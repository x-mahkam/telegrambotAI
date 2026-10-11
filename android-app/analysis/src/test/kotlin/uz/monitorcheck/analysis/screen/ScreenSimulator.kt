package uz.monitorcheck.analysis.screen

import uz.monitorcheck.analysis.flicker.RowProfile
import uz.monitorcheck.analysis.geometry.ArucoDetector
import uz.monitorcheck.analysis.geometry.Homography
import uz.monitorcheck.analysis.image.Pt
import uz.monitorcheck.analysis.image.RgbImage
import java.util.Random
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.round

/**
 * Test sahifasini (monitor-test.html) ekranda chizish va kamera suratini modellashtirish.
 * Faqat testlar uchun: ma'lum geometriya va nuqsonlar bilan sintetik tasvirlar.
 */
class TestScreen(
    val pattern: TestPattern,
    val w: Int = 1920,
    val h: Int = 1080,
    val markers: Boolean = true,
    /** Piksel nuqsonlari: (x, y) → RGB kod. */
    val defects: Map<Pair<Int, Int>, IntArray> = emptyMap(),
    /** Yorqinlik notekisligi: (u, v) → ko'paytuvchi (chiziqli). */
    val shading: (Double, Double) -> Double = { _, _ -> 1.0 },
    /** Gradient pog'onalari uchun panel bitligi (6 → har 4 daraja birlashadi). */
    val panelBits: Int = 8,
    /** Panelning qora darajasi (chiziqli, oq = 1) — LCD hech qachon to'liq qora emas. */
    val black: Double = 0.0,
) {
    val cell = maxOf(4, round(minOf(w, h) / 135.0).toInt())

    /** Ekran pikselining RGB kodi (0..255). */
    fun code(x: Int, y: Int): IntArray {
        defects[x to y]?.let { return it }
        if (markers) markerCode(x, y)?.let { return intArrayOf(it, it, it) }
        return when (pattern) {
            TestPattern.WHITE, TestPattern.FLICKER -> intArrayOf(255, 255, 255)
            TestPattern.BLACK -> intArrayOf(0, 0, 0)
            TestPattern.RED -> intArrayOf(255, 0, 0)
            TestPattern.GREEN -> intArrayOf(0, 255, 0)
            TestPattern.BLUE -> intArrayOf(0, 0, 255)
            TestPattern.GRAY50 -> intArrayOf(128, 128, 128)
            TestPattern.BLEED -> if (x == 0 || y == 0 || x == w - 1 || y == h - 1) intArrayOf(32, 32, 32) else intArrayOf(0, 0, 0)
            TestPattern.GRADIENT -> {
                val v = quant(floor(x * 256.0 / w).toInt().coerceIn(0, 255))
                when (y * 4 / h) { 0 -> intArrayOf(v, v, v); 1 -> intArrayOf(v, 0, 0); 2 -> intArrayOf(0, v, 0); else -> intArrayOf(0, 0, v) }
            }
            TestPattern.GRADIENT_STEPS -> {
                val k = floor(x * 64.0 / w).toInt().coerceIn(0, 63)
                val v = quant(if (y < h / 2) k else 192 + k)
                intArrayOf(v, v, v)
            }
        }
    }

    private fun quant(v: Int): Int = if (panelBits >= 8) v else (v shr 2) shl 2 or ((v shr 2) shl 2 shr 6)

    private fun markerCode(x: Int, y: Int): Int? {
        val s = 8 * cell
        val corner = when {
            x < s && y < s -> 0
            x >= w - s && y < s -> 1
            x >= w - s && y >= h - s -> 2
            x < s && y >= h - s -> 3
            else -> return null
        }
        val x0 = if (corner == 0 || corner == 3) 0 else w - s
        val y0 = if (corner == 0 || corner == 1) 0 else h - s
        val cx = (x - x0) / cell
        val cy = (y - y0) / cell
        if (cx == 0 || cy == 0 || cx == 7 || cy == 7) return 255
        if (cx == 1 || cy == 1 || cx == 6 || cy == 6) return 0
        val id = (pattern.id - 1) * 4 + corner
        val code = ArucoDetector.DICT_4X4_50[id]
        val bit = (code shr (15 - ((cy - 2) * 4 + (cx - 2)))) and 1
        return if (bit == 1) 255 else 0
    }

    /** Ekran (x, y) nuqtasidagi chiziqli yorqinlik (RGB), soya bilan. */
    fun linear(x: Int, y: Int): DoubleArray {
        val c = code(x, y)
        val s = shading((x + 0.5) / w, (y + 0.5) / h)
        return DoubleArray(3) { (RowProfile.srgbToLinear(c[it] / 255.0) + black) * s }
    }
}

