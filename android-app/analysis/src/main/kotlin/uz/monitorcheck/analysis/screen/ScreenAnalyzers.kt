package uz.monitorcheck.analysis.screen

import uz.monitorcheck.analysis.dsp.Stats
import uz.monitorcheck.analysis.image.GrayImage
import uz.monitorcheck.analysis.image.Pt
import uz.monitorcheck.analysis.image.RgbImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ekran sohasidan (u, v ∈ [u0;u1]×[v0;v1]) to'r bo'yicha namunalar. */
internal fun sampleRegion(img: GrayImage, g: ScreenGeometry, u0: Double, u1: Double, v0: Double, v1: Double, nu: Int, nv: Int): DoubleArray {
    val out = DoubleArray(nu * nv)
    var k = 0
    for (j in 0 until nv) for (i in 0 until nu) {
        val p = g.imagePoint(u0 + (u1 - u0) * (i + 0.5) / nu, v0 + (v1 - v0) * (j + 0.5) / nv)
        out[k++] = img.sample(p.x, p.y)
    }
    return out
}

/** Kesilgan o'rtacha (chetki [trim] ulushlar tashlanadi) — chang/nuqsonlar ta'sirini kamaytiradi. */
internal fun trimmedMean(x: DoubleArray, trim: Double = 0.1): Double {
    val s = x.sortedArray()
    val a = (s.size * trim).toInt()
    val b = max(a + 1, s.size - a)
    var sum = 0.0
    for (i in a until b) sum += s[i]
    return sum / (b - a)
}

// =====================================================================================
// 1. Yorqinlik bir tekisligi (3×3 zona)
// =====================================================================================

enum class QualityLevel { GOOD, FAIR, POOR }

data class UniformityResult(
    /** 3×3 zona yorqinligi (nisbiy birlik), qator-ustun tartibida. */
    val zones: List<Double>,
    /** Har bir zona markazga nisbatan, %. */
    val relativeToCenterPct: List<Double>,
    /** Bir tekislik U = L_min / L_max × 100 % (VESA FPDM 2.0, 306-1). */
    val uniformityPct: Double,
    /** Markazdan eng katta og'ish, %. */
    val maxDeviationFromCenterPct: Double,
    val level: QualityLevel,
)

/**
 * 9 nuqtali usul: zonalar markazlari (1/6, 1/2, 5/6) atrofida ekran o'lchamining ±5% li
 * yamoqlarida o'rtacha yorqinlik (kesilgan o'rtacha). Manba: VESA Flat Panel Display
 * Measurements Standard (FPDM) 2.0, 306-1 "Sampled uniformity"; ISO 9241-305.
 *
 * Baholash (tizimning ichki mezoni, sozlanadi): U ≥ 80% — yaxshi; 70–80% — qoniqarli; < 70% — past.
 */
object UniformityAnalyzer {
    var goodThresholdPct = 80.0
    var fairThresholdPct = 70.0

    fun analyze(lum: GrayImage, g: ScreenGeometry): UniformityResult {
        val zones = ArrayList<Double>(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            val uc = (c + 0.5) / 3
            val vc = (r + 0.5) / 3
            zones += trimmedMean(sampleRegion(lum, g, uc - 0.05, uc + 0.05, vc - 0.05, vc + 0.05, 20, 20))
        }
        val center = zones[4]
        val rel = zones.map { 100.0 * it / center }
        val u = 100.0 * zones.min() / zones.max()
        val dev = rel.maxOf { abs(it - 100.0) }
        val level = when {
            u >= goodThresholdPct -> QualityLevel.GOOD
            u >= fairThresholdPct -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
        return UniformityResult(zones, rel, u, dev, level)
    }
}

// =====================================================================================
// 2. Piksel nuqsonlari
// =====================================================================================

enum class DefectKind {
    /** Yorqin fonda qora/qorong'i nuqta (o'lik piksel) — ISO 9241-307, 2-tur. */
    DARK_PIXEL,
    /** Qora fonda yorug' nuqta (yonib qolgan piksel) — 1-tur. */
    BRIGHT_PIXEL,
    /** Rangli fonda boshqa rang (subpiksel nuqsoni) — 3-tur. */
    SUBPIXEL,
    /** Kattaroq dog' (chang, mura) — piksel nuqsoni emas. */
    SPOT,
}

data class Defect(
    val kind: DefectKind,
    /** Ekrandagi joyi, normallashgan (0..1). */
    val u: Double,
    val v: Double,
    /** Taxminiy o'lcham, ekran pikselida (ekvivalent diametr). */
    val sizeScreenPx: Double,
    /** Nisbiy kontrast (lokal fonga nisbatan). */
    val contrast: Double,
)

data class DefectResult(
    val pattern: TestPattern,
    val defects: List<Defect>,
    /** Kamera pikseli / ekran pikseli. */
    val cameraPxPerScreenPx: Double,
    val noiseSigma: Double,
    /** Juda ko'p nomzod — moiré yoki shovqin (fokusni biroz buzing / masofani o'zgartiring). */
    val moireSuspected: Boolean,
    /** Ekran pikseli kamerada 1.2 pikseldan kichik — bitta piksel nuqsonlari ko'rinmasligi mumkin. */
    val lowResolution: Boolean,
)

/**
 * Bir rangli ekranda lokal fondan ajralib turgan nuqtalarni topish.
 *
 *  1. Har bir kanal uchun fon: blok medianalari (blok ≈ ekran kengligining 1/48 i) +
 *     bilinear interpolyatsiya (medianа nuqsonlarga sezgir emas).
 *  2. Og'ish: yorqin fonda d = max_c |I_c − B_c| / B_Y; qora fonda d = (Y − B_Y) (absolyut).
 *  3. Shovqin σ — og'ishning MAD bahosi (σ = 1.4826·MAD); nomzod: d > max(k·σ, d_min).
 *  4. Nomzodlar 8-qo'shnilik bo'yicha guruhlanadi; markaz, maydon, cho'qqi kontrast.
 *  5. Tur: maydon ekran pikselida ≤ 9 (≈ 3×3) — piksel nuqsoni, aks holda dog'.
 */
object PixelDefectAnalyzer {

