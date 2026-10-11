package uz.monitorcheck.analysis.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Signalga ishlov berish uchun yordamchi funksiyalar. */
object Signal {

    /**
     * Hann oynasi: w_n = 0.5·(1 − cos(2πn/(N−1))).
     * Spektral "oqish"ni (leakage) kamaytiradi; asosiy cho'qqi kengligi ±2 bin.
     * Manba: F. J. Harris, Proc. IEEE 66 (1978) 51–83.
     */
    fun hann(n: Int): DoubleArray =
        if (n == 1) doubleArrayOf(1.0) else DoubleArray(n) { 0.5 * (1 - cos(2 * PI * it / (n - 1))) }

    /** Normallashgan sinc: sinc(x) = sin(πx)/(πx), sinc(0) = 1. */
    fun sinc(x: Double): Double = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

    /**
     * Parabolik interpolyatsiya: uchta qo'shni nuqta (y[k−1], y[k], y[k+1]) orqali
     * cho'qqining kasr siljishi δ ∈ (−0.5; 0.5):  δ = ½·(y₋ − y₊)/(y₋ − 2y₀ + y₊).
     * Manba: J. O. Smith, "Spectral Audio Signal Processing", 2011, "Quadratic interpolation".
     */
    fun parabolicPeakOffset(ym: Double, y0: Double, yp: Double): Double {
        val den = ym - 2 * y0 + yp
        if (abs(den) < 1e-300) return 0.0
        return (0.5 * (ym - yp) / den).coerceIn(-0.5, 0.5)
    }

    /**
     * Eng kichik kvadratlar usuli: A·c ≈ y, normal tenglamalar (AᵀA)c = Aᵀy
     * Gauss usuli (asosiy elementni tanlash bilan) orqali yechiladi.
     * [columns] — har bir ustun alohida bazis funksiya qiymatlari (uzunligi = y.size).
     */
    fun leastSquares(columns: List<DoubleArray>, y: DoubleArray): DoubleArray {
        val p = columns.size
        val n = y.size
        val ata = Array(p) { DoubleArray(p) }
        val aty = DoubleArray(p)
        for (i in 0 until p) {
            val ci = columns[i]
            for (j in i until p) {
                val cj = columns[j]
                var s = 0.0
                for (k in 0 until n) s += ci[k] * cj[k]
                ata[i][j] = s
                ata[j][i] = s
            }
            var s = 0.0
            for (k in 0 until n) s += ci[k] * y[k]
            aty[i] = s
        }
        return solve(ata, aty)
    }

    /** Chiziqli tenglamalar sistemasi (kichik o'lcham), Gauss usuli + qisman pivoting. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val x = b.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-300) continue           // degenerat ustun — 0 qoladi
            if (piv != col) {
                val t = m[piv]; m[piv] = m[col]; m[col] = t
                val tb = x[piv]; x[piv] = x[col]; x[col] = tb
            }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                x[r] -= f * x[col]
            }
        }
        val out = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            if (abs(m[r][r]) < 1e-300) { out[r] = 0.0; continue }
            var s = x[r]
            for (c in r + 1 until n) s -= m[r][c] * out[c]
            out[r] = s / m[r][r]
        }
        return out
    }

    /**
     * Legendre ko'phadlari P_0..P_degree qiymatlari x ∈ [−1; 1] da (ortogonal bazis —
     * ko'phad trendini moslashda normal tenglamalar yaxshi shartlangan bo'ladi).
     */
    fun legendreBasis(n: Int, degree: Int): List<DoubleArray> {
        val x = DoubleArray(n) { if (n == 1) 0.0 else -1.0 + 2.0 * it / (n - 1) }
        val out = ArrayList<DoubleArray>()
        out += DoubleArray(n) { 1.0 }
        if (degree >= 1) out += x.copyOf()
        for (d in 2..degree) {
            val pm1 = out[d - 1]
            val pm2 = out[d - 2]
            // (d)P_d = (2d−1)·x·P_{d−1} − (d−1)·P_{d−2}
            out += DoubleArray(n) { ((2 * d - 1) * x[it] * pm1[it] - (d - 1) * pm2[it]) / d }
        }
        return out
    }
}
