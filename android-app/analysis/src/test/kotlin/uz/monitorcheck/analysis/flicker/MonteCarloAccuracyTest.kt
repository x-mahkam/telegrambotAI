package uz.monitorcheck.analysis.flicker

import org.junit.jupiter.api.Test
import uz.monitorcheck.analysis.dsp.Signal
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.assertTrue

/**
 * Monte-Karlo: tasodifiy PWM parametrlarida bitta kadr o'lchovining aniqligi.
 * Natija jadvali testlar chiqishida chop etiladi (usul aniqligini hujjatlash uchun).
 *
 * Diapazon: f = 150 Hz…20 kHz (log-tekis), D = 0.1…0.9, Mod = 10…100%,
 * T_e = 1/20000…1/4000 s, t_row = 5…20 µs, qatorlar 1000…2000, shovqin 0.1…0.5%.
 * Ko'r nuqtalar (|sinc(f·T_e)| < 0.5) va kadrda 2 sikldan kam holatlar chiqarib tashlanadi —
 * ilova ularni ikkinchi ekspozitsiya bilan qayta o'lchaydi.
 */
class MonteCarloAccuracyTest {

    @Test
    fun `random PWM sweep`() {
        val rnd = Random(2026)
        val analyzer = FlickerAnalyzer()
        val fErr = ArrayList<Double>()
        val mErr = ArrayList<Double>()
        var detected = 0
        var total = 0
        var timeNs = 0L
        while (total < 200) {
            val f = exp(ln(150.0) + rnd.nextDouble() * (ln(20000.0) - ln(150.0)))
            val d = 0.1 + 0.8 * rnd.nextDouble()
            val mod = 10 + 90 * rnd.nextDouble()
            val te = 1.0 / (4000 + 16000 * rnd.nextDouble())
            val tRow = (5 + 15 * rnd.nextDouble()) * 1e-6
            val rows = 1000 + rnd.nextInt(1000)
            val noise = 0.001 + 0.004 * rnd.nextDouble()
            if (abs(Signal.sinc(f * te)) < 0.5 || f * tRow * rows < 2.0) continue
            total++
            val p = RollingShutterSimulator.profile(
                RollingShutterSimulator.pwm(f, d, mod), rows, tRow, te,
                t0Sec = rnd.nextDouble() * 0.01, noise = noise, rng = Random(rnd.nextLong()), sub = 128,
            )
            val t0 = System.nanoTime()
            val r = analyzer.analyzeFrame(p, RowTiming.calibrated(tRow), te)
            timeNs += System.nanoTime() - t0
            if (!r.detected) continue
            detected++
            fErr += abs(r.frequencyHz!! - f) / f * 100
            mErr += abs(r.modulationPct - mod)
        }
        fun pct(x: List<Double>, q: Double) = x.sorted()[((x.size - 1) * q).toInt()]
        println("Monte-Karlo ($total holat): aniqlandi $detected")
        println("  chastota xatosi, %%:   median %.3f, 95%%: %.3f, maks %.3f".format(pct(fErr, 0.5), pct(fErr, 0.95), fErr.max()))
        println("  modulyatsiya xatosi, p.p.: median %.2f, 95%%: %.2f, maks %.2f".format(pct(mErr, 0.5), pct(mErr, 0.95), mErr.max()))
        println("  bitta kadr tahlili: %.1f ms (JVM)".format(timeNs / 1e6 / total))
        assertTrue(detected >= 0.97 * total, "aniqlash darajasi")
        assertTrue(pct(fErr, 0.95) < 0.5, "chastota: 95% holatda < 0.5%")
        assertTrue(pct(mErr, 0.95) < 5.0, "modulyatsiya: 95% holatda < 5 p.p.")
    }
}
