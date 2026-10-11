package uz.monitorcheck.ui.flicker

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uz.monitorcheck.analysis.dsp.Stats
import uz.monitorcheck.analysis.flicker.Calibration
import uz.monitorcheck.analysis.flicker.CalibrationResult
import uz.monitorcheck.analysis.flicker.FlickerAnalyzer
import uz.monitorcheck.analysis.flicker.FlickerMeasurement
import uz.monitorcheck.analysis.flicker.FrameResult
import uz.monitorcheck.analysis.flicker.RowTiming
import uz.monitorcheck.analysis.standards.Ieee1789
import uz.monitorcheck.camera.CameraConfig
import uz.monitorcheck.camera.CameraFrame
import uz.monitorcheck.camera.CaptureSettings
import uz.monitorcheck.camera.FlickerCamera
import uz.monitorcheck.data.CalibrationStore
import uz.monitorcheck.data.FlickerReport
import uz.monitorcheck.data.SessionRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class FlickerMode { AIMING, MEASURING, CALIBRATING, RESULT, CALIBRATION_RESULT }

/** Ekspozitsiyani boshqarish holati. */
enum class ExposureControl {
    /** Qo'lda ekspozitsiya sinab ko'rilmoqda (qurilma so'ralgan qiymatni bajaradimi). */
    PROBING,
    /** Qo'lda ekspozitsiya ishlaydi (tekshirildi). */
    MANUAL,
    /** Faqat avtomatik ekspozitsiya — qisqa ekspozitsiya kafolatlanmaydi. */
    AUTO,
}

class LiveState(
    /** Qator profili (nisbiy, o'rtacha = 1), grafik uchun qisqartirilgan. */
    val profile: FloatArray,
    val meanCode: Double,
    val saturated: Double,
    val frame: FrameResult?,
)

data class FlickerUiState(
    val config: CameraConfig? = null,
    val noCamera: Boolean = false,
    val error: String? = null,
    val mode: FlickerMode = FlickerMode.AIMING,
    val control: ExposureControl = ExposureControl.PROBING,
    /** Kadr natijasidagi HAQIQIY ekspozitsiya va ISO. */
    val actualExposureNs: Long? = null,
    val actualIso: Int? = null,
    val live: LiveState? = null,
    val timing: RowTiming = RowTiming.UNKNOWN,
    val skewTRowSec: Double? = null,
    val calibratedTRowSec: Double? = null,
    val useCalibration: Boolean = true,
    val progress: Int = 0,
    val progressTotal: Int = 0,
    val passB: Boolean = false,
    val report: FlickerReport? = null,
    val calibration: CalibrationResult? = null,
    val calibrationFailed: Boolean = false,
)

/**
 * Miltillash o'lchovi jarayoni:
 *  PROBING     — qo'lda ekspozitsiya so'raladi va kadr natijasidagi haqiqiy qiymat bilan
 *                solishtiriladi (ba'zi qurilmalar MANUAL_SENSOR e'lon qilmasa ham bajaradi,
 *                ba'zilari e'lon qilib ham bajarmaydi);
 *  AIMING      — jonli ko'rinish, avtomatik ISO;
 *  MEASURING   — A o'tish: [FRAMES] kadr; miltillash topilmasa — B o'tish boshqa ekspozitsiyada;
 *  CALIBRATING — 100 Hz lampa bo'yicha t_row.
 */
class FlickerViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val FRAMES = 24
        const val CALIB_FRAMES = 30
        private const val SKIP_AFTER_LOCK = 2
        private const val PROBE_FRAMES = 6
    }

    private val store = CalibrationStore(app)
    private val analyzer = FlickerAnalyzer()
    private val calibAnalyzer = FlickerAnalyzer(FlickerAnalyzer.Config(minCyclesPerFrame = 1.2))

    private val _state = MutableStateFlow(FlickerUiState())
    val state: StateFlow<FlickerUiState> = _state.asStateFlow()

    private var camera: FlickerCamera? = null
    private var collectJob: Job? = null
    private val mutex = Mutex()

    private fun locked(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { mutex.withLock { block() } }
    }

    private var frameCounter = 0
    private var lockedSettingsId = -1
    private var skipped = 0
    private val collected = ArrayList<Pair<CameraFrame, FrameResult>>()
    private var passAResult: FlickerMeasurement? = null
    private var calibrationRef = 100.0
    private val probeExposures = ArrayList<Long>()

    init {
        val cfg = try {
            CameraConfig.select(app.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
        } catch (e: Exception) {
            null
        }
        _state.update {
            it.copy(
                config = cfg,
                noCamera = cfg == null,
                control = if (cfg?.aeOffSupported == true) ExposureControl.PROBING else ExposureControl.AUTO,
                calibratedTRowSec = cfg?.let { c -> store.tRowSec(c.calibrationKey) },
                useCalibration = store.useCalibration,
            )
        }
    }

    // ---- Kamera hayot sikli ---------------------------------------------------------

    fun onSurfaceReady(surface: Surface) {
        val cfg = _state.value.config ?: return
        if (camera != null) return
        val cam = FlickerCamera(getApplication(), cfg)
        camera = cam
        probeExposures.clear()
        collectJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                cam.open(surface)
                cam.frames.collect { f -> mutex.withLock { onFrame(cam, f) } }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun onSurfaceDestroyed() {
        collectJob?.cancel()
        collectJob = null
        camera?.close()
        camera = null
        if (_state.value.mode == FlickerMode.MEASURING || _state.value.mode == FlickerMode.CALIBRATING) {
            _state.update { it.copy(mode = FlickerMode.AIMING) }
        }
    }

    override fun onCleared() = onSurfaceDestroyed()

    // ---- Foydalanuvchi amallari ---------------------------------------------------------

    fun startMeasurement() = locked {
        val cam = camera ?: return@locked
        if (_state.value.control == ExposureControl.PROBING) return@locked
        collected.clear()
        passAResult = null
        lockedSettingsId = cam.currentSettingsId
        skipped = 0
        _state.update { it.copy(mode = FlickerMode.MEASURING, progress = 0, progressTotal = FRAMES, passB = false, report = null) }
    }

    fun startCalibration(referenceHz: Double) = locked {
        if (camera == null) return@locked
        calibrationRef = referenceHz
        collected.clear()
        skipped = 0
        _state.update {
            it.copy(mode = FlickerMode.CALIBRATING, progress = 0, progressTotal = CALIB_FRAMES, calibration = null, calibrationFailed = false)
        }
    }

    fun backToAiming() = locked { _state.update { it.copy(mode = FlickerMode.AIMING) } }

    fun clearCalibration() {
        val cfg = _state.value.config ?: return
        store.clear(cfg.calibrationKey)
        _state.update { it.copy(calibratedTRowSec = null) }
    }

    fun setUseCalibration(use: Boolean) {
        store.useCalibration = use
        _state.update { it.copy(useCalibration = use) }
    }

    // ---- Kadrlarga ishlov berish ----------------------------------------------------------

    private fun currentTiming(frame: CameraFrame): Pair<RowTiming, Double?> {
        val skewT = frame.skewNs?.takeIf { it > 0 && frame.rows > 1 }?.let { RowTiming.fromSkew(it, frame.rows) }
        val st = _state.value
        val cal = st.calibratedTRowSec
        val timing = when {
            cal != null && st.useCalibration -> RowTiming.calibrated(cal)
            skewT != null -> skewT
            cal != null -> RowTiming.calibrated(cal)
            else -> RowTiming.UNKNOWN
        }
        return timing to skewT?.tRowSec
    }

    private fun onFrame(cam: FlickerCamera, frame: CameraFrame) {
        frameCounter++
        val (timing, skewT) = currentTiming(frame)
        val expSec = frame.exposureNs?.let { it * 1e-9 }
        _state.update { it.copy(actualExposureNs = frame.exposureNs, actualIso = frame.iso, timing = timing, skewTRowSec = skewT) }

        if (_state.value.control == ExposureControl.PROBING) {
            probe(cam, frame)
            return
        }
        when (_state.value.mode) {
            FlickerMode.AIMING, FlickerMode.RESULT, FlickerMode.CALIBRATION_RESULT -> {
                autoExpose(cam, frame)
                val fr = if (frameCounter % 3 == 0 && _state.value.mode == FlickerMode.AIMING)
                    analyzer.analyzeFrame(frame.profile, timing, expSec) else null
                _state.update {
                    it.copy(live = LiveState(downsample(frame.profile.values), frame.profile.meanCode,
                        frame.profile.saturatedFraction, fr ?: it.live?.frame))
                }
            }
            FlickerMode.MEASURING -> {
                if (frame.settingsId != lockedSettingsId) return
                if (skipped < SKIP_AFTER_LOCK) { skipped++; return }
                collected += frame to analyzer.analyzeFrame(frame.profile, timing, expSec)
                _state.update { it.copy(progress = collected.size) }
                if (collected.size >= FRAMES) finishPass(cam, timing)
            }
            FlickerMode.CALIBRATING -> {
                autoExpose(cam, frame)
                collected += frame to calibAnalyzer.analyzeFrame(frame.profile, RowTiming.UNKNOWN, expSec)
                _state.update { it.copy(progress = collected.size) }
                if (collected.size >= CALIB_FRAMES) finishCalibration(skewT)
            }
        }
    }

    /**
     * Qo'lda ekspozitsiya haqiqatan bajariladimi: so'ralgan T_e va kadr natijasidagi
     * SENSOR_EXPOSURE_TIME ±30% ichida bo'lsa — MANUAL, aks holda AUTO rejimga o'tiladi.
     */
    private fun probe(cam: FlickerCamera, frame: CameraFrame) {
        if (frame.settingsId != cam.currentSettingsId) return
        frame.exposureNs?.let { probeExposures += it }
        _state.update {
            it.copy(live = LiveState(downsample(frame.profile.values), frame.profile.meanCode, frame.profile.saturatedFraction, null))
        }
        if (probeExposures.size < PROBE_FRAMES && frame.exposureNs != null) return
        val med = if (probeExposures.isEmpty()) null else Stats.median(probeExposures.map { it.toDouble() }.toDoubleArray())
        val req = cam.settings.exposureNs.toDouble()
        val ok = med != null && abs(med - req) / req < 0.3
        if (ok) {
            _state.update { it.copy(control = ExposureControl.MANUAL) }
        } else {
            cam.apply(cam.settings.copy(manual = false))
            _state.update { it.copy(control = ExposureControl.AUTO) }
        }
    }

    private fun finishPass(cam: FlickerCamera, timing: RowTiming) {
        val frames = collected.map { it.second }
        val expSec = medianExposure()
        val m = analyzer.combine(frames, timing, expSec, cam.response)
        val st = _state.value
        val manual = st.control == ExposureControl.MANUAL

        if (!m.detected && !st.passB && manual) {
            // B o'tish: ekspozitsiyani ~0.6× (imkon bo'lmasa ~1.6×) o'zgartirib — f ≈ k/T_e "ko'r
            // nuqta"lari ikkinchi ekspozitsiyada boshqa joyga tushadi.
            passAResult = m
            val s = cam.settings
            val range = cam.config.exposureRangeNs
            val shorter = (s.exposureNs * 0.6).roundToLong()
            val newExp = if (range == null || shorter >= range.lower) shorter
            else min((s.exposureNs * 1.6).roundToLong(), CameraConfig.MAX_EXPOSURE_NS)
            val iso = clampIso(cam, (s.iso * s.exposureNs.toDouble() / newExp).roundToInt())
            cam.apply(s.copy(exposureNs = newExp, iso = iso))
            lockedSettingsId = cam.currentSettingsId
            skipped = 0
            collected.clear()
            _state.update { it.copy(passB = true, progress = 0) }
            return
        }

        val a = passAResult
        val final = when {
            m.detected -> m
            a != null -> a.copy(
                detectionLimitPct = max(a.detectionLimitPct, m.detectionLimitPct),
                coverageHz = listOfNotNull(a.coverageHz, m.coverageHz).maxOrNull(),
            )
            else -> m
        }
        val assess = Ieee1789.assess(
            detected = final.detected,
            frequencyHz = final.frequencyHz?.mean,
            modulationPct = final.modulationPct?.mean,
            modHighPct = final.modulationPct?.high?.takeIf { !it.isNaN() },
            fLowHz = final.frequencyHz?.low?.takeIf { !it.isNaN() },
            fHighHz = final.frequencyHz?.high?.takeIf { !it.isNaN() },
            coverageHz = final.coverageHz,
        )
        val report = FlickerReport(
            measurement = final,
            assessment = assess,
            exposureSec = final.exposureSec,
            iso = collected.lastOrNull()?.first?.iso,
            rows = collected.lastOrNull()?.first?.rows ?: 0,
            fromPassB = st.passB && m.detected,
            manualExposure = manual,
        )
        collected.clear()
        SessionRepository.setFlicker(report)
        _state.update { it.copy(mode = FlickerMode.RESULT, report = report) }
    }

    private fun finishCalibration(skewT: Double?) {
        val cfg = _state.value.config
        val res = Calibration.fromFrames(collected.map { it.second }, calibrationRef, skewT)
        collected.clear()
        if (res != null && cfg != null) {
            store.save(cfg.calibrationKey, res.tRow.mean)
            _state.update {
                it.copy(mode = FlickerMode.CALIBRATION_RESULT, calibration = res, calibrationFailed = false, calibratedTRowSec = res.tRow.mean)
            }
        } else {
            _state.update { it.copy(mode = FlickerMode.CALIBRATION_RESULT, calibration = null, calibrationFailed = true) }
        }
    }

    // ---- Avtomatik ISO (qo'lda rejimda) -----------------------------------------------------

    /** Ekspozitsiya qisqa qoladi (≤ 1/5000 s — qamrov ≥ 3 kHz), yorqinlik asosan ISO bilan. */
    private fun autoExpose(cam: FlickerCamera, frame: CameraFrame) {
        val s = cam.settings
        if (!s.manual || frame.settingsId != cam.currentSettingsId || frameCounter % 3 != 0) return
        val mean = frame.profile.meanCode
        val sat = frame.profile.saturatedFraction
        val factor = when {
            sat > 0.003 -> 0.6
            mean < 0.30 || mean > 0.65 -> (0.45 / max(mean, 0.02)).coerceIn(0.25, 4.0)
            else -> return
        }
        var iso = (s.iso * factor).roundToInt()
        var exp = s.exposureNs
        val isoR = cam.config.isoRange
        val expR = cam.config.exposureRangeNs
        if (isoR != null && iso < isoR.lower) {
            exp = max((exp * iso.toDouble() / isoR.lower).roundToLong(), expR?.lower ?: exp)
            iso = isoR.lower
        }
        if (isoR != null && iso > isoR.upper) {
            exp = min((exp * iso.toDouble() / isoR.upper).roundToLong(), CameraConfig.MAX_EXPOSURE_NS)
            iso = isoR.upper
        }
        if (iso != s.iso || exp != s.exposureNs) cam.apply(CaptureSettings(true, exp, iso))
    }

    private fun clampIso(cam: FlickerCamera, iso: Int): Int =
        cam.config.isoRange?.let { iso.coerceIn(it.lower, it.upper) } ?: iso

    private fun medianExposure(): Double? {
        val e = collected.mapNotNull { it.first.exposureNs?.toDouble() }
        return if (e.isEmpty()) null else Stats.median(e.toDoubleArray()) * 1e-9
    }

    private fun downsample(v: DoubleArray, points: Int = 256): FloatArray {
        val mean = v.average().takeIf { it > 0 } ?: 1.0
        val step = v.size.toDouble() / points
        return FloatArray(min(points, v.size)) { i ->
            val a = (i * step).toInt()
            val b = max(a + 1, ((i + 1) * step).toInt()).coerceAtMost(v.size)
            var s = 0.0
            for (k in a until b) s += v[k]
            (s / (b - a) / mean).toFloat()
        }
    }
}
