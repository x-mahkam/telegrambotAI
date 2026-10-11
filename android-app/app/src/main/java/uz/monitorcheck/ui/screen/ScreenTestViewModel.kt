package uz.monitorcheck.ui.screen

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.monitorcheck.analysis.geometry.ArucoDetector
import uz.monitorcheck.analysis.image.GrayImage
import uz.monitorcheck.analysis.image.Pt
import uz.monitorcheck.analysis.image.RgbImage
import uz.monitorcheck.analysis.screen.BandingAnalyzer
import uz.monitorcheck.analysis.screen.BleedAnalyzer
import uz.monitorcheck.analysis.screen.PixelDefectAnalyzer
import uz.monitorcheck.analysis.screen.ScreenGeometry
import uz.monitorcheck.analysis.screen.ScreenLocator
import uz.monitorcheck.analysis.screen.TestPattern
import uz.monitorcheck.analysis.screen.UniformityAnalyzer
import uz.monitorcheck.camera.CameraConfig
import uz.monitorcheck.camera.ScreenCamera
import uz.monitorcheck.data.Resolution
import uz.monitorcheck.data.ScreenTestResult
import uz.monitorcheck.data.ScreenWarning
import uz.monitorcheck.data.SessionRepository
import kotlin.math.max

/** Jonli ko'rinishdagi aniqlash natijasi. */
data class LiveDetection(
    val pattern: TestPattern?,
    val markersFound: Int,
    /** To'rtala marker topildi — ekran geometriyasi tiklandi. */
    val ready: Boolean,
    /** Ekran (yoki markerlar) konturi, ko'rinish koordinatalarida (0..1). */
    val outline: List<Pt>,
)

enum class ScreenPhase { AIMING, CAPTURING, ANALYZING }

enum class ScreenError { NO_GEOMETRY, NOT_ANALYZABLE, CAMERA, MEMORY }

data class ScreenUiState(
    val config: CameraConfig? = null,
    val noCamera: Boolean = false,
    val phase: ScreenPhase = ScreenPhase.AIMING,
    val live: LiveDetection? = null,
    val selected: TestPattern? = null,
    val defocus: Boolean = false,
    val error: ScreenError? = null,
    val errorDetail: String? = null,
)

class ScreenTestViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ScreenUiState())
    val state: StateFlow<ScreenUiState> = _state.asStateFlow()

    private var camera: ScreenCamera? = null
    private val detector = ArucoDetector()

    init {
        val cfg = try {
            CameraConfig.select(app.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
        } catch (e: Exception) {
            null
        }
        _state.update { it.copy(config = cfg, noCamera = cfg == null) }
    }

    fun onSurfaceReady(surface: Surface) {
        val cfg = _state.value.config ?: return
        if (camera != null) return
        val cam = ScreenCamera(getApplication(), cfg)
        camera = cam
        cam.onLiveFrame = { g -> viewModelScope.launch(Dispatchers.Default) { processLive(cam, g) } }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                cam.open(surface)
            } catch (e: Exception) {
                _state.update { it.copy(error = ScreenError.CAMERA, errorDetail = e.message) }
            }
        }
    }

    fun onSurfaceDestroyed() {
        camera?.close()
        camera = null
    }

    override fun onCleared() = onSurfaceDestroyed()

    fun setDefocus(v: Boolean) = _state.update { it.copy(defocus = v) }
    fun select(p: TestPattern) = _state.update { it.copy(selected = p) }
    fun setResolution(r: Resolution) = SessionRepository.setResolution(r)
    fun dismissError() = _state.update { it.copy(error = null, errorDetail = null) }

    // ---- Jonli aniqlash ------------------------------------------------------------------

    private suspend fun processLive(cam: ScreenCamera, g: GrayImage) {
        try {
            if (_state.value.phase == ScreenPhase.AIMING) {
                val markers = detector.detect(g)
                val geom = ScreenLocator.locate(markers)
                val pattern = geom?.pattern ?: ScreenLocator.identify(markers)
                val pts = geom?.corners ?: markers.map { it.center }
                val orient = cam.config.sensorOrientation
                val outline = pts.map { toDisplay(it, g.width, g.height, orient) }
                _state.update { it.copy(live = LiveDetection(pattern, markers.size, geom != null, outline)) }
            }
        } catch (e: Exception) {
            // jonli tahlil xatosi — keyingi kadrda qayta uriniladi
        }
        delay(250)
        cam.liveRequested.set(true)
    }

    /** Sensor yo'nalishidagi tasvir nuqtasi → portret ko'rinishdagi normallashgan nuqta. */
    private fun toDisplay(p: Pt, w: Int, h: Int, orientation: Int): Pt = when (orientation) {
        90 -> Pt(1 - p.y / h, p.x / w)
        270 -> Pt(p.y / h, 1 - p.x / w)
        180 -> Pt(1 - p.x / w, 1 - p.y / h)
        else -> Pt(p.x / w, p.y / h)
    }

    // ---- Suratga olish va tahlil ---------------------------------------------------------

    fun capture() {
        val cam = camera ?: return
        val live = _state.value.live ?: return
        val pattern = live.pattern ?: return
        if (_state.value.phase != ScreenPhase.AIMING) return
        if (pattern == TestPattern.FLICKER || pattern == TestPattern.GRADIENT) {
            _state.update { it.copy(error = ScreenError.NOT_ANALYZABLE) }
            return
        }
        _state.update { it.copy(phase = ScreenPhase.CAPTURING, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val res = runCapture(cam, pattern, _state.value.defocus)
                SessionRepository.putScreen(res)
                _state.update { it.copy(selected = res.pattern) }
            } catch (e: NoGeometry) {
                _state.update { it.copy(error = ScreenError.NO_GEOMETRY) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(error = ScreenError.MEMORY) }
            } catch (e: Exception) {
                _state.update { it.copy(error = ScreenError.CAMERA, errorDetail = e.message) }
            } finally {
                _state.update { it.copy(phase = ScreenPhase.AIMING) }
            }
        }
    }

    private class NoGeometry : Exception()

    /** Ekspozitsiya kompensatsiyasi (EV): oq ekran to'yinmasligi, qora testda markerlar ko'rinishi uchun. */
    private fun evFor(p: TestPattern): Double = when (p) {
        TestPattern.WHITE, TestPattern.GRADIENT_STEPS -> -1.0
        TestPattern.BLACK, TestPattern.BLEED -> -2.0
        else -> -0.5
    }

    private suspend fun runCapture(cam: ScreenCamera, expected: TestPattern, defocus: Boolean): ScreenTestResult {
        var ev = evFor(expected)
        val twoShots = expected == TestPattern.BLACK || expected == TestPattern.BLEED
        var attempt = 0
        while (true) {
            val geom: ScreenGeometry
            val analysis: RgbImage
            if (twoShots) {
                // Qora testlar: markerlar qisqa ekspozitsiyada o'qiladi, tahlil — uzunroq ekspozitsiyali
                // ikkinchi suratda (telefon qimirlamagan deb olinadi). Birinchi tasvir darhol bo'shatiladi.
                geom = locate(shoot(cam, ev, defocus)) ?: throw NoGeometry()
                analysis = shoot(cam, 1.0, defocus)
            } else {
                analysis = shoot(cam, ev, defocus)
                geom = locate(analysis) ?: throw NoGeometry()
            }
            _state.update { it.copy(phase = ScreenPhase.ANALYZING) }
            val saturated = !twoShots && saturatedFraction(analysis, geom) > 0.01
            if (saturated && attempt == 0 && cam.config.aeCompensationRange != null) {
                attempt++
                ev -= 1.0
                continue
            }
            return analyze(analysis, geom, saturated, cam.config)
        }
    }

    private suspend fun shoot(cam: ScreenCamera, ev: Double, defocus: Boolean): RgbImage {
        _state.update { it.copy(phase = ScreenPhase.CAPTURING) }
        return toRgb(cam.capture(ev, defocus))
    }

    private fun locate(img: RgbImage): ScreenGeometry? {
        val lum = img.luminance()
        val factor = max(1, max(img.width, img.height) / 1400)
        return ScreenLocator.locate(detector.detectScaled(lum, factor))
    }

    private fun analyze(img: RgbImage, g: ScreenGeometry, saturated: Boolean, cfg: CameraConfig): ScreenTestResult {
        val res = SessionRepository.resolution.value
        val lum = img.luminance()
        val warnings = mutableSetOf<ScreenWarning>()
        if (saturated) warnings += ScreenWarning.SATURATED
        val angle = cfg.jpegFocalPx?.let { f ->
            // JPEG kengligi konfiguratsiyadagidan farq qilsa, fokus masofasi masshtablanadi
            val fpx = f * img.width / cfg.jpegSize.width
            g.cornerViewAnglesDeg(img.width / 2.0, img.height / 2.0, fpx).max()
        }
        if (angle != null && angle > 25) warnings += ScreenWarning.WIDE_ANGLE

        var result = ScreenTestResult(g.pattern, maxViewAngleDeg = angle)
        when (g.pattern) {
            TestPattern.WHITE, TestPattern.GRAY50 -> {
                val d = PixelDefectAnalyzer.analyze(img, g, res.width)
                result = result.copy(uniformity = UniformityAnalyzer.analyze(lum, g), defects = d)
            }
            TestPattern.BLACK, TestPattern.RED, TestPattern.GREEN, TestPattern.BLUE ->
                result = result.copy(defects = PixelDefectAnalyzer.analyze(img, g, res.width))
            TestPattern.BLEED -> result = result.copy(bleed = BleedAnalyzer.analyze(lum, g))
            TestPattern.GRADIENT_STEPS -> result = result.copy(banding = BandingAnalyzer.analyze(lum, g))
            else -> Unit
        }
        result.defects?.let {
            if (it.moireSuspected) warnings += ScreenWarning.MOIRE
            if (it.lowResolution) warnings += ScreenWarning.LOW_RESOLUTION
        }
        return result.copy(warnings = warnings)
    }

    /** Ekran ichidagi namunalarda biror kanal ≥ 0.98 (chiziqli) bo'lgan ulush. */
    private fun saturatedFraction(img: RgbImage, g: ScreenGeometry): Double {
        var n = 0
        var s = 0
        for (j in 0 until 60) for (i in 0 until 100) {
            val p = g.imagePoint(0.05 + 0.9 * (i + 0.5) / 100, 0.05 + 0.9 * (j + 0.5) / 60)
            val x = p.x.toInt()
            val y = p.y.toInt()
            if (x !in 0 until img.width || y !in 0 until img.height) continue
            val k = y * img.width + x
            n++
            if (img.r[k] >= 0.98f || img.g[k] >= 0.98f || img.b[k] >= 0.98f) s++
        }
        return if (n > 0) s.toDouble() / n else 0.0
    }

    private fun toRgb(bmp: Bitmap): RgbImage {
        val w = bmp.width
        val h = bmp.height
        val img = RgbImage.fromArgbRows(w, h) { y, buf -> bmp.getPixels(buf, 0, w, 0, y, w, 1) }
        bmp.recycle()
        return img
    }
}
