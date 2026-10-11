package uz.monitorcheck.analysis.geometry

import uz.monitorcheck.analysis.image.GrayImage
import uz.monitorcheck.analysis.image.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Topilgan marker: [corners] — markerning o'z yo'nalishida TL, TR, BR, BL (qora chegaraning tashqi burchaklari). */
data class Marker(val id: Int, val corners: List<Pt>) {
    val center: Pt get() = Pt(corners.sumOf { it.x } / 4, corners.sumOf { it.y } / 4)

    /** Markerning o'rtacha tomon uzunligi (piksel). */
    val side: Double get() = (0 until 4).sumOf { (corners[(it + 1) % 4] - corners[it]).norm() } / 4
}

/**
 * ArUco DICT_4X4_50 markerlarini topish (test sahifasi markerlari, docs/marker-spec.md).
 *
 * Kirish — CHIZIQLI yorqinlik (0..1). Muhim: kamera xiraligi chiziqli yorug'likda sodir
 * bo'ladi, shuning uchun chekkaning 50% nuqtasi faqat chiziqli qiymatlarda haqiqiy chekkaga
 * to'g'ri keladi (gamma-kodlangan qiymatlarda ~0.5–1 px siljish beradi).
 *
 * Algoritm:
 *  1. Moslashuvchan bo'sag'a: piksel lokal o'rtachadan (integral tasvir, oyna ≈ tasvir/20)
 *     12% dan ko'p qorong'i bo'lsa — "qora".
 *  2. Qora bog'liq sohalar (4-qo'shnilik); o'lcham va shakl bo'yicha saralash.
 *  3. Har bir soha chegarasining qavariq qobig'i → to'rtburchak (eng uzoq juftlik va
 *     har ikki tomondagi eng uzoq nuqtalar).
 *  4. To'rtburchak ichida 6×6 katak gomografiya orqali o'qiladi: tashqi halqa qora bo'lishi,
 *     ichki 4×4 bitlar lug'atdagi kod bilan (4 ta burilishda) mos kelishi shart.
 *  5. Burchaklar subpiksel aniqlikda: har bir tomon bo'ylab kesimlarda qora↔oq o'tishning
 *     50% nuqtasi topiladi, chiziq (umumiy eng kichik kvadratlar) moslanadi, qo'shni
 *     chiziqlar kesishmasi — burchak.
 */
