package uz.monitorcheck.analysis.screen

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import uz.monitorcheck.analysis.geometry.ArucoDetector
import uz.monitorcheck.analysis.image.Pt
import uz.monitorcheck.analysis.image.RgbImage
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mustaqil tekshiruv: test-page/monitor-test.html ning HAQIQIY brauzer (Chromium) skrinshotlari
 * OpenCV bilan perspektiv o'zgartirilib, xiralashtirilib, shovqin qo'shilib, JPEG qilingan
 * (test-page/tools/out → src/test/resources/realpage). Kotlin detektori HTML'dagi markerlarni
 * to'g'ri o'qishi va ekran burchaklarini topishi kerak.
 */
class RealPageTest {

    private fun load(name: String): RgbImage {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/realpage/$name.jpg"))
        val px = IntArray(bi.width * bi.height)
        bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return RgbImage.fromArgb(px, bi.width, bi.height)
    }

    private fun truth(name: String): List<Pt> {
        val txt = javaClass.getResourceAsStream("/realpage/truth.json")!!.bufferedReader().readText()
        val block = txt.substringAfter("\"$name\"").substringAfter("\"corners\"").substringBefore("\"w\"")
        val nums = Regex("-?\\d+\\.?\\d*(e-?\\d+)?").findAll(block).map { it.value.toDouble() }.toList()
        return (0 until 4).map { Pt(nums[2 * it], nums[2 * it + 1]) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["01_WHITE", "02_BLACK", "03_RED", "06_GRAY50", "09_GRADIENT_STEPS"])
    fun `browser screenshot markers and corners`(name: String) {
        val img = load(name)
        val markers = ArucoDetector().detect(img.luminance())
        val testId = name.substringBefore('_').toInt()
        assertEquals((0 until 4).map { (testId - 1) * 4 + it }, markers.map { it.id })
        val g = assertNotNull(ScreenLocator.locate(markers))
        assertEquals(testId, g.pattern.id)
        assertEquals(16.0 / 9, g.aspect, 0.02)
        val t = truth(name)
        for (k in 0 until 4) {
            val e = (g.corners[k] - t[k]).norm()
            assertTrue(e < 3.0, "$name burchak $k: xato $e px")
        }
    }
}
