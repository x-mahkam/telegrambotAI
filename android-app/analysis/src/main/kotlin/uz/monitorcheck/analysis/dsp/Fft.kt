package uz.monitorcheck.analysis.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Tez Fure almashtirishi (FFT): radix-2 Cooley–Tukey, joyida (in-place), iterativ.
 *
 * X_k = Σ_{n=0}^{N−1} x_n · e^{−2πi·k·n/N}
 *
 * Manba: J. W. Cooley, J. W. Tukey, "An algorithm for the machine calculation of
 * complex Fourier series", Math. Comp. 19 (1965) 297–301;
 * W. H. Press va b., "Numerical Recipes", 3-nashr, §12.2.
 */
object Fft {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    fun nextPowerOfTwo(n: Int): Int {
        require(n > 0)
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** [re], [im] — bir xil uzunlikdagi (2 ning darajasi) massivlar; natija ularning o'ziga yoziladi. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && isPowerOfTwo(n)) { "FFT uzunligi 2 ning darajasi bo'lishi kerak: $n" }

        // Bit-teskari tartiblash
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // Kapalak (butterfly) bosqichlari
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    /**
     * Haqiqiy signalning amplituda spektri |X_k|, k = 0..M/2.
     * Signal [paddedSize] gacha nollar bilan to'ldiriladi (zero-padding):
     * bu spektrni interpolyatsiya qiladi va cho'qqi joyini aniqroq topishga yordam beradi,
     * lekin haqiqiy chastota ajratish qobiliyatini (1/N) oshirmaydi.
     */
    fun magnitudeSpectrum(x: DoubleArray, paddedSize: Int = nextPowerOfTwo(x.size)): DoubleArray {
        val m = nextPowerOfTwo(maxOf(paddedSize, x.size))
        val re = DoubleArray(m)
        val im = DoubleArray(m)
        x.copyInto(re)
        transform(re, im)
        return DoubleArray(m / 2 + 1) { hypot(re[it], im[it]) }
    }
}
