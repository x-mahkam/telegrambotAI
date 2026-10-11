package uz.monitorcheck.analysis.image

import uz.monitorcheck.analysis.flicker.RowProfile
import java.nio.ByteBuffer
import kotlin.math.floor

/** Tekislikdagi nuqta (piksel koordinatalari, piksel markazi = butun son). */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Double) = Pt(x * k, y * k)
    fun dot(o: Pt) = x * o.x + y * o.y
    fun cross(o: Pt) = x * o.y - y * o.x
    fun norm() = kotlin.math.hypot(x, y)
}

/** Bir kanalli tasvir (masalan, chiziqli yorqinlik). */
class GrayImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(data.size == width * height)
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    /** Bilinear interpolyatsiya; tasvirdan tashqarida — chetdagi qiymat. */
    fun sample(x: Double, y: Double): Double {
        val xc = x.coerceIn(0.0, width - 1.0)
        val yc = y.coerceIn(0.0, height - 1.0)
        val x0 = floor(xc).toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = floor(yc).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val fx = xc - x0
        val fy = yc - y0
        val i = y0 * width + x0
        val w1 = if (width > 1) 1 else 0
        val h1 = if (height > 1) width else 0
        val a = data[i]
        val b = data[i + w1]
        val c = data[i + h1]
        val d = data[i + h1 + w1]
        return (a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy)
    }

    fun contains(x: Double, y: Double) = x >= 0 && y >= 0 && x <= width - 1 && y <= height - 1

    /** Butun koeffitsientli kichraytirish (blok o'rtachasi). */
    fun downscale(f: Int): GrayImage {
        if (f <= 1) return this
        val w = width / f
        val h = height / f
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0 until f) {
                val row = (y * f + dy) * width + x * f
                for (dx in 0 until f) s += data[row + dx]
            }
            out[y * w + x] = s / (f * f)
        }
        return GrayImage(w, h, out)
    }

    companion object {
        private val SRGB_LUT = FloatArray(256) { RowProfile.srgbToLinear(it / 255.0).toFloat() }

        /** YUV Y tekisligidan, sRGB teskari funksiyasi bilan chiziqlashtirilgan (0..1). */
        fun fromLuma(buf: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int = 1, step: Int = 1): GrayImage {
            val w = width / step
            val h = height / step
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                val row = y * step * rowStride
                for (x in 0 until w) out[y * w + x] = SRGB_LUT[buf.get(row + x * step * pixelStride).toInt() and 0xFF]
            }
            return GrayImage(w, h, out)
        }
    }
}

/**
 * Uch kanalli chiziqli RGB tasvir (0..1). JPEG/ARGB piksellardan sRGB teskari funksiyasi bilan
 * chiziqlashtiriladi (IEC 61966-2-1), chunki yorqinlik nisbatlari faqat chiziqli fazoda ma'noli.
 */
class RgbImage(val width: Int, val height: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {

    /** Nisbiy yorqinlik Y = 0.2126R + 0.7152G + 0.0722B (ITU-R BT.709, chiziqli RGB). */
    fun luminance(): GrayImage = GrayImage(width, height, FloatArray(width * height) {
        0.2126f * r[it] + 0.7152f * g[it] + 0.0722f * b[it]
    })

    companion object {
        private val SRGB = FloatArray(256) { RowProfile.srgbToLinear(it / 255.0).toFloat() }
        private val LIN = FloatArray(256) { it / 255f }

        /**
         * Qatorma-qator to'ldirish (katta Bitmap uchun butun IntArray ajratmaslik maqsadida).
         * [row] (y, buf) — buf ga y-qatorning ARGB piksellarini yozadi.
         */
        fun fromArgbRows(width: Int, height: Int, srgb: Boolean = true, row: (Int, IntArray) -> Unit): RgbImage {
            val lut = if (srgb) SRGB else LIN
            val n = width * height
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            val buf = IntArray(width)
            for (y in 0 until height) {
                row(y, buf)
                val o = y * width
                for (x in 0 until width) {
                    val p = buf[x]
                    r[o + x] = lut[(p shr 16) and 0xFF]
                    g[o + x] = lut[(p shr 8) and 0xFF]
                    b[o + x] = lut[p and 0xFF]
                }
            }
            return RgbImage(width, height, r, g, b)
        }

        /** ARGB_8888 piksellar (Android Bitmap.getPixels) dan. */
        fun fromArgb(pixels: IntArray, width: Int, height: Int, srgb: Boolean = true): RgbImage {
            val lut = if (srgb) SRGB else LIN
            val n = width * height
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            for (i in 0 until n) {
                val p = pixels[i]
                r[i] = lut[(p shr 16) and 0xFF]
                g[i] = lut[(p shr 8) and 0xFF]
                b[i] = lut[p and 0xFF]
            }
            return RgbImage(width, height, r, g, b)
        }
    }
}
