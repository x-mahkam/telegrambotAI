package uz.monitorcheck.analysis.flicker

import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Kamera chiqishidagi qiymatni nisbiy yorug'lik intensivligiga aylantirish modeli.
 *
 * Kamera odatda gamma-kodlangan qiymat beradi (I ∝ Y^γ). Modulyatsiya chuqurligi
 * (Imax − Imin)/(Imax + Imin) faqat CHIZIQLI intensivlikda to'g'ri, shuning uchun
 * har bir piksel o'rtachalashdan OLDIN chiziqlashtiriladi.
 */
enum class ResponseModel {
    /** Kamera chiziqli tonemap egri chizig'i bilan sozlangan (Camera2 TONEMAP_MODE_CONTRAST_CURVE). */
    LINEAR,

    /**
     * Tonemap'ni boshqarib bo'lmaydi: sRGB uzatish funksiyasining teskarisi taxminiy
     * qo'llanadi (IEC 61966-2-1). Modulyatsiya natijasi taxminiy deb belgilanadi.
     */
    SRGB_APPROX,
}

/** Kadr qatorlari bo'yicha o'rtacha (chiziqli) yorug'lik profili va sifat ko'rsatkichlari. */
class RowProfile(
    /** Har bir qator (sensor o'qish tartibida) uchun o'rtacha chiziqli intensivlik, 0..1. */
    val values: DoubleArray,
    /** ROI ichidagi o'rtacha kod qiymati, 0..1 (ekspozitsiyani sozlash uchun). */
    val meanCode: Double,
    /** To'yingan (Y ≥ 250) piksellar ulushi. */
    val saturatedFraction: Double,
    val response: ResponseModel,
) {
    val rows: Int get() = values.size

    companion object {
        /** To'yinish chegarasi (8-bit kod). */
        const val SATURATION_CODE = 250

        /**
         * sRGB → chiziqli: C ≤ 0.04045 bo'lsa C/12.92, aks holda ((C + 0.055)/1.055)^2.4.
         * Manba: IEC 61966-2-1:1999.
         */
        fun srgbToLinear(c: Double): Double =
            if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

        private val SRGB_LUT = DoubleArray(256) { srgbToLinear(it / 255.0) }
        private val LINEAR_LUT = DoubleArray(256) { it / 255.0 }

        /**
         * YUV_420_888 tasvirining Y (yorqinlik) tekisligidan profil hisoblash.
         *
         * Y tekisligi SENSOR yo'nalishida (ekran aylanishidan qat'i nazar): r-qator
         * sensor r-qatori bo'lib, rolling shutter vaqt o'qi aynan shu bo'yicha ketadi.
         * Ustunlar bo'yicha faqat markaziy qism ([colFrom; colTo] ulushi) olinadi —
         * linza vinyetkasi va ekran chetlari ta'sirini kamaytirish uchun.
         */
        fun fromLuma(
            buffer: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int = 1,
            response: ResponseModel = ResponseModel.LINEAR,
            colFrom: Double = 0.25,
            colTo: Double = 0.75,
            colStep: Int = 2,
        ): RowProfile {
            val lut = if (response == ResponseModel.LINEAR) LINEAR_LUT else SRGB_LUT
            val c0 = (width * colFrom).toInt().coerceIn(0, width - 1)
            val c1 = (width * colTo).toInt().coerceIn(c0 + 1, width)
            val values = DoubleArray(height)
            var codeSum = 0L
            var sat = 0L
            var count = 0L
            for (r in 0 until height) {
                val base = r * rowStride
                var s = 0.0
                var n = 0
                var c = c0
                while (c < c1) {
                    val v = buffer.get(base + c * pixelStride).toInt() and 0xFF
                    s += lut[v]
                    codeSum += v
                    if (v >= SATURATION_CODE) sat++
                    n++
                    c += colStep
                }
                values[r] = s / n
                count += n
            }
            return RowProfile(values, codeSum / (255.0 * count), sat.toDouble() / count, response)
        }
    }
}
