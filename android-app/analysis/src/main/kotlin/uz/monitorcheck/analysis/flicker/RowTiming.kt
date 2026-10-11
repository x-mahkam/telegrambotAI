package uz.monitorcheck.analysis.flicker

import uz.monitorcheck.analysis.dsp.Estimate
import uz.monitorcheck.analysis.dsp.Stats

enum class TimingSource {
    /** Camera2 CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW dan. */
    SENSOR_SKEW,

    /** Ma'lum chastotali manba (tarmoq lampasi, 100 Hz) bo'yicha tajribaviy kalibrovka. */
    CALIBRATED,

    /** Noma'lum — faqat sifat jihatidan xulosa ("miltillash bor/yo'q"). */
    UNKNOWN,
}

/**
 * Rolling shutter: r-qatorning ekspozitsiyasi t_r = t_0 + r·t_row da boshlanadi.
 * Yorug'lik L(t) chastotasi f bilan davriy bo'lsa, qator profili fazoviy chastota
 * ν = f·t_row (sikl/qator) bilan davriy bo'ladi. Demak
 *
 *      f = ν / t_row.
 *
 * Manba: C.-K. Liang va b., "Analysis and compensation of rolling shutter effect",
 * IEEE Trans. Image Processing 17 (2008) 1323; Android CaptureResult
 * SENSOR_ROLLING_SHUTTER_SKEW hujjati ("time between the start of exposure of the
 * first row and the start of exposure of the last row").
 */
data class RowTiming(val tRowSec: Double?, val source: TimingSource) {

    fun frequencyHz(cyclesPerRow: Double): Double? = tRowSec?.let { cyclesPerRow / it }

    /** O'lchanadigan eng yuqori chastota (Naykvist): f_max = 1/(2·t_row). */
    val nyquistHz: Double? get() = tRowSec?.let { 0.5 / it }

    companion object {
        val UNKNOWN = RowTiming(null, TimingSource.UNKNOWN)

        /**
         * t_row = skew / (H − 1): skew birinchi va oxirgi qator boshlanishi orasidagi vaqt,
         * H — chiqish tasviridagi qatorlar soni (tasvir butun o'qish oralig'ini qamraydi deb
         * hisoblanadi; masshtablash chiziqli bo'lgani uchun bu shart bajariladi).
         */
        fun fromSkew(skewNs: Long, rows: Int): RowTiming {
            require(rows > 1 && skewNs > 0)
            return RowTiming(skewNs * 1e-9 / (rows - 1), TimingSource.SENSOR_SKEW)
        }

        /** Kalibrovka: ma'lum f_ref uchun o'lchangan ν dan t_row = ν / f_ref. */
        fun calibrated(tRowSec: Double) = RowTiming(tRowSec, TimingSource.CALIBRATED)
    }
}

/** Tarmoq lampasi bo'yicha kalibrovka natijasi. */
data class CalibrationResult(
    val referenceHz: Double,
    val tRow: Estimate,
    /** Sensor skew bo'yicha t_row (bo'lsa) bilan nisbiy farq, %. */
    val deviationFromSkewPct: Double?,
    val framesUsed: Int,
)

object Calibration {
    /**
     * Har bir kadrda o'lchangan ν_i dan t_row,i = ν_i / f_ref; natija — o'rtacha va 95% CI.
     * f_ref: 50 Hz tarmoqda lampalar yorug'ligi 100 Hz da tebranadi (quvvat ∝ sin²).
     */
    fun fromFrames(frames: List<FrameResult>, referenceHz: Double, skewTRowSec: Double?): CalibrationResult? {
        val nus = frames.filter { it.detected }.map { it.cyclesPerRow }.toDoubleArray()
        if (nus.size < 3) return null
        val med = Stats.median(nus)
        val kept = nus.filter { kotlin.math.abs(it - med) / med < 0.03 }.map { it / referenceHz }.toDoubleArray()
        if (kept.size < 3) return null
        val est = Stats.estimate(kept)
        val dev = skewTRowSec?.let { 100.0 * (est.mean - it) / it }
        return CalibrationResult(referenceHz, est, dev, kept.size)
    }
}
