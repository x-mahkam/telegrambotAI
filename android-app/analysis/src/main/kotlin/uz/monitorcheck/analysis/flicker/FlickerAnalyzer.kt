package uz.monitorcheck.analysis.flicker

import uz.monitorcheck.analysis.dsp.Estimate
import uz.monitorcheck.analysis.dsp.Fft
import uz.monitorcheck.analysis.dsp.Signal
import uz.monitorcheck.analysis.dsp.Stats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** O'lchash sifati bo'yicha ogohlantirishlar (UI ularni matnga aylantiradi). */
enum class FlickerWarning {
    /** t_row noma'lum: chastota hisoblanmaydi, faqat "bor/yo'q" xulosasi. */
    NO_TIMING,
    /** Ekspozitsiya vaqti noma'lum: sinc tuzatish qo'llanmadi (modulyatsiya pastroq baholangan bo'lishi mumkin). */
    NO_EXPOSURE,
    /** Asosiy garmonika uchun sinc < 0.5: ekspozitsiya davrga nisbatan uzun, natija ishonchsiz. */
    LOW_SINC,
    /** Barcha kadrlarda faza bir xil: statik fazoviy naqsh (moiré) yoki kadrga sinxron miltillash. */
    STATIC_PHASE,
    /** Kadrlar bo'yicha chastota mos emas. */
    INCONSISTENT_FREQUENCY,
    /** Kadrda 2.5 sikldan kam — chastota aniqligi past. */
    FEW_CYCLES,
    /** Piksellarning sezilarli qismi to'yingan — modulyatsiya kamaygan bo'ladi. */
    SATURATED,
    /** Tasvir juda qorong'i — shovqin yuqori. */
    TOO_DARK,
    /** Kamera javobi chiziqli emas (sRGB taxmini) — modulyatsiya taxminiy. */
    NONLINEAR_RESPONSE,
    /** Kadrlar soni kam — ishonch oralig'i keng. */
    FEW_FRAMES,
}

/** Modulyatsiya qaysi model bo'yicha hisoblangan. */
enum class WaveformModel {
    /** Garmonikalar qatori + sinc tuzatish (ixtiyoriy shakl, masalan sinusoidal pulsatsiya). */
    HARMONIC,

    /** Ikki darajali to'g'ri burchakli PWM, ekspozitsiya integrali aniq hisobga olingan. */
    RECTANGULAR_PWM,
}

