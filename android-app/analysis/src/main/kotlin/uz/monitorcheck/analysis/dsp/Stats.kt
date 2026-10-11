package uz.monitorcheck.analysis.dsp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** O'rtacha qiymat va 95% ishonch oralig'i. */
data class Estimate(
    val mean: Double,
    val std: Double,
    val n: Int,
    /** 95% ishonch oralig'ining yarim kengligi: t_{0.975, n−1}·s/√n. n = 1 da NaN. */
    val ci95: Double,
) {
    val low: Double get() = mean - ci95
    val high: Double get() = mean + ci95
}

object Stats {

    fun mean(x: DoubleArray): Double = if (x.isEmpty()) Double.NaN else x.sum() / x.size

    /** Tanlanma standart chetlanishi (n − 1 maxraj bilan). */
    fun std(x: DoubleArray): Double {
        if (x.size < 2) return Double.NaN
        val m = mean(x)
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val s = x.sortedArray()
        val h = s.size / 2
        return if (s.size % 2 == 1) s[h] else 0.5 * (s[h - 1] + s[h])
    }

    /**
     * Styudent t-taqsimotining 0.975-kvantili (ikki tomonlama 95%), erkinlik darajasi df.
     * Qiymatlar standart jadvallardan (masalan, NIST/SEMATECH e-Handbook, Table 1.4.2.3.2).
     */
    fun tQuantile975(df: Int): Double {
        val table = doubleArrayOf(
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
        )
        return when {
            df < 1 -> Double.NaN
            df <= 30 -> table[df - 1]
            df <= 40 -> 2.021
            df <= 60 -> 2.000
            df <= 120 -> 1.980
            else -> 1.960
        }
    }

    fun estimate(x: DoubleArray): Estimate {
        val n = x.size
        val m = mean(x)
        val s = std(x)
        val ci = if (n >= 2) tQuantile975(n - 1) * s / sqrt(n.toDouble()) else Double.NaN
        return Estimate(m, s, n, ci)
    }

    /**
     * Doiraviy statistika: fazalarning natijaviy vektori uzunligi R ∈ [0; 1].
     * R ≈ 1 — fazalar bir xil (statik naqsh), R ≈ 0 — tasodifiy.
     * Manba: K. V. Mardia, P. E. Jupp, "Directional Statistics", 2000, §2.3.
     */
    fun circularResultantLength(phases: DoubleArray): Double {
        if (phases.isEmpty()) return Double.NaN
        val c = phases.sumOf { cos(it) }
        val s = phases.sumOf { sin(it) }
        return hypot(c, s) / phases.size
    }

    fun circularMean(phases: DoubleArray): Double =
        atan2(phases.sumOf { sin(it) }, phases.sumOf { cos(it) })
}
