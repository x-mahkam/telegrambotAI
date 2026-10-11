package uz.monitorcheck.analysis.screen

import uz.monitorcheck.analysis.geometry.Homography
import uz.monitorcheck.analysis.geometry.Marker
import uz.monitorcheck.analysis.image.Pt
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Test sahifasidagi rasmlar (docs/marker-spec.md bilan bir xil tartib). */
enum class TestPattern(val id: Int) {
    WHITE(1), BLACK(2), RED(3), GREEN(4), BLUE(5), GRAY50(6), BLEED(7), GRADIENT(8), GRADIENT_STEPS(9), FLICKER(10);

    companion object {
        fun byId(id: Int): TestPattern? = entries.firstOrNull { it.id == id }

        /** markerId = (testId − 1)·4 + burchak. */
        fun fromMarkerId(markerId: Int): Pair<TestPattern?, Int> = byId(markerId / 4 + 1) to (markerId % 4)
    }
}

/**
 * Tasvirdagi ekran geometriyasi.
 *
 * Ekran koordinatalari "katak" birligida (marker katagi c = round(min(W,H)/135) piksel):
 * (0,0) — ekranning yuqori-chap burchagi, (wCells, hCells) — pastki-o'ng.
 * [toImage] ekran → tasvir gomografiyasi.
 */
class ScreenGeometry(
    val pattern: TestPattern,
    val markers: List<Marker>,
    val toImage: Homography,
    val wCells: Double,
    val hCells: Double,
    /** 16 ta marker burchagi bo'yicha qayta proyeksiya xatosi (RMS, piksel). */
    val rmsErrorPx: Double,
) {
    private val fromImage = toImage.inverse()

    val aspect: Double get() = wCells / hCells

    /** Normallashgan ekran koordinatalari (u, v ∈ [0; 1]) → tasvir. */
    fun imagePoint(u: Double, v: Double): Pt = toImage.map(u * wCells, v * hCells)

    /** Tasvir → normallashgan ekran koordinatalari. */
    fun screenUv(x: Double, y: Double): Pt {
        val p = fromImage.map(x, y)
        return Pt(p.x / wCells, p.y / hCells)
    }

    /** Ekran burchaklari tasvirda: TL, TR, BR, BL. */
    val corners: List<Pt> get() = listOf(imagePoint(0.0, 0.0), imagePoint(1.0, 0.0), imagePoint(1.0, 1.0), imagePoint(0.0, 1.0))

    /** Marker va uning atrofi (burchakdan [marginCells] katakkacha) — tahlildan chiqariladi. */
    fun inMarkerZone(u: Double, v: Double, marginCells: Double = 11.0): Boolean {
        val x = u * wCells
        val y = v * hCells
        val nearX = x < marginCells || x > wCells - marginCells
        val nearY = y < marginCells || y > hCells - marginCells
        return nearX && nearY
    }

    /** Ekranning tasvirdagi o'rtacha kengligi, piksel (masshtab uchun). */
    val widthPx: Double
        get() {
            val c = corners
            return ((c[1] - c[0]).norm() + (c[2] - c[3]).norm()) / 2
        }

    /**
     * Kamera o'qiga nisbatan ekran burchaklariga qarash burchaklari (gradus), pinhole model:
     * θ = atan(r / f_px), r — burchakning optik markazdan masofasi. Telefon ekranga
     * perpendikulyar bo'lsa, bu taxminan ekranga qarash burchagi.
     */
    fun cornerViewAnglesDeg(cx: Double, cy: Double, focalPx: Double): List<Double> =
        corners.map { Math.toDegrees(atan(hypot(it.x - cx, it.y - cy) / focalPx)) }
}

object ScreenLocator {

    /** Ko'pchilik markerlar bo'yicha test turi. */
    fun identify(markers: List<Marker>): TestPattern? =
        markers.mapNotNull { TestPattern.fromMarkerId(it.id).first }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Marker burchaklarining ekran koordinatalari (kataklarda) — docs/marker-spec.md. */
    fun markerCornersCells(corner: Int, w: Double, h: Double): List<Pt> {
        val (x0, y0) = when (corner) {
            0 -> 1.0 to 1.0
            1 -> (w - 7) to 1.0
            2 -> (w - 7) to (h - 7)
            else -> 1.0 to (h - 7)
        }
        return listOf(Pt(x0, y0), Pt(x0 + 6, y0), Pt(x0 + 6, y0 + 6), Pt(x0, y0 + 6))
    }

    /**
     * To'rtala marker bo'yicha ekran geometriyasini tiklash.
     *
     * Noma'lumlar: gomografiya (8) va ekran o'lchami kataklarda (W_c, H_c). 16 nuqtadan
     * W_c, H_c bo'yicha qayta proyeksiya xatosi minimallashtiriladi (avval to'r, keyin
     * koordinatali oltin kesim). W_c/H_c — ekranning haqiqiy tomonlar nisbati.
     */
    fun locate(markers: List<Marker>): ScreenGeometry? {
        val pattern = identify(markers) ?: return null
        val byCorner = HashMap<Int, Marker>()
        for (m in markers) {
            val (p, corner) = TestPattern.fromMarkerId(m.id)
            if (p == pattern) byCorner[corner] = m
        }
        if (byCorner.size < 4) return null
        val img = (0 until 4).flatMap { byCorner.getValue(it).corners }

        fun fit(w: Double, h: Double): Pair<Homography, Double> {
            val scr = (0 until 4).flatMap { markerCornersCells(it, w, h) }
            val hm = Homography.fromPoints(scr, img)
            var se = 0.0
            for (i in scr.indices) {
                val p = hm.map(scr[i])
                se += (p.x - img[i].x).let { it * it } + (p.y - img[i].y).let { it * it }
            }
            return hm to sqrt(se / scr.size)
        }

        // Dastlabki baho: ekran ~135 katak balandlikda (marker-spec formulasi), nisbat to'r bo'yicha
        var bestW = 240.0
        var bestH = 135.0
        var bestE = Double.MAX_VALUE
        var hc = 110.0
        while (hc <= 160.0) {
            var a = 1.0
            while (a <= 2.6) {
                val e = fit(a * hc, hc).second
                if (e < bestE) { bestE = e; bestW = a * hc; bestH = hc }
                a += 0.05
            }
            hc += 5.0
        }
        repeat(6) {
            bestW = golden(bestW - 12, bestW + 12) { fit(it, bestH).second }
            bestH = golden(bestH - 8, bestH + 8) { fit(bestW, it).second }
        }
        val (hm, err) = fit(bestW, bestH)
        return ScreenGeometry(pattern, byCorner.values.sortedBy { it.id }, hm, bestW, bestH, err)
    }

    private fun golden(lo0: Double, hi0: Double, f: (Double) -> Double): Double {
        val g = (sqrt(5.0) - 1) / 2
        var lo = max(1.0, lo0)
        var hi = hi0
        var x1 = hi - g * (hi - lo)
        var x2 = lo + g * (hi - lo)
        var f1 = f(x1)
        var f2 = f(x2)
        repeat(30) {
            if (f1 < f2) { hi = x2; x2 = x1; f2 = f1; x1 = hi - g * (hi - lo); f1 = f(x1) }
            else { lo = x1; x1 = x2; f1 = f2; x2 = lo + g * (hi - lo); f2 = f(x2) }
        }
        return (lo + hi) / 2
    }
}
