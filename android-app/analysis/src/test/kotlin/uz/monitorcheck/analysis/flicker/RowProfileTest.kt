package uz.monitorcheck.analysis.flicker

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.test.assertEquals

class RowProfileTest {

    @Test
    fun `profile from Y plane with row stride padding`() {
        val w = 100
        val h = 8
        val stride = 128                          // qator oxirida to'ldiruvchi baytlar
        val buf = ByteBuffer.allocate(stride * h)
        for (r in 0 until h) for (c in 0 until stride) {
            val v = if (c < w) 20 * r + (if (c % 2 == 0) 10 else 0) else 255
            buf.put(r * stride + c, v.toByte())
        }
        val p = RowProfile.fromLuma(buf, w, h, stride, colStep = 1)
        assertEquals(h, p.rows)
        for (r in 0 until h) assertEquals((20.0 * r + 5) / 255, p.values[r], 1e-9)
        assertEquals(0.0, p.saturatedFraction)
    }

    @Test
    fun `saturation is counted`() {
        val buf = ByteBuffer.allocate(64 * 64)
        for (i in 0 until 64 * 64) buf.put(i, (if (i % 4 == 0) 255 else 100).toByte())
        val p = RowProfile.fromLuma(buf, 64, 64, 64, colStep = 1)
        assertEquals(0.25, p.saturatedFraction, 0.01)
    }

    @Test
    fun `srgb linearization`() {
        assertEquals(0.0, RowProfile.srgbToLinear(0.0))
        assertEquals(1.0, RowProfile.srgbToLinear(1.0), 1e-12)
        assertEquals(0.02 / 12.92, RowProfile.srgbToLinear(0.02), 1e-15)
        assertEquals(((0.5 + 0.055) / 1.055).pow(2.4), RowProfile.srgbToLinear(0.5), 1e-15)
        // 50% kod ≈ 21.4% yorqinlik
        assertEquals(0.214, RowProfile.srgbToLinear(0.5), 0.001)
    }

    @Test
    fun `row timing from skew`() {
        val t = RowTiming.fromSkew(skewNs = 15_000_000, rows = 1501)
        assertEquals(10e-6, t.tRowSec!!, 1e-15)
        assertEquals(50_000.0, t.nyquistHz!!, 1e-6)
        assertEquals(1000.0, t.frequencyHz(0.01)!!, 1e-9)
    }
}