class ArucoDetector(
    private val thresholdFraction: Double = 0.12,
    private val minSidePx: Int = 10,
) {

    fun detect(img: GrayImage): List<Marker> {
        val w = img.width
        val h = img.height
        val dark = threshold(img)
        val labels = IntArray(w * h)
        val found = HashMap<Int, Marker>()
        var next = 1
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!dark[start] || labels[start] != 0) continue
            // Bog'liq sohani to'ldirish (stack bilan)
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var area = 0
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            val boundary = ArrayList<Pt>()
            while (sp > 0) {
                val p = stack[--sp]
                area++
                val x = p % w
                val y = p / w
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                var edge = false
                if (x > 0) { val q = p - 1; if (dark[q]) { if (labels[q] == 0) { labels[q] = next; stack[sp++] = q } } else edge = true } else edge = true
                if (x < w - 1) { val q = p + 1; if (dark[q]) { if (labels[q] == 0) { labels[q] = next; stack[sp++] = q } } else edge = true } else edge = true
                if (y > 0) { val q = p - w; if (dark[q]) { if (labels[q] == 0) { labels[q] = next; stack[sp++] = q } } else edge = true } else edge = true
                if (y < h - 1) { val q = p + w; if (dark[q]) { if (labels[q] == 0) { labels[q] = next; stack[sp++] = q } } else edge = true } else edge = true
                if (edge && boundary.size < 20000) boundary.add(Pt(x.toDouble(), y.toDouble()))
            }
            next++
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minSidePx || bh < minSidePx) continue
            if (bw > w / 2 || bh > h / 2) continue
            if (max(bw, bh).toDouble() / min(bw, bh) > 4.0) continue
            val quad = quadFromPoints(boundary) ?: continue
            val qArea = polygonArea(quad)
            if (qArea < minSidePx * minSidePx) continue
            val fill = area / qArea
            if (fill < 0.3 || fill > 1.1) continue
            // Bo'sag'a chegarani fon yorqinligiga qarab "yeydi" yoki "kengaytiradi" — shuning uchun
            // bitlarni o'qishdan oldin burchaklar chekka profillari bo'yicha aniqlashtiriladi.
            val refinedQuad = refineQuad(img, refineQuad(img, quad))
            val m = decode(img, refinedQuad) ?: continue
            val prev = found[m.id]
            if (prev == null || m.side > prev.side) found[m.id] = m
        }
        return found.values.sortedBy { it.id }
    }

    /**
     * Katta tasvir uchun: [factor] marta kichraytirilgan nusxada topish (xotira va tezlik),
     * so'ng burchaklar to'liq o'lchamli tasvirda qayta aniqlashtiriladi.
     * Kichraytirilgan piksel i markazi to'liq tasvirda f·i + (f − 1)/2 ga to'g'ri keladi.
     */
    fun detectScaled(full: GrayImage, factor: Int): List<Marker> {
        if (factor <= 1) return detect(full)
        val small = full.downscale(factor)
        val off = (factor - 1) / 2.0
        return detect(small).map { m ->
            val scaled = m.corners.map { Pt(it.x * factor + off, it.y * factor + off) }
            val refined = refineQuad(full, refineQuad(full, scaled))
            Marker(m.id, refined)
        }
    }

    // --- 1. Moslashuvchan bo'sag'a ---------------------------------------------------------

    private fun threshold(img: GrayImage): BooleanArray {
        val w = img.width
        val h = img.height
        val integ = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += img.data[y * w + x]
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val r = max(7, max(w, h) / 40)
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                val mean = s / ((x1 - x0) * (y1 - y0))
                out[y * w + x] = img.data[y * w + x] < mean * (1 - thresholdFraction) - 0.002
            }
        }
        return out
    }

    // --- 3. To'rtburchak ------------------------------------------------------------------------

    private fun quadFromPoints(pts: List<Pt>): List<Pt>? {
        val hull = convexHull(pts)
        if (hull.size < 4) return null
        // Eng uzoq juftlik (diagonal)
        var a = 0
        var c = 0
        var best = -1.0
        for (i in hull.indices) for (j in i + 1 until hull.size) {
            val d = (hull[i] - hull[j]).norm()
            if (d > best) { best = d; a = i; c = j }
        }
        val pa = hull[a]
        val pc = hull[c]
        val dir = pc - pa
        var b = -1
        var d = -1
        var maxPos = 0.0
        var maxNeg = 0.0
        for (i in hull.indices) {
            val s = dir.cross(hull[i] - pa)
            if (s > maxPos) { maxPos = s; b = i }
            if (s < maxNeg) { maxNeg = s; d = i }
        }
        if (b < 0 || d < 0) return null
        val quad = listOf(pa, hull[b], pc, hull[d])
        // Qavariq qobiq yuzasining ko'p qismi to'rtburchakka tushishi kerak
        if (polygonArea(quad) < 0.8 * polygonArea(hull)) return null
        return orderClockwise(quad)
    }

    private fun convexHull(points: List<Pt>): List<Pt> {
        val p = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (p.size < 3) return p
        val lower = ArrayList<Pt>()
        for (pt in p) {
            while (lower.size >= 2 && (lower[lower.size - 1] - lower[lower.size - 2]).cross(pt - lower[lower.size - 2]) <= 0) lower.removeAt(lower.size - 1)
            lower.add(pt)
        }
        val upper = ArrayList<Pt>()
        for (pt in p.asReversed()) {
            while (upper.size >= 2 && (upper[upper.size - 1] - upper[upper.size - 2]).cross(pt - upper[upper.size - 2]) <= 0) upper.removeAt(upper.size - 1)
            upper.add(pt)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    // --- 4. Bitlarni o'qish ---------------------------------------------------------------------

    private fun decode(img: GrayImage, quad: List<Pt>): Marker? {
        val hm = Homography.unitSquareTo(quad)
        val v = Array(6) { DoubleArray(6) }
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until 6) for (j in 0 until 6) {
            var s = 0.0
            for (a in -1..1) for (b in -1..1) {
                val p = hm.map((j + 0.5 + 0.2 * b) / 6, (i + 0.5 + 0.2 * a) / 6)
                s += img.sample(p.x, p.y)
            }
            v[i][j] = s / 9
            lo = min(lo, v[i][j])
            hi = max(hi, v[i][j])
        }
        if (hi - lo < 0.15 * hi || hi <= 0) return null
        val t = (lo + hi) / 2
        var borderWhite = 0
        for (k in 0 until 6) {
            if (v[0][k] > t) borderWhite++
            if (v[5][k] > t) borderWhite++
            if (k in 1..4) {
                if (v[k][0] > t) borderWhite++
                if (v[k][5] > t) borderWhite++
            }
        }
        if (borderWhite > 1) return null
        var bits = Array(4) { r -> IntArray(4) { c -> if (v[r + 1][c + 1] > t) 1 else 0 } }
        for (rot in 0 until 4) {
            val code = bits.foldIndexed(0) { r, acc, row -> row.foldIndexed(acc) { c, a, bit -> a or (bit shl (15 - (r * 4 + c))) } }
            val id = CODE_TO_ID[code]
            if (id != null) {
                // Namunada bitlar marker yo'nalishiga nisbatan `rot` marta soat strelkasi bo'yicha
                // burilgan bo'lsa, markerning TL burchagi quad[rot] da.
                val corners = List(4) { quad[(it + rot) % 4] }
                return Marker(id, corners)
            }
            bits = rotateCcw(bits)
        }
        return null
    }

    /** Matritsani soat strelkasiga teskari 90° burish (namuna buriluvini "orqaga qaytarish"). */
    private fun rotateCcw(m: Array<IntArray>): Array<IntArray> =
        Array(4) { r -> IntArray(4) { c -> m[c][3 - r] } }

    // --- 5. Subpiksel aniqlashtirish ------------------------------------------------------------

    /**
     * To'rtburchak burchaklarini aniqlashtirish. Har bir tomonga perpendikulyar 11 ta kesim
     * olinadi (−0.8 … +1.2 katak, tashqariga). Kesimdagi minimum — qora chegara, undan keyingi
     * maksimum — oq sokin zona; ular o'rtasidagi 50% sath kesishmasi — chekka nuqtasi
     * (simmetrik xiralikda haqiqiy chekka). Nuqtalarga chiziq moslanadi, kesishmalar — burchaklar.
     */
    private fun refineQuad(img: GrayImage, c: List<Pt>): List<Pt> {
        val side = (0 until 4).sumOf { (c[(it + 1) % 4] - c[it]).norm() } / 4
        val cell = side / 6
        val center = Pt(c.sumOf { it.x } / 4, c.sumOf { it.y } / 4)
        val lines = ArrayList<DoubleArray>(4)
        val nS = 41
        for (k in 0 until 4) {
            val a = c[k]
            val b = c[(k + 1) % 4]
            val len = (b - a).norm()
            if (len < 1e-6) return c
            val dir = (b - a) * (1.0 / len)
            var n = Pt(-dir.y, dir.x)
            if (n.dot((a + b) * 0.5 - center) < 0) n = n * -1.0       // tashqariga
            val pts = ArrayList<Pt>()
            for (s in 1..11) {
                val t = 0.15 + 0.7 * s / 12
                val base = a + (b - a) * t
                val offs = DoubleArray(nS) { (-0.8 + 2.0 * it / (nS - 1)) * cell }
                val prof = DoubleArray(nS) { val p = base + n * offs[it]; img.sample(p.x, p.y) }
                // Ichki minimum (qora chegara) — kesimning birinchi yarmida
                var iMin = 0
                for (i in 0 until nS / 2) if (prof[i] < prof[iMin]) iMin = i
                var iMax = iMin
                for (i in iMin until nS) if (prof[i] > prof[iMax]) iMax = i
                val lo = prof[iMin]
                val hi = prof[iMax]
                if (iMax <= iMin || hi - lo < 0.1 * max(hi, 1e-6)) continue
                val half = (lo + hi) / 2
                for (i in iMin until iMax) {
                    if (prof[i] <= half && prof[i + 1] > half) {
                        val f = (half - prof[i]) / (prof[i + 1] - prof[i])
                        val off = offs[i] + f * (offs[i + 1] - offs[i])
                        pts.add(base + n * off)
                        break
                    }
                }
            }
            if (pts.size < 4) return c
            lines.add(fitLine(pts))
        }
        val out = ArrayList<Pt>(4)
        for (k in 0 until 4) {
            val p = intersect(lines[(k + 3) % 4], lines[k]) ?: return c
            if ((p - c[k]).norm() > 1.5 * cell) return c                    // ishonchsiz — asl burchak
            out.add(p)
        }
        return out
    }

    /** Umumiy eng kichik kvadratlar (PCA): chiziq a·x + b·y = c, (a, b) — birlik normal. */
    private fun fitLine(p: List<Pt>): DoubleArray {
        val mx = p.sumOf { it.x } / p.size
        val my = p.sumOf { it.y } / p.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (q in p) {
            val dx = q.x - mx
            val dy = q.y - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        // Kovariatsiya matritsasining kichik xos vektori — normal
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val l = tr / 2 - sqrt(max(0.0, tr * tr / 4 - det))
        var a = sxy
        var b = l - sxx
        if (abs(a) + abs(b) < 1e-12) { a = l - syy; b = sxy }
        if (abs(a) + abs(b) < 1e-12) { a = 1.0; b = 0.0 }
        val nrm = sqrt(a * a + b * b)
        a /= nrm; b /= nrm
        return doubleArrayOf(a, b, a * mx + b * my)
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): Pt? {
        val det = l1[0] * l2[1] - l1[1] * l2[0]
        if (abs(det) < 1e-9) return null
        return Pt((l1[2] * l2[1] - l1[1] * l2[2]) / det, (l1[0] * l2[2] - l1[2] * l2[0]) / det)
    }

    companion object {
        /** OpenCV DICT_4X4_50 (test-page/monitor-test.html dagi jadval bilan bir xil). */
        val DICT_4X4_50 = intArrayOf(
            46386, 3994, 13101, 39238, 21662, 31181, 40494, 50418, 65242, 53078,
            63889, 4519, 3767, 10767, 9393, 9790, 18021, 26112, 27742, 30383,
            34443, 45099, 52437, 56706, 65095, 38001, 44260, 42324, 8483, 13423,
            17429, 22450, 40655, 61643, 2222, 2345, 6261, 1279, 3574, 7258,
            5912, 10792, 12940, 14514, 9448, 12011, 11583, 19300, 20526, 20499,
        )
        private val CODE_TO_ID: Map<Int, Int> = DICT_4X4_50.withIndex().associate { it.value to it.index }

        fun polygonArea(p: List<Pt>): Double {
            var s = 0.0
            for (i in p.indices) s += p[i].cross(p[(i + 1) % p.size])
            return abs(s) / 2
        }

        /** Tasvir koordinatalarida (y pastga) soat strelkasi bo'yicha tartiblash. */
        fun orderClockwise(q: List<Pt>): List<Pt> {
            val cx = q.sumOf { it.x } / q.size
            val cy = q.sumOf { it.y } / q.size
            return q.sortedBy { atan2(it.y - cy, it.x - cx) }
        }
    }
}