/** Bitta kadr tahlili natijasi. */
data class FrameResult(
    val detected: Boolean,
    /** Fazoviy chastota ν, sikl/qator. */
    val cyclesPerRow: Double,
    /** f = ν / t_row, Hz (t_row ma'lum bo'lsa). */
    val frequencyHz: Double?,
    /** Modulyatsiya chuqurligi, % — sinc tuzatish bilan (imkon bo'lsa). */
    val modulationPct: Double,
    /** Tuzatishsiz (kamera "ko'rgan") modulyatsiya, %. */
    val rawModulationPct: Double,
    val model: WaveformModel,
    /** PWM to'ldirish koeffitsienti (yuqori daraja ulushi), faqat RECTANGULAR_PWM da. */
    val dutyCycle: Double?,
    /** Asosiy garmonika uchun sinc(f·T_e) (null — hisoblab bo'lmadi). */
    val sincFundamental: Double?,
    val snrDb: Double,
    /** Asosiy garmonika fazasi 0-qatorda, radian (kadrlar orasidagi taqqoslash uchun). */
    val phaseRad: Double,
    val harmonicsUsed: Int,
    val cyclesInFrame: Double,
    /** Bu kadr uchun aniqlash chegarasi (modulyatsiya, %). */
    val detectionLimitPct: Double,
    /** Bir davr to'lqin shakli (nisbiy, o'rtacha = 1), 64 nuqta — grafik uchun. */
    val waveform: DoubleArray,
    val saturatedFraction: Double,
    val meanCode: Double,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Bir necha kadr bo'yicha yakuniy o'lchov. */
data class FlickerMeasurement(
    val detected: Boolean,
    val framesTotal: Int,
    val framesDetected: Int,
    val framesUsed: Int,
    val cyclesPerRow: Estimate?,
    val frequencyHz: Estimate?,
    val modulationPct: Estimate?,
    val rawModulationPct: Estimate?,
    /** PWM to'ldirish koeffitsienti (ko'pchilik kadrlarda PWM modeli tanlangan bo'lsa). */
    val dutyCycle: Estimate?,
    val model: WaveformModel?,
    /** Miltillash aniqlanmaganda: modulyatsiyaning yuqori chegarasi, %. */
    val detectionLimitPct: Double,
    val timing: RowTiming,
    val exposureSec: Double?,
    val phaseCoherence: Double?,
    val waveform: DoubleArray?,
    val warnings: Set<FlickerWarning>,
    /**
     * Ishonchli qamrov chegarasi, Hz: f ≤ coverageHz da |sinc(f·T_e)| ≥ 0.5, ya'ni miltillash
     * kamida yarmi saqlangan holda ko'rinadi (f_max = 0.6034/T_e), va Naykvist chegarasidan past.
     * Miltillash topilmasa, xulosa faqat shu chastotagacha amal qiladi.
     */
    val coverageHz: Double? = null,
)

/** |sinc(x)| = 0.5 bo'ladigan x (sin(πx)/(πx) = 0.5 tenglamasining yechimi). */
const val SINC_HALF_X = 0.6034

fun coverageHz(exposureSec: Double?, timing: RowTiming): Double? {
    if (exposureSec == null || exposureSec <= 0) return null
    val byExposure = SINC_HALF_X / exposureSec
    return timing.nyquistHz?.let { minOf(it, byExposure) } ?: byExposure
}

/**
 * Rolling shutter usulida miltillashni (PWM) o'lchash.
 *
 * Bitta kadr uchun algoritm:
 *  1. Profil o'rtachaga normallashtiriladi: y_r = P_r / ⟨P⟩.
 *  2. Sekin trend (linza vinyetkasi, ekranning notekisligi) 3-darajali Legendre
 *     ko'phadi bilan moslanadi va bo'linadi: r = y/T − 1.
 *  3. Hann oynasi + nollar bilan to'ldirish (×8) + FFT → [ν_min; ν_max] da eng baland
 *     cho'qqi. PWM uchun asosiy garmonika doim eng kuchli: |c_k| = |sin(πkD)|/(πk) ≤ |c_1|
 *     (chunki |sin(kx)| ≤ k·|sin x|).
 *  4. Cho'qqi parabolik interpolyatsiya, so'ng "oltin kesim" bilan aniqlanadi:
 *     trend + K garmonikali modelning qoldiq kvadratlari yig'indisi minimallashtiriladi.
 *  5. Garmonikalar koeffitsientlari (a_k, b_k) eng kichik kvadratlar bilan topiladi.
 *  6. Ekspozitsiya tuzatishi: har bir qator yorug'likni T_e davomida integrallaydi —
 *     bu kenglik T_e bo'lgan to'g'ri burchakli filtr; uning chastota javobi
 *        H(f) = sinc(f·T_e) = sin(π f T_e)/(π f T_e).
 *     Shuning uchun k-garmonika amplitudasi s_k = sinc(k·f·T_e) ga kamaygan; tuzatish:
 *        (a_k, b_k)_haqiqiy = (a_k, b_k)_o'lchangan / s_k   (faqat |s_k| ≥ 0.3 bo'lganda).
 *  7. To'lqin shakli Lanczos σ-faktorlari bilan qayta tiklanadi (Gibbs tebranishini
 *     kamaytirish uchun): σ_k = sinc(k/(K+1)).
 *  8. Modulyatsiya chuqurligi (IEEE 1789-2015, "percent flicker"):
 *        Mod% = (I_max − I_min)/(I_max + I_min) × 100.
 *
 * Kadrlar bo'yicha: medianadan ±3% dagi kadrlar olinadi, o'rtacha va Styudent bo'yicha
 * 95% ishonch oralig'i hisoblanadi; fazalar kogerentligi (R) statik naqshni ajratadi.
 */
class FlickerAnalyzer(private val config: Config = Config()) {

    data class Config(
        /** Kadrda kamida shuncha sikl bo'lishi kerak (past chastotalar trend bilan aralashadi). */
        val minCyclesPerFrame: Double = 1.5,
        /** Naykvist (0.5) dan pastroq yuqori chegara. */
        val maxCyclesPerRow: Double = 0.45,
        val trendDegree: Int = 3,
        val zeroPadFactor: Int = 8,
        /** Cho'qqi / spektr medianasi, dB. */
        val snrThresholdDb: Double = 15.0,
        /** Shundan kichik modulyatsiya "aniqlanmadi" hisoblanadi, %. */
        val minModulationPct: Double = 0.5,
        val maxHarmonics: Int = 10,
        /** Sinc tuzatish shundan kichik koeffitsientda qo'llanmaydi (shovqin kuchayadi). */
        val minSinc: Double = 0.3,
        val waveformPoints: Int = 64,
    )

    fun analyzeFrame(profile: RowProfile, timing: RowTiming, exposureSec: Double?): FrameResult =
        analyzeFrame(profile.values, timing, exposureSec, profile.saturatedFraction, profile.meanCode)

    fun analyzeFrame(
        values: DoubleArray,
        timing: RowTiming,
        exposureSec: Double?,
        saturatedFraction: Double = 0.0,
        meanCode: Double = Double.NaN,
    ): FrameResult {
        val n = values.size
        require(n >= 64) { "Profil juda qisqa: $n qator" }
        val mean = Stats.mean(values)
        if (!(mean > 0)) return empty(n, saturatedFraction, meanCode)
        val y = DoubleArray(n) { values[it] / mean }

        // 2) Trend va nisbiy tebranish
        val legendre = Signal.legendreBasis(n, config.trendDegree)
        val trendCoef = Signal.leastSquares(legendre, y)
        val trend = evalBasis(legendre, trendCoef, n)
        val r = DoubleArray(n) { y[it] / trend[it] - 1.0 }

        // 3) Spektr
        val win = Signal.hann(n)
        val m = Fft.nextPowerOfTwo(n) * config.zeroPadFactor
        val mag = Fft.magnitudeSpectrum(DoubleArray(n) { r[it] * win[it] }, m)
        val kMin = max(1, ceil(config.minCyclesPerFrame / n * m).toInt())
        val kMax = min(mag.size - 2, floor(config.maxCyclesPerRow * m).toInt())
        if (kMax <= kMin + 2) return empty(n, saturatedFraction, meanCode)
        var kPeak = kMin
        for (k in kMin..kMax) if (mag[k] > mag[kPeak]) kPeak = k
        val band = mag.copyOfRange(kMin, kMax + 1)
        val floorMag = Stats.median(band).coerceAtLeast(1e-300)
        val snrDb = 20 * log10(mag[kPeak] / floorMag)

        // Subgarmonika tekshiruvi (shovqinli holatlar uchun): ν/2 da kuchli cho'qqi bo'lsa — o'sha asosiy
        var k0 = kPeak
        val kHalf = kPeak / 2
        if (kHalf >= kMin) {
            var kh = kHalf
            for (k in max(kMin, kHalf - config.zeroPadFactor)..min(kMax, kHalf + config.zeroPadFactor)) {
                if (mag[k] > mag[kh]) kh = k
            }
            if (mag[kh] >= 0.5 * mag[kPeak]) k0 = kh
        }

        // 4) Aniqlashtirish: parabolik (log-amplituda bo'yicha) + oltin kesim
        val delta = if (k0 in 1 until mag.size - 1)
            Signal.parabolicPeakOffset(ln(mag[k0 - 1] + 1e-300), ln(mag[k0] + 1e-300), ln(mag[k0 + 1] + 1e-300))
        else 0.0
        val nu0 = (k0 + delta) / m
        val kRefine = harmonicsFor(nu0).coerceAtMost(8)
        val nu = goldenMin(nu0 - 2.0 / m, nu0 + 2.0 / m) { rss(y, legendre, it, kRefine) }

        // 5) Garmonikalar: trend (Legendre) va garmonikalar BIRGALIKDA moslanadi — aks holda
        //    kadrda sikllar kam bo'lganda ko'phad tebranishning bir qismini "yutib" yuboradi.
        val kHarm = harmonicsFor(nu)
        val fit = fitHarmonics(y, legendre, nu, kHarm)
        val a1 = fit.a[0]
        val b1 = fit.b[0]
        val amp1 = hypot(a1, b1)
        val phase = atan2(b1, a1)
        val cycles = nu * n

        // Aniqlash chegarasi: amplituda bahosining standart xatosi σ·√(2/n), 3σ mezoni
        val sigmaAmp = fit.residualStd * sqrt(2.0 / n)
        val detLimit = max(config.minModulationPct, 100.0 * 3.0 * sigmaAmp)

        // 6) Sinc tuzatish
        val f = timing.frequencyHz(nu)
        val sincs = DoubleArray(kHarm) { k ->
            if (f != null && exposureSec != null) Signal.sinc((k + 1) * f * exposureSec) else 1.0
        }
        val corrA = DoubleArray(kHarm)
        val corrB = DoubleArray(kHarm)
        var used = 0
        for (k in 0 until kHarm) {
            if (abs(sincs[k]) >= config.minSinc) {
                corrA[k] = fit.a[k] / sincs[k]
                corrB[k] = fit.b[k] / sincs[k]
                used++
            } else if (k == 0) {
                corrA[k] = fit.a[k]
                corrB[k] = fit.b[k]
            }
        }

        // 7–8) To'lqin shakli va modulyatsiya (garmonik model)
        val raw = modulation(fit.c, fit.a, fit.b)
        var corr = modulation(fit.c, corrA, corrB)
        var waveform = reconstruct(fit.c, corrA, corrB, config.waveformPoints, phase)
        var model = WaveformModel.HARMONIC
        var duty: Double? = null

        // 9) To'g'ri burchakli PWM modeli (T_e va t_row ma'lum bo'lsa). Ekspozitsiya yuqori
        //    garmonikalarni yo'qotgani uchun garmonik qatordan PWM tekisliklarini tiklab bo'lmaydi;
        //    to'g'ridan-to'g'ri fizik model esa ularni aniq beradi. Qoldiq garmonik modelnikidan
        //    sezilarli katta bo'lmasa (≤ 5%), PWM modeli tanlanadi.
        if (f != null && exposureSec != null && amp1 > 0) {
            val rect = fitRectangular(y, legendre, nu, f * exposureSec, phase)
            if (rect != null && rect.residualStd <= fit.residualStd * 1.05) {
                corr = rect.modulationPct
                duty = rect.duty
                model = WaveformModel.RECTANGULAR_PWM
                waveform = DoubleArray(config.waveformPoints) { i ->
                    // fundamental cho'qqisi 0-nuqtada bo'lishi uchun impuls markazga siljitilgan
                    val u = (i.toDouble() / config.waveformPoints + rect.duty / 2) % 1.0
                    if (u < rect.duty) rect.high else rect.low
                }.let { w -> val a = w.average(); DoubleArray(w.size) { w[it] / a } }
            }
        }

        val detected = snrDb >= config.snrThresholdDb &&
            100.0 * amp1 >= detLimit &&
            cycles >= config.minCyclesPerFrame

        return FrameResult(
            detected = detected,
            cyclesPerRow = nu,
            frequencyHz = f,
            modulationPct = corr,
            rawModulationPct = raw,
            model = model,
            dutyCycle = duty,
            sincFundamental = if (f != null && exposureSec != null) sincs[0] else null,
            snrDb = snrDb,
            phaseRad = phase,
            harmonicsUsed = max(used, 1),
            cyclesInFrame = cycles,
            detectionLimitPct = detLimit,
            waveform = waveform,
            saturatedFraction = saturatedFraction,
            meanCode = meanCode,
        )
    }

    /** Kadrlar natijalarini birlashtirish. */
    fun combine(frames: List<FrameResult>, timing: RowTiming, exposureSec: Double?, response: ResponseModel): FlickerMeasurement {
        val warnings = mutableSetOf<FlickerWarning>()
        if (timing.tRowSec == null) warnings += FlickerWarning.NO_TIMING
        if (exposureSec == null) warnings += FlickerWarning.NO_EXPOSURE
        if (response != ResponseModel.LINEAR) warnings += FlickerWarning.NONLINEAR_RESPONSE
        if (frames.size < 10) warnings += FlickerWarning.FEW_FRAMES
        if (frames.isNotEmpty() && Stats.median(frames.map { it.saturatedFraction }.toDoubleArray()) > 0.01) {
            warnings += FlickerWarning.SATURATED
        }
        val codes = frames.map { it.meanCode }.filter { !it.isNaN() }
        if (codes.isNotEmpty() && Stats.median(codes.toDoubleArray()) < 0.12) warnings += FlickerWarning.TOO_DARK

        val det = frames.filter { it.detected }
        val limit = if (frames.isEmpty()) Double.NaN else Stats.median(frames.map { it.detectionLimitPct }.toDoubleArray())
        if (frames.isEmpty() || det.size < max(2, (frames.size + 1) / 2)) {
            return FlickerMeasurement(false, frames.size, det.size, 0, null, null, null, null, null, null,
                limit, timing, exposureSec, null, null, warnings, coverageHz(exposureSec, timing))
        }

        val medNu = Stats.median(det.map { it.cyclesPerRow }.toDoubleArray())
        val kept = det.filter { abs(it.cyclesPerRow - medNu) / medNu <= 0.03 }
        if (kept.size < 0.6 * det.size) warnings += FlickerWarning.INCONSISTENT_FREQUENCY
        val use = kept.ifEmpty { det }

        val nuEst = Stats.estimate(use.map { it.cyclesPerRow }.toDoubleArray())
        val fEst = if (timing.tRowSec != null) Stats.estimate(use.map { it.cyclesPerRow / timing.tRowSec }.toDoubleArray()) else null
        val mEst = Stats.estimate(use.map { it.modulationPct }.toDoubleArray())
        val rawEst = Stats.estimate(use.map { it.rawModulationPct }.toDoubleArray())
        if (Stats.median(use.map { it.cyclesInFrame }.toDoubleArray()) < 2.5) warnings += FlickerWarning.FEW_CYCLES
        val sincs = use.mapNotNull { it.sincFundamental }
        if (sincs.isNotEmpty() && Stats.median(sincs.map { abs(it) }.toDoubleArray()) < 0.5) warnings += FlickerWarning.LOW_SINC

        // Fazalar kogerentligi: haqiqiy (vaqt bo'yicha) miltillash kamera kadrlariga sinxron emas,
        // shuning uchun 0-qatordagi faza kadrdan kadrga o'zgaradi (R kichik). Statik fazoviy
        // naqsh (masalan, piksel to'ri moiré) esa har kadrda bir xil fazada turadi (R ≈ 1).
        val coherence = if (use.size >= 5) Stats.circularResultantLength(use.map { it.phaseRad }.toDoubleArray()) else null
        if (coherence != null && coherence > 0.95) warnings += FlickerWarning.STATIC_PHASE

        // O'rtacha to'lqin shakli (fazalar moslangan holda saqlangan)
        val wf = DoubleArray(config.waveformPoints)
        for (fr in use) for (i in wf.indices) wf[i] += fr.waveform[i] / use.size

        val rectFrames = use.filter { it.model == WaveformModel.RECTANGULAR_PWM }
        val model = if (rectFrames.size * 2 > use.size) WaveformModel.RECTANGULAR_PWM else WaveformModel.HARMONIC
        val dutyEst = if (model == WaveformModel.RECTANGULAR_PWM)
            Stats.estimate(rectFrames.mapNotNull { it.dutyCycle }.toDoubleArray()) else null

        return FlickerMeasurement(true, frames.size, det.size, use.size, nuEst, fEst, mEst, rawEst, dutyEst, model,
            limit, timing, exposureSec, coherence, wf, warnings, coverageHz(exposureSec, timing))
    }

    // ---------------------------------------------------------------------------------

    private class HarmonicFit(val c: Double, val a: DoubleArray, val b: DoubleArray, val residualStd: Double)

    private fun harmonicsFor(nu: Double): Int =
        max(1, min(config.maxHarmonics, floor(0.49 / nu).toInt()))

    private fun harmonicColumns(trend: List<DoubleArray>, n: Int, nu: Double, k: Int): List<DoubleArray> {
        val cols = ArrayList<DoubleArray>(trend.size + 2 * k)
        cols += trend
        for (h in 1..k) {
            val w = 2 * PI * nu * h
            cols += DoubleArray(n) { cos(w * it) }
            cols += DoubleArray(n) { sin(w * it) }
        }
        return cols
    }

    /**
     * y ≈ Σ_j t_j·P_j(x) + Σ_h [a_h·cos(2πhνr) + b_h·sin(2πhνr)].
     * Natijaviy a_h, b_h trendning o'rtacha darajasiga (t_0, P_0 koeffitsienti) bo'linadi —
     * ya'ni nisbiy amplitudalar (o'rtacha yorug'lik = 1).
     */
    private fun fitHarmonics(y: DoubleArray, trend: List<DoubleArray>, nu: Double, k: Int): HarmonicFit {
        val n = y.size
        val cols = harmonicColumns(trend, n, nu, k)
        val coef = Signal.leastSquares(cols, y)
        var ss = 0.0
        for (i in 0 until n) {
            var v = 0.0
            for (j in cols.indices) v += coef[j] * cols[j][i]
            val e = y[i] - v
            ss += e * e
        }
        val dof = max(1, n - cols.size)
        val level = coef[0].takeIf { it > 0 } ?: 1.0
        val t = trend.size
        return HarmonicFit(
            c = 0.0,
            a = DoubleArray(k) { coef[t + 2 * it] / level },
            b = DoubleArray(k) { coef[t + 1 + 2 * it] / level },
            residualStd = sqrt(ss / dof) / level,
        )
    }

    private fun rss(y: DoubleArray, trend: List<DoubleArray>, nu: Double, k: Int): Double {
        if (nu <= 0) return Double.MAX_VALUE
        return fitHarmonics(y, trend, nu, k).residualStd
    }

    /** Oltin kesim usuli bilan [lo; hi] da unimodal funksiya minimumi. */
    private fun goldenMin(lo0: Double, hi0: Double, f: (Double) -> Double): Double {
        val g = (sqrt(5.0) - 1) / 2
        var lo = lo0
        var hi = hi0
        var x1 = hi - g * (hi - lo)
        var x2 = lo + g * (hi - lo)
        var f1 = f(x1)
        var f2 = f(x2)
        repeat(40) {
            if (f1 < f2) {
                hi = x2; x2 = x1; f2 = f1
                x1 = hi - g * (hi - lo); f1 = f(x1)
            } else {
                lo = x1; x1 = x2; f1 = f2
                x2 = lo + g * (hi - lo); f2 = f(x2)
            }
        }
        return 0.5 * (lo + hi)
    }

    /** Lanczos σ-faktorlari bilan qayta tiklangan to'lqin shakli bo'yicha Mod%. */
    private fun modulation(c: Double, a: DoubleArray, b: DoubleArray): Double {
        val g = 512
        val k = a.size
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        for (i in 0 until g) {
            val th = 2 * PI * i / g
            var v = 1.0 + c
            for (h in 1..k) {
                val s = Signal.sinc(h.toDouble() / (k + 1))
                v += s * (a[h - 1] * cos(h * th) + b[h - 1] * sin(h * th))
            }
            mx = max(mx, v)
            mn = min(mn, v)
        }
        mn = max(0.0, mn)                       // yorug'lik manfiy bo'lmaydi
        return if (mx + mn > 0) 100.0 * (mx - mn) / (mx + mn) else 0.0
    }

    /** Bir davr to'lqin shakli; boshlanish fazasi asosiy garmonika fazasiga moslangan, o'rtacha 1 ga normallangan. */
    private fun reconstruct(c: Double, a: DoubleArray, b: DoubleArray, points: Int, phase: Double): DoubleArray {
        val k = a.size
        val out = DoubleArray(points) { i ->
            val th = 2 * PI * i / points + phase
            var v = 1.0 + c
            for (h in 1..k) {
                val s = Signal.sinc(h.toDouble() / (k + 1))
                v += s * (a[h - 1] * cos(h * th) + b[h - 1] * sin(h * th))
            }
            max(0.0, v)
        }
        val avg = out.average()
        return if (avg > 0) DoubleArray(points) { out[it] / avg } else out
    }

    private class RectFit(val duty: Double, val high: Double, val low: Double, val residualStd: Double) {
        val modulationPct: Double
            get() {
                val lo = max(0.0, low)
                return if (high + lo > 0) 100.0 * (high - lo) / (high + lo) else 0.0
            }
    }

    /**
     * To'g'ri burchakli PWM modeli bilan moslash.
     *
     * Yorug'lik fazasi u (sikllarda) bo'yicha: u mod 1 < D da yuqori daraja, aks holda past.
     * 0 dan x gacha "yuqori" holatdagi umumiy vaqt (sikllarda):
     *     G(x) = ⌊x⌋·D + min(x − ⌊x⌋, D).
     * Qator yorug'likni w = f·T_e sikl davomida integrallaydi, demak normallangan signal
     *     s_r = [G(φ_r + w) − G(φ_r)] / w,   φ_r = ν·r + φ₀.
     * Model: y_r = Σ t_j·P_j(x_r) + Δ·(s_r − D) — (t_j, Δ) bo'yicha chiziqli; D bo'yicha
     * to'r + oltin kesim qidiruvi. Boshlang'ich faza φ₀ asosiy garmonika fazasidan topiladi:
     * s ning 1-garmonikasi cos(2π(φ + w/2 − D/2))·sinc(w) ga proporsional.
     */
    private fun fitRectangular(y: DoubleArray, trend: List<DoubleArray>, nu: Double, w: Double, theta1: Double): RectFit? {
        val n = y.size
        val signFlip = if (Signal.sinc(w) < 0) 0.5 else 0.0
        fun phi0(d: Double) = -theta1 / (2 * PI) - w / 2 + d / 2 + signFlip

        fun fitFor(d: Double, dPhi: Double): RectFit {
            val p0 = phi0(d) + dPhi
            fun g(x: Double): Double { val fl = floor(x); return fl * d + min(x - fl, d) }
            val s = DoubleArray(n) { r ->
                val ph = nu * r + p0
                if (w > 1e-6) (g(ph + w) - g(ph)) / w - d else (if (ph - floor(ph) < d) 1.0 - d else -d)
            }
            val cols = trend + listOf(s)
            val c = Signal.leastSquares(cols, y)
            var ss = 0.0
            for (i in 0 until n) {
                var v = 0.0
                for (j in cols.indices) v += c[j] * cols[j][i]
                ss += (y[i] - v) * (y[i] - v)
            }
            val level = c[0].takeIf { it > 0 } ?: 1.0
            val delta = c[trend.size]
            val hi = level + delta * (1 - d)
            val lo = level - delta * d
            return RectFit(d, max(hi, lo) / level, min(hi, lo) / level, sqrt(ss / max(1, n - cols.size)) / level)
        }

        var best: RectFit? = null
        var bestD = 0.5
        var d = 0.02
        while (d <= 0.98 + 1e-9) {
            val r = fitFor(d, 0.0)
            if (best == null || r.residualStd < best.residualStd) { best = r; bestD = d }
            d += 0.02
        }
        val dRef = goldenMin(max(0.01, bestD - 0.02), min(0.99, bestD + 0.02)) { fitFor(it, 0.0).residualStd }
        // Fazani biroz aniqlashtirish (garmonik faza shovqinli bo'lishi mumkin)
        var out = fitFor(dRef, 0.0)
        for (k in -4..4) {
            if (k == 0) continue
            val r = fitFor(dRef, k * 0.0025)
            if (r.residualStd < out.residualStd) out = r
        }
        return out
    }

    private fun evalBasis(basis: List<DoubleArray>, coef: DoubleArray, n: Int): DoubleArray =
        DoubleArray(n) { i -> basis.indices.sumOf { coef[it] * basis[it][i] } }

    private fun empty(n: Int, sat: Double, code: Double) = FrameResult(
        false, 0.0, null, 0.0, 0.0, WaveformModel.HARMONIC, null, null, 0.0, 0.0, 0, 0.0, 100.0,
        DoubleArray(config.waveformPoints) { 1.0 }, sat, code,
    )
}
