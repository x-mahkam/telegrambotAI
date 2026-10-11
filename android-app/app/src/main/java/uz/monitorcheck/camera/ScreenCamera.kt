package uz.monitorcheck.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import uz.monitorcheck.analysis.image.GrayImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Piksel / bir tekislik moduli kamerasi:
 *  - ko'rinish (TextureView) + kichik YUV oqimi — markerlarni jonli topish uchun;
 *  - yuqori o'lchamli JPEG surat — tahlil uchun (ekspozitsiya kompensatsiyasi bilan).
 * Bu juftlik (PRIV PREVIEW + YUV PREVIEW + JPEG MAXIMUM) barcha Camera2 darajalarida kafolatlangan.
 */
class ScreenCamera(context: Context, val config: CameraConfig) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("monitorcheck-screen-cam").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var liveReader: ImageReader? = null
    private var jpegReader: ImageReader? = null

    /** Jonli kadr kerak bo'lganda true qilinadi; bitta kadr yuborilgach o'zi false bo'ladi. */
    val liveRequested = AtomicBoolean(true)
    var onLiveFrame: ((GrayImage) -> Unit)? = null

    private val frameCounter = AtomicInteger(0)
    @Volatile private var lastFocusDistance: Float? = null
    @Volatile private var pendingJpeg: CompletableDeferred<ByteArray>? = null

    @SuppressLint("MissingPermission")
    suspend fun open(previewSurface: Surface) {
        preview = previewSurface
        val live = ImageReader.newInstance(config.liveSize.width, config.liveSize.height, ImageFormat.YUV_420_888, 2)
        live.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return@setOnImageAvailableListener
            try {
                if (liveRequested.compareAndSet(true, false)) {
                    val y = img.planes[0]
                    val g = GrayImage.fromLuma(y.buffer, img.width, img.height, y.rowStride, y.pixelStride)
                    onLiveFrame?.invoke(g)
                }
            } finally {
                img.close()
            }
        }, handler)
        liveReader = live

        val jpeg = ImageReader.newInstance(config.jpegSize.width, config.jpegSize.height, ImageFormat.JPEG, 2)
        jpeg.setOnImageAvailableListener({ r ->
            val img = try { r.acquireNextImage() } catch (e: IllegalStateException) { null } ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                pendingJpeg?.complete(bytes)
            } finally {
                img.close()
            }
        }, handler)
        jpegReader = jpeg

        val dev = suspendCancellableCoroutine { cont ->
            manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("kamera uzildi"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("xato kodi $error"))
                }
            }, handler)
        }
        device = dev
        @Suppress("DEPRECATION")
        session = suspendCancellableCoroutine { cont ->
            dev.createCaptureSession(listOf(previewSurface, live.surface, jpeg.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("sessiya sozlanmadi"))
                }
            }, handler)
        }
        repeating(evIndex = 0, focusDistance = null)
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            frameCounter.incrementAndGet()
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { if (request.get(CaptureRequest.CONTROL_AF_MODE) != CaptureRequest.CONTROL_AF_MODE_OFF) lastFocusDistance = it }
        }
    }

    private fun baseRequest(template: Int, evIndex: Int, focusDistance: Float?): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        if (focusDistance != null && config.manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        // Konturlarni sun'iy kuchaytirish nuqsonlar atrofida "halo" hosil qiladi — o'chiriladi
        if (config.edgeOff) b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        return b
    }

    private fun repeating(evIndex: Int, focusDistance: Float?) {
        val s = session ?: return
        val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW, evIndex, focusDistance)
        preview?.let { b.addTarget(it) }
        liveReader?.let { b.addTarget(it.surface) }
        try { s.setRepeatingRequest(b.build(), callback, handler) } catch (e: Exception) { /* sessiya yopilgan */ }
    }

    private suspend fun waitFrames(n: Int) {
        val target = frameCounter.get() + n
        withTimeout(5000) {
            while (frameCounter.get() < target) kotlinx.coroutines.delay(15)
        }
    }

    /**
     * Surat olish: ekspozitsiya kompensatsiyasi [ev] (EV) o'rnatiladi, avtomatik ekspozitsiya
     * barqarorlashishi kutiladi, so'ng JPEG olinadi. [defocus] — fokus masofasini avtomatik
     * fokus qiymatining 70% iga o'zgartirish (moiré'ni kamaytirish uchun).
     */
    suspend fun capture(ev: Double, defocus: Boolean): Bitmap {
        val s = session ?: error("sessiya yo'q")
        val evIndex = config.evIndex(ev)
        repeating(evIndex, null)
        waitFrames(10)
        val focus = if (defocus && config.manualFocus) lastFocusDistance?.let { it * 0.7f } else null
        if (focus != null) {
            repeating(evIndex, focus)
            waitFrames(5)
        }
        val deferred = CompletableDeferred<ByteArray>()
        pendingJpeg = deferred
        val b = baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, evIndex, focus)
        b.addTarget(jpegReader!!.surface)
        b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        s.capture(b.build(), callback, handler)
        val bytes = withTimeout(8000) { deferred.await() }
        pendingJpeg = null
        repeating(0, null)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("JPEG o'qilmadi")
    }

    fun close() {
        try { session?.close() } catch (e: Exception) {}
        try { device?.close() } catch (e: Exception) {}
        try { liveReader?.close() } catch (e: Exception) {}
        try { jpegReader?.close() } catch (e: Exception) {}
        session = null
        device = null
        thread.quitSafely()
    }
}