    fun analyze(img: RgbImage, g: ScreenGeometry, screenWidthPx: Int = 1920): DefectResult {
        val w = img.width
        val h = img.height
        val lum = img.luminance()
        val camPerScreen = g.widthPx / screenWidthPx
        val dark = g.pattern == TestPattern.BLACK
        val bs = max(8, (g.widthPx / 48).roundToInt())
        val gw = ceil(w.toDouble() / bs).toInt()
        val gh = ceil(h.toDouble() / bs).toInt()

        // Ekran ichidagi (marker zonalarisiz) piksellar niqobi — u, v orqali
        val inside = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val uv = g.screenUv(x.toDouble(), y.toDouble())
            inside[y * w + x] = uv.x in 0.012..0.988 && uv.y in 0.015..0.985 && !g.inMarkerZone(uv.x, uv.y, 12.0)
        }

        val chans = arrayOf(img.r, img.g, img.b, lum.data)
        val bg = Array(4) { blockMedian(chans[it], inside, w, h, bs, gw, gh) }

        fun bgAt(c: Int, x: Int, y: Int): Double {
            val gx = ((x + 0.5) / bs - 0.5).coerceIn(0.0, gw - 1.0)
            val gy = ((y + 0.5) / bs - 0.5).coerceIn(0.0, gh - 1.0)
            val x0 = floor(gx).toInt().coerceAtMost(max(0, gw - 2))
            val y0 = floor(gy).toInt().coerceAtMost(max(0, gh - 2))
            val x1 = min(x0 + 1, gw - 1)
            val y1 = min(y0 + 1, gh - 1)
            val fx = gx - x0
            val fy = gy - y0
            val gr = bg[c]
            return gr[y0 * gw + x0] * (1 - fx) * (1 - fy) + gr[y0 * gw + x1] * fx * (1 - fy) +
                gr[y1 * gw + x0] * (1 - fx) * fy + gr[y1 * gw + x1] * fx * fy
        }

