package uz.monitorcheck.analysis.standards

/**
 * IEEE Std 1789-2015 "IEEE Recommended Practices for Modulating Current in High-Brightness
 * LEDs for Mitigating Health Risks to Viewers", 8.1-bo'lim (Recommended Practices 1 va 2).
 *
 * Modulyatsiya chuqurligi Mod% = (L_max − L_min)/(L_max + L_min) × 100.
 *
 *  Past xavf chegarasi (Recommended Practice 1):
 *      f < 90 Hz:           Mod% < 0.025·f
 *      90 ≤ f ≤ 1250 Hz:    Mod% < 0.08·f
 *      f > 1250 Hz:         cheklov yo'q
 *
 *  Ta'sir kuzatilmaydigan daraja — NOEL (Recommended Practice 2):
 *      f < 90 Hz:           Mod% < 0.01·f
 *      90 ≤ f ≤ 3000 Hz:    Mod% < 0.0333·f
 *      f > 3000 Hz:         cheklov yo'q
 *
 * Eslatma: bu baholash ergonomik ko'rsatkichning standart tavsiyasiga muvofiqligini
 * bildiradi; tibbiy xulosa emas.
 */
enum class Ieee1789Level {
    /** Miltillash aniqlanmadi (aniqlash chegarasidan past). */
    NO_FLICKER_DETECTED,

    /** NOEL zonasi — ta'sir kuzatilmaydigan daraja. */
    NO_OBSERVABLE_EFFECT,

    /** Past xavf zonasi (NOEL dan yuqori, lekin past xavf chegarasidan past). */
    LOW_RISK,

    /** Tavsiya etilgan chegaradan tashqarida (yuqori xavf zonasi). */
    HIGH_RISK,

    /** Miltillash bor, lekin chastota noma'lum (t_row yo'q) — standart bo'yicha baholab bo'lmaydi. */
    FREQUENCY_UNKNOWN,

    /**
     * Miltillash topilmadi, lekin o'lchov 3000 Hz gacha qamrab olmadi (ekspozitsiya uzun) —
     * "miltillash yo'q" deb xulosa qilib bo'lmaydi.
     */
    INCONCLUSIVE,
}

data class Ieee1789Assessment(
    val level: Ieee1789Level,
    val frequencyHz: Double?,
    val modulationPct: Double?,
    /** Shu chastotadagi NOEL chegarasi, % (∞ — cheklov yo'q). */
    val noelLimitPct: Double?,
    /** Shu chastotadagi past xavf chegarasi, %. */
    val lowRiskLimitPct: Double?,
    /** Ishonch oralig'i chegaradan o'tadi — natija chegaraga yaqin. */
    val borderline: Boolean,
    /** O'lchov qamrovi, Hz (miltillash topilmaganda xulosa shu chastotagacha amal qiladi). */
    val coverageHz: Double? = null,
)

object Ieee1789 {

    fun noelLimitPct(f: Double): Double = when {
        f < 90.0 -> 0.01 * f
        f <= 3000.0 -> 0.0333 * f
        else -> Double.POSITIVE_INFINITY
    }

    fun lowRiskLimitPct(f: Double): Double = when {
        f < 90.0 -> 0.025 * f
        f <= 1250.0 -> 0.08 * f
        else -> Double.POSITIVE_INFINITY
    }

    fun classify(f: Double, modPct: Double): Ieee1789Level = when {
        modPct < noelLimitPct(f) -> Ieee1789Level.NO_OBSERVABLE_EFFECT
        modPct < lowRiskLimitPct(f) -> Ieee1789Level.LOW_RISK
        else -> Ieee1789Level.HIGH_RISK
    }

    /** "Miltillash yo'q" xulosasi uchun kerakli qamrov: NOEL cheklovi 3000 Hz gacha amal qiladi. */
    const val REQUIRED_COVERAGE_HZ = 3000.0

    /**
     * @param modHighPct modulyatsiya ishonch oralig'ining yuqori chegarasi (bo'lsa) —
     *   agar u boshqa zonaga tushsa, natija "chegaraga yaqin" deb belgilanadi.
     * @param fLowHz, fHighHz chastota ishonch oralig'i chegaralari (bo'lsa).
     * @param coverageHz o'lchov qamrovi; miltillash topilmasa va qamrov < 3000 Hz bo'lsa — INCONCLUSIVE.
     */

    fun assess(
        detected: Boolean,
        frequencyHz: Double?,
        modulationPct: Double?,
        modHighPct: Double? = null,
        fLowHz: Double? = null,
        fHighHz: Double? = null,
        coverageHz: Double? = null,
    ): Ieee1789Assessment {
        if (!detected || modulationPct == null) {
            val level = if (coverageHz != null && coverageHz >= REQUIRED_COVERAGE_HZ) Ieee1789Level.NO_FLICKER_DETECTED
            else Ieee1789Level.INCONCLUSIVE
            return Ieee1789Assessment(level, null, modulationPct, null, null, false, coverageHz)
        }
        if (frequencyHz == null) {
            return Ieee1789Assessment(Ieee1789Level.FREQUENCY_UNKNOWN, null, modulationPct, null, null, false)
        }
        val level = classify(frequencyHz, modulationPct)
        val alt = buildList {
            if (modHighPct != null) add(classify(frequencyHz, modHighPct))
            if (fLowHz != null) add(classify(fLowHz, modHighPct ?: modulationPct))
            if (fHighHz != null) add(classify(fHighHz, modulationPct))
        }
        return Ieee1789Assessment(
            level = level,
            frequencyHz = frequencyHz,
            modulationPct = modulationPct,
            noelLimitPct = noelLimitPct(frequencyHz),
            lowRiskLimitPct = lowRiskLimitPct(frequencyHz),
            borderline = alt.any { it != level },
            coverageHz = coverageHz,
        )
    }
}
