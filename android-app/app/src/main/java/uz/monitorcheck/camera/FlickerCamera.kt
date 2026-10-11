package uz.monitorcheck.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import uz.monitorcheck.analysis.flicker.ResponseModel
import uz.monitorcheck.analysis.flicker.RowProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Kamera sozlamalari (qo'lda rejimda). */
data class CaptureSettings(
    val manual: Boolean,
    val exposureNs: Long,
    val iso: Int,
)

/** Bitta kadr: qator profili + shu kadr metama'lumotlari. */
class CameraFrame(
    val profile: RowProfile,
    val timestampNs: Long,
    /** Haqiqiy ekspozitsiya (CaptureResult.SENSOR_EXPOSURE_TIME). */
    val exposureNs: Long?,
    val iso: Int?,
    /** CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW, ns (qurilma bersa). */
    val skewNs: Long?,
    val rows: Int,
    /** Qaysi sozlamalar to'plami bilan olingan (sozlama o'zgargandan keyingi kadrlarni ajratish uchun). */
    val settingsId: Int,
)

/**
 * Camera2 orqali miltillash o'lchovi uchun kadrlar oqimi.
 *
 * Har bir kadr uchun:
 *   ImageReader (YUV_420_888) → Y tekisligidan qator profili (darhol, tasvir yopiladi);
 *   CaptureCallback → shu kadrning TotalCaptureResult'i (ekspozitsiya, ISO, skew).
 * Ikkalasi SENSOR_TIMESTAMP bo'yicha juftlanadi.
 */
class FlickerCamera(context: Context, val config: CameraConfig) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("monitorcheck-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val profiles = ConcurrentHashMap<Long, RowProfile>()
    private val results = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val settingsCounter = AtomicInteger(0)

    @Volatile
    var currentSettingsId: Int = 0
        private set

    @Volatile
    var settings: CaptureSettings = initialSettings()
        private set

    val response: ResponseModel =
        if (config.linearTonemap) ResponseModel.LINEAR else ResponseModel.SRGB_APPROX

    private val _frames = MutableSharedFlow<CameraFrame>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<CameraFrame> = _frames.asSharedFlow()

    private fun initialSettings(): CaptureSettings {
        val exp = config.exposureRangeNs?.let {
            CameraConfig.TARGET_EXPOSURE_NS.coerceIn(it.lower, it.upper)
        } ?: CameraConfig.TARGET_EXPOSURE_NS
        val iso = config.isoRange?.let { (it.lower * 4).coerceIn(it.lower, it.upper) } ?: 400
        return CaptureSettings(config.manualSensor, exp, iso)
    }

    @SuppressLint("MissingPermission")
    suspend fun open(preview: Surface) {
        previewSurface = preview
        val r = ImageReader.newInstance(config.yuvSize.width, config.yuvSize.height, ImageFormat.YUV_420_888, 4)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r

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
        val s = suspendCancellableCoroutine { cont ->
            dev.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("sessiya sozlanmadi"))
                }
            }, handler)
        }
        session = s
        apply(settings)
    }

    /** Yangi sozlamalarni qo'llash (takrorlanuvchi so'rov qayta beriladi). */
    fun apply(newSettings: CaptureSettings) {
        val dev = device ?: return
        val s = session ?: return
        val prev = previewSurface ?: return
        val rdr = reader ?: return
        settings = newSettings
        val id = settingsCounter.incrementAndGet()

        val template = if (newSettings.manual) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_PREVIEW
        val b = dev.createCaptureRequest(template)
        b.addTarget(prev)
        b.addTarget(rdr.surface)
        if (newSettings.manual) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, newSettings.exposureNs)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, newSettings.iso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // Antibanding avtomatik ekspozitsiyani 10 ms ga karrali qiladi va 100 Hz polosalarni
            // yo'qotadi — o'chiriladi. Haqiqiy ekspozitsiya har kadr natijasidan o'qiladi.
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)
        }
        if (config.manualFocus) {
            // Fokus cheksizlikda: 5–15 sm dagi ekran xiralashadi, piksel to'ri (moiré) yo'qoladi.
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
        if (config.noiseReductionOff) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        if (config.edgeOff) b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        if (config.linearTonemap) {
            // Chiziqli tonemap: chiqish = kirish (nuqtalar (0,0) va (1,1)), uchala kanal uchun.
            val lin = floatArrayOf(0f, 0f, 1f, 1f)
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(lin, lin, lin))
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.setTag(id)
        try {
            s.setRepeatingRequest(b.build(), captureCallback, handler)
            currentSettingsId = id
        } catch (e: Exception) {
            // Sessiya yopilgan bo'lishi mumkin — e'tiborsiz qoldiriladi
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            results[ts] = result
            pair(ts)
        }
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val y = img.planes[0]
            val p = RowProfile.fromLuma(
                buffer = y.buffer,
                width = img.width,
                height = img.height,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
                response = response,
            )
            profiles[img.timestamp] = p
            pair(img.timestamp)
        } finally {
            img.close()
        }
    }

    private fun pair(ts: Long) {
        val res = results[ts] ?: return
        val prof = profiles.remove(ts) ?: return
        results.remove(ts)
        _frames.tryEmit(
            CameraFrame(
                profile = prof,
                timestampNs = ts,
                exposureNs = res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = res.get(CaptureResult.SENSOR_SENSITIVITY),
                skewNs = if (config.hasSkew) res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) else null,
                rows = prof.rows,
                settingsId = (res.request.tag as? Int) ?: 0,
            ),
        )
        // Juftlanmay qolgan eski yozuvlarni tozalash
        if (profiles.size > 16) profiles.keys.sorted().take(profiles.size - 8).forEach { profiles.remove(it) }
        if (results.size > 16) results.keys.sorted().take(results.size - 8).forEach { results.remove(it) }
    }

    fun close() {
        try { session?.close() } catch (e: Exception) {}
        try { device?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        session = null
        device = null
        reader = null
        thread.quitSafely()
    }
}