        // Og'ish (ishorali: + yorug'roq, − qorong'iroq) va maksimal kanal og'ishi
        val dev = FloatArray(w * h)
        val samples = ArrayList<Double>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!inside[i]) continue
            val bY = max(bgAt(3, x, y), 1e-4)
            val d = if (dark) {
                lum.data[i] - bY
            } else {
                var best = 0.0
                for (c in 0..2) {
                    val dc = (chans[c][i] - bgAt(c, x, y)) / bY
                    if (abs(dc) > abs(best)) best = dc
                }
                best
            }
            dev[i] = d.toFloat()
            if ((x + 7 * y) % 11 == 0) samples += d
        }
        if (samples.isEmpty()) return DefectResult(g.pattern, emptyList(), camPerScreen, 0.0, false, camPerScreen < 1.2)
        val med = Stats.median(samples.toDoubleArray())
        val sigma = 1.4826 * Stats.median(samples.map { abs(it - med) }.toDoubleArray())
        val thr = if (dark) max(10 * sigma, 0.01) else max(6 * sigma, 0.22)

        val cand = ArrayList<Int>()
        for (i in 0 until w * h) if (inside[i] && abs(dev[i] - med) > thr) cand += i
        val moire = cand.size > 20000
        if (moire) return DefectResult(g.pattern, emptyList(), camPerScreen, sigma, true, camPerScreen < 1.2)

        // 8-qo'shnilik bo'yicha guruhlash
        val set = HashSet<Int>(cand)
        val seen = HashSet<Int>()
        val defects = ArrayList<Defect>()
        for (s in cand) {
            if (s in seen) continue
            val comp = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            stack.add(s); seen.add(s)
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                comp += p
                val px = p % w
                val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val qx = px + dx
                    val qy = py + dy
                    if (qx !in 0 until w || qy !in 0 until h) continue
                    val q = qy * w + qx
                    if (q in set && seen.add(q)) stack.add(q)
                }
            }
            var sx = 0.0; var sy = 0.0; var peak = 0.0
            var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
            for (p in comp) {
                val x = p % w
                val y = p / w
                sx += x; sy += y
                if (abs(dev[p]) > abs(peak)) peak = dev[p].toDouble()
                sumR += img.r[p] - bgAt(0, x, y); sumG += img.g[p] - bgAt(1, x, y); sumB += img.b[p] - bgAt(2, x, y)
            }
            val cx = sx / comp.size
            val cy = sy / comp.size
            val uv = g.screenUv(cx, cy)
            if (comp.size < 2 && camPerScreen > 1.5) continue          // yakka shovqin pikseli
            // O'lcham yarim maksimum bo'yicha (FWHM-ga o'xshash): kuchli nuqtaning optik xiraligi
            // va qora fonda "yoyilishi" (blooming) uni kattaroq ko'rsatmasligi uchun. To'yingan
            // nuqtalarda cho'qqi tekis bo'lgani uchun bu ham konservativ baho beradi.
            val halfArea = comp.count { abs(dev[it] - med) >= 0.5 * abs(peak - med) }
            val areaScreen = halfArea / (camPerScreen * camPerScreen)
            val diam = 2 * sqrt(areaScreen / Math.PI)
            val spotLimit = if (dark) 25.0 else 9.0
            val kind = when {
                areaScreen > spotLimit -> DefectKind.SPOT
                dark -> DefectKind.BRIGHT_PIXEL
                isColorTest(g.pattern) && isOtherColor(g.pattern, sumR, sumG, sumB) -> DefectKind.SUBPIXEL
                peak < 0 -> if (isColorTest(g.pattern)) DefectKind.SUBPIXEL else DefectKind.DARK_PIXEL
                else -> DefectKind.SUBPIXEL
            }
            defects += Defect(kind, uv.x, uv.y, diam, peak)
        }
        return DefectResult(g.pattern, defects.sortedByDescending { abs(it.contrast) }, camPerScreen, sigma, false, camPerScreen < 1.2)
    }

    private fun isColorTest(p: TestPattern) = p == TestPattern.RED || p == TestPattern.GREEN || p == TestPattern.BLUE

    /** Rangli fonda boshqa kanal kuchaygan bo'lsa — "yopishgan" subpiksel. */
    private fun isOtherColor(p: TestPattern, dr: Double, dg: Double, db: Double): Boolean = when (p) {
        TestPattern.RED -> dg > 0 || db > 0
        TestPattern.GREEN -> dr > 0 || db > 0
        TestPattern.BLUE -> dr > 0 || dg > 0
        else -> false
    }

    private fun blockMedian(d: FloatArray, inside: BooleanArray, w: Int, h: Int, bs: Int, gw: Int, gh: Int): DoubleArray {
        val out = DoubleArray(gw * gh) { Double.NaN }
        val buf = DoubleArray(bs * bs)
        for (by in 0 until gh) for (bx in 0 until gw) {
            var n = 0
            for (y in by * bs until min(h, (by + 1) * bs)) for (x in bx * bs until min(w, (bx + 1) * bs)) {
                val i = y * w + x
                if (inside[i]) buf[n++] = d[i].toDouble()
            }
            if (n >= bs * bs / 4) out[by * gw + bx] = Stats.median(buf.copyOf(n))
        }
        // Bo'sh bloklar (ekran tashqarisi) — eng yaqin to'ldirilgan qo'shni bilan
        repeat(gw + gh) {
            var changed = false
            for (by in 0 until gh) for (bx in 0 until gw) {
                if (!out[by * gw + bx].isNaN()) continue
                var s = 0.0; var k = 0
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val x = bx + dx; val y = by + dy
                    if (x in 0 until gw && y in 0 until gh && !out[y * gw + x].isNaN()) { s += out[y * gw + x]; k++ }
                }
                if (k > 0) { out[by * gw + bx] = s / k; changed = true }
            }
            if (!changed) return@repeat
        }
        for (i in out.indices) if (out[i].isNaN()) out[i] = 0.0
        return out
    }
}

