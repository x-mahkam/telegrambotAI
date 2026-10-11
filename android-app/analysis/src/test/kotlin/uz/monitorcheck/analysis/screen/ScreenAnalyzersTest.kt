package uz.monitorcheck.analysis.screen

import org.junit.jupiter.api.Test
import uz.monitorcheck.analysis.geometry.ArucoDetector
import uz.monitorcheck.analysis.image.RgbImage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenAnalyzersTest {

    private fun shoot(screen: TestScreen, cw: Int = 1600, ch: Int = 1200, fill: Double = 0.85, exposure: Double = 0.85,
                      blur: Double = 0.8, noise: Double = 0.01, seed: Long = 3): Pair<RgbImage, ScreenGeometry> {
        val s2c = CameraSim.placement(screen.w, screen.h, cw, ch, fill = fill, jitter = 0.02, seed = seed)
        val img = CameraSim.capture(screen, s2c.inverse(), cw, ch, blurSigma = blur, noise = noise, exposure = exposure, seed = seed)
        val g = assertNotNull(ScreenLocator.locate(ArucoDetector().detect(img.luminance())), "ekran topilmadi")
        return img to g
    }

    /** Geometriyasiz surat (ekspozitsiya bo'yicha ikkinchi kadr). */
    private fun shootRaw(screen: TestScreen, exposure: Double, seed: Long = 3): Pair<RgbImage, Unit> {
        val s2c = CameraSim.placement(screen.w, screen.h, 1600, 1200, fill = 0.85, jitter = 0.02, seed = seed)
        return CameraSim.capture(screen, s2c.inverse(), 1600, 1200, blurSigma = 0.8, noise = 0.01, exposure = exposure, seed = seed + 1) to Unit
    }

    @Test
    fun `uniformity of a perfect screen is near 100 percent`() {
        val (img, g) = shoot(TestScreen(TestPattern.GRAY50))
        val u = UniformityAnalyzer.analyze(img.luminance(), g)
        assertTrue(u.uniformityPct > 97, "U = ${u.uniformityPct}")
        assertEquals(QualityLevel.GOOD, u.level)
    }

    @Test
    fun `uniformity with vignetting matches ground truth`() {
        // Chetlarga qarab yorqinlik pasayadi: L = 1 − k·r²/2, r — markazdan masofa (burchakda r² = 2)
        for ((k, level) in listOf(0.3 to QualityLevel.GOOD, 0.6 to QualityLevel.FAIR, 0.8 to QualityLevel.POOR)) {
            val shade = { u: Double, v: Double -> val x = 2 * u - 1; val y = 2 * v - 1; 1 - k * (x * x + y * y) / 2 }
            val (img, g) = shoot(TestScreen(TestPattern.GRAY50, shading = shade))
            val u = UniformityAnalyzer.analyze(img.luminance(), g)
            // Haqiqiy: burchak zonasi markazi (1/6, 1/6) → x = y = −2/3 → 1 − k·(8/9)/2
            val truth = 100 * (1 - k * 4.0 / 9)
            assertEquals(truth, u.uniformityPct, 1.5)
            assertEquals(truth, u.relativeToCenterPct[0], 1.5)
            assertEquals(level, u.level)
        }
    }

    @Test
    fun `dead pixels and dust on white`() {
        val dead = listOf(300 to 200, 640 to 360, 1000 to 600)
        val defects = HashMap<Pair<Int, Int>, IntArray>()
        dead.forEach { defects[it] = intArrayOf(0, 0, 0) }
        // Chang: 6×6 piksel, 40% qorong'iroq
        for (x in 900 until 906) for (y in 150 until 156) defects[x to y] = intArrayOf(170, 170, 170)
        val screen = TestScreen(TestPattern.WHITE, w = 1280, h = 720, defects = defects)
        val (img, g) = shoot(screen, cw = 2400, ch = 1800)
        val r = PixelDefectAnalyzer.analyze(img, g, screenWidthPx = 1280)
        assertTrue(!r.moireSuspected && !r.lowResolution)
        val px = r.defects.filter { it.kind == DefectKind.DARK_PIXEL }
        assertEquals(3, px.size, "o'lik piksellar: ${r.defects}")
        for ((x, y) in dead) {
            assertTrue(px.any { abs(it.u - (x + 0.5) / 1280) < 0.003 && abs(it.v - (y + 0.5) / 720) < 0.004 }, "($x,$y) topilmadi")
        }
        assertEquals(1, r.defects.count { it.kind == DefectKind.SPOT })
    }

    @Test
    fun `bright pixel on black and stuck subpixel on red`() {
        val b = TestScreen(TestPattern.BLACK, w = 1280, h = 720, defects = mapOf((500 to 400) to intArrayOf(255, 255, 255)), black = 0.002)
        val (imgB, gB) = shoot(b, cw = 2400, ch = 1800, exposure = 20.0)
        val rb = PixelDefectAnalyzer.analyze(imgB, gB, 1280)
        assertEquals(listOf(DefectKind.BRIGHT_PIXEL), rb.defects.map { it.kind })

        val red = TestScreen(TestPattern.RED, w = 1280, h = 720,
            defects = mapOf((700 to 300) to intArrayOf(255, 255, 0), (200 to 500) to intArrayOf(0, 0, 0)))
        val (imgR, gR) = shoot(red, cw = 2400, ch = 1800)
        val rr = PixelDefectAnalyzer.analyze(imgR, gR, 1280)
        assertEquals(2, rr.defects.size, "${rr.defects}")
        assertTrue(rr.defects.all { it.kind == DefectKind.SUBPIXEL })
    }

    @Test
    fun `clean screens have no defects`() {
        for (p in listOf(TestPattern.WHITE, TestPattern.GREEN, TestPattern.BLUE, TestPattern.GRAY50)) {
            val (img, g) = shoot(TestScreen(p, w = 1280, h = 720), cw = 2400, ch = 1800, seed = p.id.toLong())
            val r = PixelDefectAnalyzer.analyze(img, g, 1280)
            assertEquals(emptyList(), r.defects, "$p: soxta nuqsonlar")
        }
    }

    @Test
    fun `8-bit and 6-bit gradients are distinguished`() {
        val (i8, g8) = shoot(TestScreen(TestPattern.GRADIENT_STEPS), cw = 2000, ch = 1500, exposure = 0.8, noise = 0.005)
        val r8 = BandingAnalyzer.analyze(i8.luminance(), g8)
        assertTrue(r8.bright.visibleSteps >= 60, "8-bit: ${r8.bright.visibleSteps}")
        assertEquals(BitDepthVerdict.EIGHT_BIT_OR_FRC, r8.verdict)

        val (i6, g6) = shoot(TestScreen(TestPattern.GRADIENT_STEPS, panelBits = 6), cw = 2000, ch = 1500, exposure = 0.8, noise = 0.005)
        val r6 = BandingAnalyzer.analyze(i6.luminance(), g6)
        assertEquals(16, r6.bright.visibleSteps, "6-bit")
        assertEquals(BitDepthVerdict.SIX_BIT, r6.verdict)
    }

    @Test
    fun `backlight bleed at the bottom edge`() {
        // Pastki chetdan 3% masofada qora daraja 5 baravar oshadi
        // Ilovadagi kabi ikki surat: qisqa ekspozitsiya — geometriya (markerlar), uzun — tahlil
        val shade = { _: Double, v: Double -> 1 + 4 * exp(-(1 - v) / 0.04) }
        val scr = TestScreen(TestPattern.BLEED, black = 0.002, shading = shade)
        val (_, g) = shoot(scr, exposure = 0.85)
        val (img, _) = shootRaw(scr, exposure = 40.0)
        val r = BleedAnalyzer.analyze(img.luminance(), g)
        assertTrue(r.maxEdgeRatio > 2.0, "nisbat ${r.maxEdgeRatio}")
        assertTrue(r.hotspot.y > 0.85, "joy ${r.hotspot}")
        assertTrue(r.level != QualityLevel.GOOD)

        val scr0 = TestScreen(TestPattern.BLEED, black = 0.002)
        val (_, g0) = shoot(scr0, exposure = 0.85)
        val (img0, _) = shootRaw(scr0, exposure = 40.0)
        val r0 = BleedAnalyzer.analyze(img0.luminance(), g0)
        assertTrue(r0.maxEdgeRatio < 1.3, "toza ekran: ${r0.maxEdgeRatio}")
        assertEquals(QualityLevel.GOOD, r0.level)
    }

    @Test
    fun `ISO 9241-307 pixel fault classes`() {
        val mp = 1920.0 * 1080 / 1e6
        assertEquals(0, PixelFaultClass.classify(0, 0, 0, mp))
        assertEquals(1, PixelFaultClass.classify(1, 2, 3, mp))
        assertEquals(2, PixelFaultClass.classify(3, 1, 0, mp))
        assertEquals(3, PixelFaultClass.classify(8, 0, 0, mp))
        assertEquals(4, PixelFaultClass.classify(20, 0, 0, mp))
    }
}
