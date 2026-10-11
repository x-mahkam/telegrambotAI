package uz.monitorcheck.analysis.geometry

import uz.monitorcheck.analysis.dsp.Signal
import uz.monitorcheck.analysis.image.Pt
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Proyektiv almashtirish (gomografiya) — tekislikdan tekislikka:
 *
 *   [x'; y'; w'] = H · [x; y; 1],   (x, y) → (x'/w', y'/w')
 *
 * Kamera ekran tekisligini aynan shunday almashtirish bilan tasvirlaydi (pinhole model,
 * linza buzilishi hisobga olinmaganda). Manba: R. Hartley, A. Zisserman,
 * "Multiple View Geometry in Computer Vision", 2-nashr, 2004, 4-bob (DLT algoritmi).
 */
class Homography(val h: DoubleArray) {
    init {
        require(h.size == 9)
    }

    fun map(x: Double, y: Double): Pt {
        val w = h[6] * x + h[7] * y + h[8]
        return Pt((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    fun map(p: Pt) = map(p.x, p.y)

    fun inverse(): Homography {
        val a = h
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        require(kotlin.math.abs(det) > 1e-300) { "Gomografiya teskarilanmaydi" }
        val inv = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    /** this ∘ other: avval other, keyin this. */
    fun compose(other: Homography): Homography {
        val a = h
        val b = other.h
        return Homography(DoubleArray(9) { k ->
            val i = k / 3
            val j = k % 3
            a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        })
    }

    companion object {
        /**
         * Normallashtirilgan DLT: src[i] → dst[i] (kamida 4 juft). h₃₃ = 1 deb,
         * 2N tenglama eng kichik kvadratlar bilan yechiladi. Hartley normallashtirishi
         * (markazga siljitish, o'rtacha masofa √2) sonli shartlanishni yaxshilaydi.
         */
        fun fromPoints(src: List<Pt>, dst: List<Pt>): Homography {
            require(src.size == dst.size && src.size >= 4)
            val ts = normalizer(src)
            val td = normalizer(dst)
            val s = src.map { ts.map(it) }
            val d = dst.map { td.map(it) }
            val n = s.size
            val cols = List(8) { DoubleArray(2 * n) }
            val rhs = DoubleArray(2 * n)
            for (i in 0 until n) {
                val (x, y) = s[i]
                val (u, v) = d[i]
                val r1 = 2 * i
                val r2 = r1 + 1
                cols[0][r1] = x; cols[1][r1] = y; cols[2][r1] = 1.0
                cols[6][r1] = -u * x; cols[7][r1] = -u * y; rhs[r1] = u
                cols[3][r2] = x; cols[4][r2] = y; cols[5][r2] = 1.0
                cols[6][r2] = -v * x; cols[7][r2] = -v * y; rhs[r2] = v
            }
            val c = Signal.leastSquares(cols, rhs)
            val hn = Homography(doubleArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], 1.0))
            return td.inverse().compose(hn).compose(ts)
        }

        private fun normalizer(p: List<Pt>): Homography {
            val cx = p.sumOf { it.x } / p.size
            val cy = p.sumOf { it.y } / p.size
            val md = p.sumOf { hypot(it.x - cx, it.y - cy) } / p.size
            val s = if (md > 1e-12) sqrt(2.0) / md else 1.0
            return Homography(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
        }

        /** Birlik kvadrat (0,0),(1,0),(1,1),(0,1) → berilgan 4 burchak. */
        fun unitSquareTo(q: List<Pt>): Homography =
            fromPoints(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)), q)
    }
}