/**
 * ISO 9241-307:2008, 5.5.7 — piksel nuqsonlari sinflari (1 million pikselga):
 *   Sinf 0: 0 / 0 / 0;  Sinf I: 1 / 1 / 2;  Sinf II: 2 / 2 / 5;  Sinf III: 5 / 15 / 50
 * (1-tur — doim yonib turgan piksel, 2-tur — doim o'chgan piksel, 3-tur — subpiksel nuqsoni).
 */
object PixelFaultClass {
    private val limits = listOf(
        0 to intArrayOf(0, 0, 0),
        1 to intArrayOf(1, 1, 2),
        2 to intArrayOf(2, 2, 5),
        3 to intArrayOf(5, 15, 50),
    )

    /**
     * @param megapixels ekrandagi piksellar soni, million (masalan, 1920×1080 → 2.07).
     * Ruxsat etilgan son = ⌊chegara × MP⌋, lekin 1 MP dan kichik ekranlarda nolga tushib
     * qolmasligi uchun kamida 1 (chegara > 0 bo'lsa).
     * @return sinf 0..3, yoki 4 — III sinf chegarasidan ham oshgan.
     */
    fun classify(type1: Int, type2: Int, type3: Int, megapixels: Double): Int {
        fun allowed(lim: Int) = if (lim == 0) 0 else max(1, floor(lim * megapixels).toInt())
        for ((cls, lim) in limits) {
            if (type1 <= allowed(lim[0]) && type2 <= allowed(lim[1]) && type3 <= allowed(lim[2])) return cls
        }
        return 4
    }

    /** Bir nechta testdagi nuqsonlarni birlashtirish (bir joydagi nuqson bir marta hisoblanadi). */
    fun merge(results: List<DefectResult>, tolerance: Double = 0.004): List<Defect> {
        val all = results.flatMap { r -> r.defects.filter { it.kind != DefectKind.SPOT } }
        val out = ArrayList<Defect>()
        for (d in all) if (out.none { abs(it.u - d.u) < tolerance && abs(it.v - d.v) < tolerance }) out += d
        return out
    }
}

// =====================================================================================
// 3. Gradient pog'onalari (rang chuqurligi)
// =====================================================================================

data class BandStats(
    val from: Int,
    val to: Int,
    /** Har bir pog'ona katagining o'rtacha yorqinligi (64 ta). */
    val means: List<Double>,
    /** Ko'rinadigan pog'onalar soni (kutilgan 64). */
    val visibleSteps: Int,
    /** Ahamiyatli o'sishlarning 4 ga karrali joylarda to'planganligi (6-bit belgisi), 0..1. */
    val fourStepPattern: Double,
    val clippedFraction: Double,
)

enum class BitDepthVerdict {
    /** 64 pog'onaning deyarli hammasi ko'rinadi: 8-bit (yoki 6-bit+FRC). */
    EIGHT_BIT_OR_FRC,
    /** ~16 pog'ona: 6-bit panel (FRC'siz). */
    SIX_BIT,
    INCONCLUSIVE,
}

data class BandingResult(val dark: BandStats, val bright: BandStats, val verdict: BitDepthVerdict)

/**
 * 9-test: yuqori polosa 0→63, pastki 192→255, har biri 64 teng katak.
 * Har bir katakning markaziy 50% ida o'rtacha yorqinlik m_k va standart xato se_k.
 * O'sish d_k = m_{k+1} − m_k ahamiyatli: d_k > 4·√(se_k² + se_{k+1}²) va d_k > 0.25·⟨d⟩
 * (⟨d⟩ = (m_63 − m_0)/63 — o'rtacha pog'ona). Pog'onalar soni = 1 + ahamiyatli o'sishlar.
 * 8-bit panelda 64, 6-bitda (FRC'siz) 4 tadan birlashib 16 bo'ladi.
 * Asosiy xulosa yorug' polosa bo'yicha (shovqin nisbati yaxshiroq).
 */
object BandingAnalyzer {

