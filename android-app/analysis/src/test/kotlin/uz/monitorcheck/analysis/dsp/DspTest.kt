package uz.monitorcheck.analysis.dsp

import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspTest {

    @Test
    fun `FFT matches direct DFT`() {
        val n = 256
        val rnd = Random(3)
        val x = DoubleArray(n) { rnd.nextGaussian() }
        val y = DoubleArray(n) { rnd.nextGaussian() }
        val re = x.copyOf()
        val im = y.copyOf()
        Fft.transform(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2 * PI * k * t / n
                sr += x[t] * cos(a) - y[t] * sin(a)
                si += x[t] * sin(a) + y[t] * cos(a)
            }
            assertEquals(sr, re[k], 1e-9)
            assertEquals(si, im[k], 1e-9)
        }
    }

    @Test
    fun `inverse FFT restores signal`() {
        val rnd = Random(5)
        val x = DoubleArray(1024) { rnd.nextDouble() }
        val re = x.copyOf()
        val im = DoubleArray(1024)
        Fft.transform(re, im)
        Fft.transform(re, im, inverse = true)
        for (i in x.indices) assertEquals(x[i], re[i], 1e-12)
    }

    @Test
    fun `parseval energy`() {
        val rnd = Random(9)
        val x = DoubleArray(512) { rnd.nextGaussian() }
        val re = x.copyOf()
        val im = DoubleArray(512)
        Fft.transform(re, im)
        val et = x.sumOf { it * it }
        val ef = re.indices.sumOf { re[it] * re[it] + im[it] * im[it] } / 512
        assertEquals(et, ef, 1e-9)
    }

    @Test
    fun `spectrum peak with zero padding and parabolic interpolation`() {
        val n = 1000
        val nu = 0.0123456
        val x = DoubleArray(n) { cos(2 * PI * nu * it) }
        val w = Signal.hann(n)
        val m = 8192
        val mag = Fft.magnitudeSpectrum(DoubleArray(n) { x[it] * w[it] }, m)
        var k = 1
        for (i in 1 until mag.size - 1) if (mag[i] > mag[k]) k = i
        val d = Signal.parabolicPeakOffset(kotlin.math.ln(mag[k - 1]), kotlin.math.ln(mag[k]), kotlin.math.ln(mag[k + 1]))
        assertEquals(nu, (k + d) / m, 2e-5)
    }

    @Test
    fun `least squares recovers polynomial and sinusoid`() {
        val n = 400
        val basis = Signal.legendreBasis(n, 3).toMutableList()
        basis += DoubleArray(n) { cos(2 * PI * 0.05 * it) }
        basis += DoubleArray(n) { sin(2 * PI * 0.05 * it) }
        val truth = doubleArrayOf(1.0, -0.2, 0.05, 0.01, 0.3, -0.1)
        val y = DoubleArray(n) { i -> basis.indices.sumOf { truth[it] * basis[it][i] } }
        val c = Signal.leastSquares(basis, y)
        for (i in truth.indices) assertEquals(truth[i], c[i], 1e-9)
    }

    @Test
    fun `sinc values`() {
        assertEquals(1.0, Signal.sinc(0.0))
        assertEquals(0.0, Signal.sinc(1.0), 1e-15)
        assertEquals(2 / PI, Signal.sinc(0.5), 1e-12)
    }

    @Test
    fun `statistics and student t`() {
        val e = Stats.estimate(doubleArrayOf(9.8, 10.1, 10.0, 10.3, 9.9))
        assertEquals(10.02, e.mean, 1e-9)
        assertEquals(0.19235, e.std, 1e-4)
        assertEquals(2.776 * 0.19235 / kotlin.math.sqrt(5.0), e.ci95, 1e-4)
        assertEquals(12.706, Stats.tQuantile975(1))
        assertEquals(1.96, Stats.tQuantile975(1000))
        assertEquals(2.5, Stats.median(doubleArrayOf(4.0, 1.0, 2.0, 3.0)))
    }

    @Test
    fun `circular resultant length`() {
        assertEquals(1.0, Stats.circularResultantLength(DoubleArray(10) { 0.7 }), 1e-12)
        val uniform = DoubleArray(360) { 2 * PI * it / 360 }
        assertTrue(Stats.circularResultantLength(uniform) < 1e-9)
        assertEquals(1.0, hypot(1.0, 0.0))
    }
}