object CameraSim {

    /**
     * Kamera surati: tasvir pikseli (i, j) → [toScreen] orqali ekran nuqtasi; ss×ss
     * subnamuna; Gauss xiraligi (σ, piksel); nisbiy Gauss shovqini; natija sRGB kodlariga
     * (8 bit) kvantlanadi va qayta chiziqlashtiriladi (JPEG/YUV kabi).
     */
    fun capture(
        screen: TestScreen,
        cameraToScreen: Homography,
        width: Int,
        height: Int,
        blurSigma: Double = 0.8,
        noise: Double = 0.01,
        exposure: Double = 0.85,
        bezel: Double = 0.01,
        ss: Int = 3,
        seed: Long = 1,
    ): RgbImage {
        val n = width * height
        val ch = Array(3) { FloatArray(n) }
        for (j in 0 until height) for (i in 0 until width) {
            val acc = DoubleArray(3)
            for (a in 0 until ss) for (b in 0 until ss) {
                val p = cameraToScreen.map(i - 0.5 + (b + 0.5) / ss, j - 0.5 + (a + 0.5) / ss)
                val sx = floor(p.x).toInt()
                val sy = floor(p.y).toInt()
                if (sx in 0 until screen.w && sy in 0 until screen.h) {
                    val l = screen.linear(sx, sy)
                    for (k in 0..2) acc[k] += l[k]
                } else for (k in 0..2) acc[k] += bezel
            }
            for (k in 0..2) ch[k][j * width + i] = (acc[k] / (ss * ss) * exposure).toFloat()
        }
        if (blurSigma > 0) for (k in 0..2) gaussian(ch[k], width, height, blurSigma)
        val rnd = Random(seed)
        val px = IntArray(n)
        val v = IntArray(3)
        for (i in 0 until n) {
            for (k in 0..2) {
                val lin = (ch[k][i] * (1 + noise * rnd.nextGaussian()) + 0.002 * rnd.nextGaussian()).coerceIn(0.0, 1.0)
                v[k] = (255 * (if (lin <= 0.0031308) 12.92 * lin else 1.055 * Math.pow(lin, 1 / 2.4) - 0.055)).toInt().coerceIn(0, 255)
            }
            px[i] = (0xFF shl 24) or (v[0] shl 16) or (v[1] shl 8) or v[2]
        }
        return RgbImage.fromArgb(px, width, height)
    }

    private fun gaussian(d: FloatArray, w: Int, h: Int, s: Double) {
        val r = (3 * s).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * s * s)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(d.size)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0.0
            for (t in -r..r) a += k[t + r] * d[y * w + (x + t).coerceIn(0, w - 1)]
            tmp[y * w + x] = a.toFloat()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0.0
            for (t in -r..r) a += k[t + r] * tmp[(y + t).coerceIn(0, h - 1) * w + x]
            d[y * w + x] = a.toFloat()
        }
    }

    /**
     * Ekran (sw×sh) → kamera tasviri (cw×ch) uchun perspektiv joylashuv: ekran kadrning
     * [fill] qismini egallaydi, burchaklar [jitter] ulushga siljitilgan, [rot90] marta burilgan.
     */
    fun placement(sw: Int, sh: Int, cw: Int, ch: Int, fill: Double = 0.8, jitter: Double = 0.03, rot90: Int = 0, seed: Long = 7): Homography {
        val rnd = Random(seed)
        // 90° ga burilganda tasvirdagi to'rtburchak tomonlari almashadi
        val (aw, ah) = if (rot90 % 2 == 1) sh to sw else sw to sh
        val bw = cw * fill
        val bh = bw * ah / aw
        val scale = if (bh > ch * fill) ch * fill / bh else 1.0
        val ww = bw * scale
        val hh = bh * scale
        val x0 = (cw - ww) / 2
        val y0 = (ch - hh) / 2
        val base = listOf(Pt(x0, y0), Pt(x0 + ww, y0), Pt(x0 + ww, y0 + hh), Pt(x0, y0 + hh))
        val dst = base.map { Pt(it.x + jitter * cw * (rnd.nextDouble() * 2 - 1), it.y + jitter * ch * (rnd.nextDouble() * 2 - 1)) }
        val rotated = List(4) { dst[(it + rot90) % 4] }
        val src = listOf(Pt(0.0, 0.0), Pt(sw.toDouble(), 0.0), Pt(sw.toDouble(), sh.toDouble()), Pt(0.0, sh.toDouble()))
        return Homography.fromPoints(src, rotated)           // ekran → kamera
    }
}