    fun analyze(lum: GrayImage, g: ScreenGeometry): BandingResult {
        val dark = band(lum, g, 0.14, 0.40, 0, 63)
        val bright = band(lum, g, 0.60, 0.86, 192, 255)
        val verdict = when {
            bright.clippedFraction > 0.02 -> BitDepthVerdict.INCONCLUSIVE
            bright.visibleSteps >= 52 -> BitDepthVerdict.EIGHT_BIT_OR_FRC
            bright.visibleSteps <= 24 && bright.fourStepPattern > 0.6 -> BitDepthVerdict.SIX_BIT
            else -> BitDepthVerdict.INCONCLUSIVE
        }
        return BandingResult(dark, bright, verdict)
    }

    private fun band(lum: GrayImage, g: ScreenGeometry, v0: Double, v1: Double, from: Int, to: Int): BandStats {
        val n = 64
        val means = DoubleArray(n)
        val se = DoubleArray(n)
        var clipped = 0
        var total = 0
        for (k in 0 until n) {
            val s = sampleRegion(lum, g, (k + 0.25) / n, (k + 0.75) / n, v0, v1, 6, 60)
            means[k] = s.average()
            // Qo'shni namunalar korrelyatsiyalangan — samarali son ≈ n/4
            se[k] = Stats.std(s) / sqrt(s.size / 4.0)
            for (x in s) { total++; if (x > 0.97) clipped++ }
        }
        val avgStep = (means[n - 1] - means[0]) / (n - 1)
        var rises = 0
        var on4 = 0
        for (k in 0 until n - 1) {
            val d = means[k + 1] - means[k]
            val sd = sqrt(se[k] * se[k] + se[k + 1] * se[k + 1])
            if (d > 4 * sd && d > 0.25 * avgStep) {
                rises++
                if ((k + 1) % 4 == 0) on4++
            }
        }
        return BandStats(from, to, means.toList(), rises + 1, if (rises > 0) on4.toDouble() / rises else 0.0,
            clipped.toDouble() / total)
    }
}

// =====================================================================================
// 4. Chetlardan yorug'lik sizishi (backlight bleed)
// =====================================================================================

data class BleedResult(
    /** 12×8 katakli xarita (nisbiy yorqinlik, markaz = 1). NaN — marker zonasi. */
    val map: List<Double>,
    val cols: Int,
    val rows: Int,
    /** Chet kataklari ichidagi eng katta nisbat (chet / markaz). */
    val maxEdgeRatio: Double,
    /** Eng yorug' chet katagining joyi (u, v). */
    val hotspot: Pt,
    val level: QualityLevel,
)

/**
 * Qora ekranda chet sohalari yorqinligini markaz bilan solishtirish.
 * Baholash (tizimning ichki mezoni): nisbat < 1.5 — sezilarli emas; 1.5–3 — o'rtacha; > 3 — kuchli.
 * Eslatma: IPS panellarda burchakdan qaraganda "IPS glow" ham shunday ko'rinadi — telefonni
 * ekranga perpendikulyar va yetarlicha uzoq ushlash kerak.
 */
object BleedAnalyzer {
    fun analyze(lum: GrayImage, g: ScreenGeometry, cols: Int = 12, rows: Int = 8): BleedResult {
        val vals = DoubleArray(cols * rows) { Double.NaN }
        for (r in 0 until rows) for (c in 0 until cols) {
            val u0 = c.toDouble() / cols
            val v0 = r.toDouble() / rows
            val uc = u0 + 0.5 / cols
            val vc = v0 + 0.5 / rows
            if (g.inMarkerZone(uc, vc, 16.0)) continue
            val s = sampleRegion(lum, g, u0 + 0.1 / cols, u0 + 0.9 / cols, v0 + 0.1 / rows, v0 + 0.9 / rows, 8, 8)
            vals[r * cols + c] = s.average()
        }
        val centerVals = ArrayList<Double>()
        for (r in rows / 2 - 1..rows / 2) for (c in cols / 2 - 1..cols / 2) centerVals += vals[r * cols + c]
        val center = max(Stats.median(centerVals.toDoubleArray()), 1e-6)
        val map = vals.map { it / center }
        var best = 0.0
        var hot = Pt(0.5, 0.5)
        for (r in 0 until rows) for (c in 0 until cols) {
            if (r != 0 && r != rows - 1 && c != 0 && c != cols - 1) continue
            val v = map[r * cols + c]
            if (!v.isNaN() && v > best) { best = v; hot = Pt((c + 0.5) / cols, (r + 0.5) / rows) }
        }
        val level = when {
            best < 1.5 -> QualityLevel.GOOD
            best < 3.0 -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
        return BleedResult(map, cols, rows, best, hot, level)
    }
}
