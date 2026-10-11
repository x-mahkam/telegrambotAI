package uz.monitorcheck.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uz.monitorcheck.analysis.flicker.FlickerMeasurement
import uz.monitorcheck.analysis.screen.BandingResult
import uz.monitorcheck.analysis.screen.BleedResult
import uz.monitorcheck.analysis.screen.DefectKind
import uz.monitorcheck.analysis.screen.DefectResult
import uz.monitorcheck.analysis.screen.PixelFaultClass
import uz.monitorcheck.analysis.screen.TestPattern
import uz.monitorcheck.analysis.screen.UniformityResult
import uz.monitorcheck.analysis.standards.Ieee1789Assessment

/** Miltillash o'lchovi natijasi (hisobot uchun). */
data class FlickerReport(
    val measurement: FlickerMeasurement,
    val assessment: Ieee1789Assessment,
    val exposureSec: Double?,
    val iso: Int?,
    val rows: Int,
    /** Natija ikkinchi ekspozitsiyada olinganmi. */
    val fromPassB: Boolean,
    /** Qo'lda ekspozitsiya ishlatilganmi (aks holda avtomatik). */
    val manualExposure: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class ScreenWarning {
    /** Juda ko'p mayda nomzodlar — moiré yoki shovqin. */
    MOIRE,
    /** Ekran pikseli kamerada 1.2 pikseldan kichik — yakka piksellar ko'rinmasligi mumkin. */
    LOW_RESOLUTION,
    /** Tasvirning bir qismi to'yingan. */
    SATURATED,
    /** Ekran burchaklariga qarash burchagi katta (> 25°). */
    WIDE_ANGLE,
}

/** Bitta ekran testi (1–7, 9) natijasi. */
data class ScreenTestResult(
    val pattern: TestPattern,
    val uniformity: UniformityResult? = null,
    val defects: DefectResult? = null,
    val banding: BandingResult? = null,
    val bleed: BleedResult? = null,
    val maxViewAngleDeg: Double? = null,
    val warnings: Set<ScreenWarning> = emptySet(),
    val timestampMs: Long = System.currentTimeMillis(),
)

/** Piksel nuqsonlari bo'yicha umumlashma (barcha testlar). */
data class PixelSummary(val type1: Int, val type2: Int, val type3: Int, val spots: Int, val isoClass: Int, val testsDone: Int)

/** Ekran o'lchami (piksel) — EDID dan yoki foydalanuvchi tanlovi. */
data class Resolution(val width: Int, val height: Int) {
    val megapixels: Double get() = width.toDouble() * height / 1e6
    override fun toString() = "${width}×$height"

    companion object {
        val COMMON = listOf(
            Resolution(1366, 768), Resolution(1600, 900), Resolution(1920, 1080), Resolution(1920, 1200),
            Resolution(2560, 1080), Resolution(2560, 1440), Resolution(3440, 1440), Resolution(3840, 2160),
            Resolution(1280, 1024),
        )
    }
}

/**
 * Joriy tekshiruv sessiyasi (xotirada). 5-bosqichda PDF hisobot shu ma'lumotdan tuziladi.
 */
object SessionRepository {
    private val _flicker = MutableStateFlow<FlickerReport?>(null)
    val flicker: StateFlow<FlickerReport?> = _flicker.asStateFlow()

    private val _screen = MutableStateFlow<Map<TestPattern, ScreenTestResult>>(emptyMap())
    val screen: StateFlow<Map<TestPattern, ScreenTestResult>> = _screen.asStateFlow()

    private val _resolution = MutableStateFlow(Resolution(1920, 1080))
    val resolution: StateFlow<Resolution> = _resolution.asStateFlow()

    fun setFlicker(r: FlickerReport) { _flicker.value = r }
    fun putScreen(r: ScreenTestResult) = _screen.update { it + (r.pattern to r) }
    fun setResolution(r: Resolution) { _resolution.value = r }

    fun clear() {
        _flicker.value = null
        _screen.value = emptyMap()
    }

    /** ISO 9241-307 bo'yicha piksel nuqsonlari sinfi (bir joydagi nuqson bir marta hisoblanadi). */
    fun pixelSummary(results: Map<TestPattern, ScreenTestResult> = screen.value, res: Resolution = resolution.value): PixelSummary? {
        val defectResults = results.values.mapNotNull { it.defects }
        if (defectResults.isEmpty()) return null
        val merged = PixelFaultClass.merge(defectResults)
        val t1 = merged.count { it.kind == DefectKind.BRIGHT_PIXEL }
        val t2 = merged.count { it.kind == DefectKind.DARK_PIXEL }
        val t3 = merged.count { it.kind == DefectKind.SUBPIXEL }
        val spots = defectResults.sumOf { r -> r.defects.count { it.kind == DefectKind.SPOT } }
        return PixelSummary(t1, t2, t3, spots, PixelFaultClass.classify(t1, t2, t3, res.megapixels), defectResults.size)
    }
}
