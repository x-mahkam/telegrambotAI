package uz.monitorcheck.analysis.standards

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ieee1789Test {

    @Test
    fun `limits follow IEEE 1789-2015 recommended practices`() {
        assertEquals(0.025 * 60, Ieee1789.lowRiskLimitPct(60.0), 1e-12)
        assertEquals(0.01 * 60, Ieee1789.noelLimitPct(60.0), 1e-12)
        assertEquals(8.0, Ieee1789.lowRiskLimitPct(100.0), 1e-12)
        assertEquals(3.33, Ieee1789.noelLimitPct(100.0), 1e-12)
        assertEquals(100.0, Ieee1789.lowRiskLimitPct(1250.0), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, Ieee1789.lowRiskLimitPct(1251.0))
        assertEquals(99.9, Ieee1789.noelLimitPct(3000.0), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, Ieee1789.noelLimitPct(3001.0))
    }

    @ParameterizedTest(name = "f={0} Hz, Mod={1}% → {2}")
    @CsvSource(
        "100, 3, NO_OBSERVABLE_EFFECT",
        "100, 5, LOW_RISK",
        "100, 10, HIGH_RISK",
        "240, 100, HIGH_RISK",          // tipik arzon monitor: 240 Hz PWM, 100%
        "1000, 30, NO_OBSERVABLE_EFFECT",
        "1000, 50, LOW_RISK",
        "1000, 85, HIGH_RISK",
        "2000, 100, LOW_RISK",          // f > 1250: past xavf, lekin NOEL emas (100% > 66.6%)
        "2000, 60, NO_OBSERVABLE_EFFECT",
        "5000, 100, NO_OBSERVABLE_EFFECT",
        "60, 1, LOW_RISK",
        "60, 2, HIGH_RISK",
    )
    fun `classification`(f: Double, mod: Double, expected: Ieee1789Level) {
        assertEquals(expected, Ieee1789.classify(f, mod))
    }

    @Test
    fun `assess handles not detected and unknown frequency`() {
        assertEquals(Ieee1789Level.NO_FLICKER_DETECTED, Ieee1789.assess(false, 500.0, 1.0, coverageHz = 4800.0).level)
        // 1/100 s ekspozitsiya: qamrov 0.6034/0.01 = 60 Hz — "miltillash yo'q" deb bo'lmaydi
        assertEquals(Ieee1789Level.INCONCLUSIVE, Ieee1789.assess(false, null, null, coverageHz = 60.3).level)
        assertEquals(Ieee1789Level.INCONCLUSIVE, Ieee1789.assess(false, null, null, coverageHz = null).level)
        assertEquals(Ieee1789Level.FREQUENCY_UNKNOWN, Ieee1789.assess(true, null, 30.0).level)
    }

    @Test
    fun `borderline when confidence interval crosses a boundary`() {
        val a = Ieee1789.assess(true, 1000.0, 32.0, modHighPct = 35.0)   // NOEL chegarasi 33.3%
        assertEquals(Ieee1789Level.NO_OBSERVABLE_EFFECT, a.level)
        assertTrue(a.borderline)
        val b = Ieee1789.assess(true, 1000.0, 10.0, modHighPct = 12.0)
        assertTrue(!b.borderline)
    }
}
