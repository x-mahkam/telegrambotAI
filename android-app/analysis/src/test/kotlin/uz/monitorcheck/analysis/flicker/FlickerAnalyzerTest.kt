package uz.monitorcheck.analysis.flicker

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uz.monitorcheck.analysis.dsp.Signal
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlickerAnalyzerTest {

    private val analyzer = FlickerAnalyzer()
    private val sim = RollingShutterSimulator

    // Tipik telefon: 1500 qator, skew 15 ms → t_row = 10 µs (Naykvist 50 kHz)
    private val rows = 1500
    private val tRow = 10e-6
    private val timing = RowTiming.calibrated(tRow)

    private fun frame(light: (Double) -> Double, exposure: Double, t0: Double = 0.0, seed: Long = 1, noise: Double = 0.002) =
        analyzer.analyzeFrame(sim.profile(light, rows, tRow, exposure, t0, noise = noise, rng = Random(seed)), timing, exposure)

    @ParameterizedTest(name = "sinus f={0} Hz, Mod={1}%")
    @CsvSource("240, 30", "500, 10", "1000, 50", "2500, 20", "6000, 40")
    fun `sinusoidal flicker frequency and modulation`(f: Double, mod: Double) {
        val te = 1.0 / 20000
        val r = frame(sim.sine(f, mod), te)
        assertTrue(r.detected, "aniqlanishi kerak")
        assertEquals(f, r.frequencyHz!!, f * 0.005, "chastota ±0.5%")
        assertEquals(mod, r.modulationPct, 1.5, "modulyatsiya ±1.5 p.p.")
        assertEquals(WaveformModel.HARMONIC, r.model, "sinusoid uchun garmonik model tanlanishi kerak")
    }

    @Test
    fun `blind spot when exposure equals flicker period`() {
        // f·T_e = 1 → sinc(1) = 0: kamera miltillashni umuman ko'rmaydi (usulning fizik cheklovi).
        // Shuning uchun ilova ikkinchi, boshqa ekspozitsiyada qayta o'lchaydi.
        val r1 = frame(sim.sine(8000.0, 40.0), 1.0 / 8000)
        assertFalse(r1.detected)
        val r2 = frame(sim.sine(8000.0, 40.0), 1.0 / 13000)
        assertTrue(r2.detected)
        assertEquals(40.0, r2.modulationPct, 2.0)
    }

    @ParameterizedTest(name = "PWM f={0} Hz, D={1}, Mod={2}%")
    @CsvSource(
        "500, 0.5, 100, 8000", "1200, 0.3, 100, 8000", "2400, 0.25, 60, 8000", "250, 0.8, 40, 8000",
        "240, 0.1, 100, 8000", "4000, 0.5, 50, 8000", "1500, 0.2, 100, 2000", "18000, 0.5, 100, 20000",
    )
    fun `PWM square wave`(f: Double, duty: Double, mod: Double, invExposure: Double) {
        val te = 1.0 / invExposure
        val r = frame(sim.pwm(f, duty, mod), te)
        assertTrue(r.detected)
        assertEquals(f, r.frequencyHz!!, f * 0.005)
        assertEquals(WaveformModel.RECTANGULAR_PWM, r.model)
        assertEquals(mod, r.modulationPct, 3.0, "Mod% ±3 p.p.")
        assertEquals(duty, r.dutyCycle!!, 0.03, "to'ldirish koeffitsienti ±0.03")
    }

    @Test
    fun `sinc exposure correction restores modulation`() {
        // f = 1000 Hz, T_e = 500 µs → sinc(0.5) = 2/π = 0.6366: kamera 20% o'rniga ~12.7% ko'radi
        val te = 500e-6
        val r = frame(sim.sine(1000.0, 20.0), te)
        assertTrue(r.detected)
        assertEquals(2 / PI, r.sincFundamental!!, 0.01)
        assertEquals(20.0 * 2 / PI, r.rawModulationPct, 1.0)
        assertEquals(20.0, r.modulationPct, 1.2)
    }

    @Test
    fun `steady light is not detected`() {
        repeat(5) { s ->
            val r = frame(sim.constant(), 1.0 / 8000, seed = s.toLong())
            assertFalse(r.detected, "doimiy yorug'likda miltillash topilmasligi kerak (seed=$s)")
            assertTrue(r.detectionLimitPct < 2.0)
        }
    }

    @Test
    fun `weak flicker below threshold is not reported`() {
        val r = frame(sim.sine(800.0, 0.2), 1.0 / 8000)
        assertFalse(r.detected)
    }

    @Test
    fun `low frequency with few cycles per frame`() {
        // 100 Hz, kadr 1500·20µs = 30 ms → 3 sikl
        val p = sim.profile(sim.sine(100.0, 25.0), rows, 20e-6, 1.0 / 4000)
        val r = analyzer.analyzeFrame(p, RowTiming.calibrated(20e-6), 1.0 / 4000)
        assertTrue(r.detected)
        assertEquals(100.0, r.frequencyHz!!, 1.5)
        assertEquals(25.0, r.modulationPct, 2.5)
    }

    @Test
    fun `without timing only qualitative result`() {
        val p = sim.profile(sim.sine(600.0, 30.0), rows, tRow, 1.0 / 8000)
        val r = analyzer.analyzeFrame(p, RowTiming.UNKNOWN, 1.0 / 8000)
        assertTrue(r.detected)
        assertEquals(null, r.frequencyHz)
        assertEquals(600.0 * tRow, r.cyclesPerRow, 600.0 * tRow * 0.005)
    }

    @Test
    fun `combine frames gives estimate with confidence interval`() {
        val te = 1.0 / 8000
        val light = sim.pwm(720.0, 0.4, 80.0)
        val rnd = Random(42)
        // Kadrlar ixtiyoriy vaqtda boshlanadi (kamera PWM'ga sinxron emas)
        val frames = (0 until 20).map { i -> frame(light, te, t0 = rnd.nextDouble() * 0.01, seed = i.toLong()) }
        val m = analyzer.combine(frames, timing, te, ResponseModel.LINEAR)
        assertTrue(m.detected)
        val f = assertNotNull(m.frequencyHz)
        assertTrue(f.low <= 720.0 && 720.0 <= f.high, "CI chastotani o'z ichiga olishi kerak: $f")
        assertEquals(80.0, m.modulationPct!!.mean, 6.0)
        assertTrue(FlickerWarning.STATIC_PHASE !in m.warnings)
        assertTrue(m.phaseCoherence!! < 0.8)
    }

    @Test
    fun `static spatial pattern is flagged as moire`() {
        // Doimiy yorug'lik + ekran piksel to'ri fazoviy naqshi (har kadrda bir xil faza)
        val te = 1.0 / 8000
        val pattern = { r: Int -> 1.0 + 0.05 * cos(2 * PI * 0.031 * r) }
        val frames = (0 until 12).map { i ->
            val p = sim.profile(sim.constant(), rows, tRow, te, rng = Random(i.toLong()), spatialPattern = pattern)
            analyzer.analyzeFrame(p, timing, te)
        }
        val m = analyzer.combine(frames, timing, te, ResponseModel.LINEAR)
        assertTrue(m.detected)
        assertTrue(FlickerWarning.STATIC_PHASE in m.warnings, "statik faza ogohlantirishi kerak")
    }

    @Test
    fun `combine without detection reports upper limit`() {
        val frames = (0 until 10).map { frame(sim.constant(), 1.0 / 8000, seed = it.toLong()) }
        val m = analyzer.combine(frames, timing, 1.0 / 8000, ResponseModel.LINEAR)
        assertFalse(m.detected)
        assertTrue(m.detectionLimitPct in 0.4..2.0)
    }

    @Test
    fun `warnings for missing timing and nonlinear response`() {
        val frames = (0 until 4).map { frame(sim.sine(500.0, 30.0), 1.0 / 8000, t0 = it * 1.3e-3) }
        val m = analyzer.combine(frames, RowTiming.UNKNOWN, null, ResponseModel.SRGB_APPROX)
        assertTrue(FlickerWarning.NO_TIMING in m.warnings)
        assertTrue(FlickerWarning.NO_EXPOSURE in m.warnings)
        assertTrue(FlickerWarning.NONLINEAR_RESPONSE in m.warnings)
        assertTrue(FlickerWarning.FEW_FRAMES in m.warnings)
    }

    @Test
    fun `long exposure flagged LOW_SINC`() {
        val te = 1.0 / 1000                                      // 1 ms, f = 700 Hz → sinc(0.7) = 0.37
        val frames = (0 until 10).map { frame(sim.sine(700.0, 40.0), te, t0 = it * 0.77e-3, seed = it.toLong()) }
        val m = analyzer.combine(frames, timing, te, ResponseModel.LINEAR)
        assertTrue(FlickerWarning.LOW_SINC in m.warnings)
        assertEquals(abs(Signal.sinc(0.7)), 0.368, 0.01)
    }

    @Test
    fun `calibration from 100 Hz lamp recovers t_row`() {
        val trueTRow = 18.5e-6
        val te = 1.0 / 2000
        // Tarmoq lampasi: L ∝ sin²(2π·50·t) + doimiy qism → 100 Hz
        val lamp = { t: Double -> 0.7 + 0.3 * cos(2 * PI * 100.0 * t) }
        val frames = (0 until 10).map { i ->
            val p = sim.profile(lamp, 1200, trueTRow, te, t0Sec = i * 3.3e-3, rng = Random(i.toLong()))
            analyzer.analyzeFrame(p, RowTiming.UNKNOWN, te)
        }
        val cal = assertNotNull(Calibration.fromFrames(frames, 100.0, skewTRowSec = 18.0e-6))
        assertEquals(trueTRow, cal.tRow.mean, trueTRow * 0.01)
        assertEquals(100.0 * (cal.tRow.mean - 18.0e-6) / 18.0e-6, cal.deviationFromSkewPct!!, 1e-9)
    }
}
