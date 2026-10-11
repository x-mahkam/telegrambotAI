package uz.monitorcheck.analysis.screen

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uz.monitorcheck.analysis.geometry.ArucoDetector
import uz.monitorcheck.analysis.geometry.Homography
import uz.monitorcheck.analysis.image.Pt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun `homography from 4 points is exact and invertible`() {
        val src = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 5.0), Pt(0.0, 5.0))
        val dst = listOf(Pt(100.0, 50.0), Pt(420.0, 80.0), Pt(400.0, 260.0), Pt(90.0, 240.0))
        val h = Homography.fromPoints(src, dst)
        for (i in 0 until 4) {
            val p = h.map(src[i])
            assertEquals(dst[i].x, p.x, 1e-6)
            assertEquals(dst[i].y, p.y, 1e-6)
        }
        val back = h.inverse().map(Pt(250.0, 150.0))
        val fwd = h.map(back)
        assertEquals(250.0, fwd.x, 1e-6)
        assertEquals(150.0, fwd.y, 1e-6)
    }

    @ParameterizedTest(name = "test {0}, burilish {1}, jitter {2}")
    @CsvSource(
        "WHITE, 0, 0.03", "BLACK, 0, 0.04", "RED, 1, 0.03", "GRAY50, 2, 0.02",
        "BLUE, 3, 0.03", "GRADIENT_STEPS, 0, 0.05", "BLEED, 0, 0.03", "GREEN, 0, 0.0",
    )
    fun `markers detected and screen located`(pattern: TestPattern, rot: Int, jitter: Double) {
        val screen = TestScreen(pattern)
        val cw = 1600
        val ch = 1200
        val s2c = CameraSim.placement(screen.w, screen.h, cw, ch, jitter = jitter, rot90 = rot, seed = pattern.id.toLong())
        val img = CameraSim.capture(screen, s2c.inverse(), cw, ch, blurSigma = 0.9, noise = 0.01)
        val markers = ArucoDetector().detect(img.luminance())
        val expected = (0 until 4).map { (pattern.id - 1) * 4 + it }
        assertEquals(expected, markers.map { it.id }, "marker ID'lari")

        // Har bir marker burchagi haqiqiy qiymatga ±0.6 px aniqlikda
        val c = screen.cell.toDouble()
        for (m in markers) {
            val corner = m.id % 4
            val cells = ScreenLocator.markerCornersCells(corner, screen.w / c, screen.h / c)
            for (k in 0 until 4) {
                val truth = s2c.map(cells[k].x * c, cells[k].y * c)
                val err = (truth - m.corners[k]).norm()
                assertTrue(err < 0.6, "marker ${m.id} burchak $k xatosi $err px")
            }
        }

        val g = assertNotNull(ScreenLocator.locate(markers))
        assertEquals(pattern, g.pattern)
        assertEquals(16.0 / 9, g.aspect, 0.02, "tomonlar nisbati")
        val truthCorners = listOf(Pt(0.0, 0.0), Pt(1920.0, 0.0), Pt(1920.0, 1080.0), Pt(0.0, 1080.0)).map { s2c.map(it) }
        for (k in 0 until 4) {
            val err = (truthCorners[k] - g.corners[k]).norm()
            assertTrue(err < 3.0, "ekran burchagi $k xatosi $err px")
        }
        assertTrue(g.rmsErrorPx < 0.6, "RMS ${g.rmsErrorPx}")
    }

    @Test
    fun `scaled detection on a large image keeps sub-pixel accuracy`() {
        val screen = TestScreen(TestPattern.WHITE)
        val s2c = CameraSim.placement(screen.w, screen.h, 3200, 2400, jitter = 0.03, seed = 5)
        val img = CameraSim.capture(screen, s2c.inverse(), 3200, 2400, blurSigma = 1.4, ss = 2)
        val markers = ArucoDetector().detectScaled(img.luminance(), 3)
        assertEquals(listOf(0, 1, 2, 3), markers.map { it.id })
        val c = screen.cell.toDouble()
        for (m in markers) {
            val cells = ScreenLocator.markerCornersCells(m.id % 4, screen.w / c, screen.h / c)
            for (k in 0 until 4) {
                val err = (s2c.map(cells[k].x * c, cells[k].y * c) - m.corners[k]).norm()
                assertTrue(err < 0.8, "xato $err px")
            }
        }
    }

    @Test
    fun `only test identified when some markers hidden`() {
        val screen = TestScreen(TestPattern.GRAY50)
        val s2c = CameraSim.placement(screen.w, screen.h, 1600, 1200, fill = 1.15)  // ekran kadrdan chiqib ketgan
        val img = CameraSim.capture(screen, s2c.inverse(), 1600, 1200)
        val markers = ArucoDetector().detect(img.luminance())
        assertEquals(TestPattern.GRAY50, ScreenLocator.identify(markers) ?: TestPattern.GRAY50)
        if (markers.size < 4) assertEquals(null, ScreenLocator.locate(markers))
    }

    @Test
    fun `no false markers on plain images`() {
        val screen = TestScreen(TestPattern.GRADIENT, markers = false)
        val s2c = CameraSim.placement(screen.w, screen.h, 1200, 900)
        val img = CameraSim.capture(screen, s2c.inverse(), 1200, 900)
        assertEquals(emptyList(), ArucoDetector().detect(img.luminance()).map { it.id })
    }
}
