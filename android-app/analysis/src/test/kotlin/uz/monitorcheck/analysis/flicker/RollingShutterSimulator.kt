package uz.monitorcheck.analysis.flicker

import java.util.Random
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Rolling shutter kamerasining fizik modeli (testlar uchun).
 *
 *   P_r = V(r) · (1/T_e) ∫_{t_r}^{t_r + T_e} L(t) dt + shovqin,   t_r = t_0 + r·t_row
 *
 * V(r) — vinyetka (markazdan chetga kamayish), shovqin — Gauss, nisbiy σ.
 */
object RollingShutterSimulator {

    /** Ideal PWM: yuqori daraja 1, past daraja (1 − M)/(1 + M), shunda (max−min)/(max+min) = M. */
    fun pwm(freqHz: Double, duty: Double, modPct: Double): (Double) -> Double {
        val m = modPct / 100.0
        val low = (1 - m) / (1 + m)
        return { t ->
            val ph = t * freqHz - floor(t * freqHz)
            if (ph < duty) 1.0 else low
        }
    }

    /** Sinusoidal: L = 1 + m·sin(2πft), Mod% = 100·m. */
    fun sine(freqHz: Double, modPct: Double): (Double) -> Double {
        val m = modPct / 100.0
        return { t -> 1.0 + m * sin(2 * PI * freqHz * t) }
    }

    fun constant(): (Double) -> Double = { 1.0 }

    fun profile(
        light: (Double) -> Double,
        rows: Int,
        tRowSec: Double,
        exposureSec: Double,
        t0Sec: Double = 0.0,
        vignette: Double = 0.15,
        noise: Double = 0.002,
        rng: Random = Random(1),
        sub: Int = 256,
        spatialPattern: ((Int) -> Double)? = null,
    ): DoubleArray = DoubleArray(rows) { r ->
        val ts = t0Sec + r * tRowSec
        var s = 0.0
        for (k in 0 until sub) s += light(ts + (k + 0.5) / sub * exposureSec)
        val x = (r - rows / 2.0) / (rows / 2.0)
        val v = 1.0 - vignette * x * x
        val sp = spatialPattern?.invoke(r) ?: 1.0
        0.6 * v * sp * (s / sub) * (1 + noise * rng.nextGaussian())
    }
}
