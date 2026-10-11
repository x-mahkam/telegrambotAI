# O'lchash metodikasi

Bu hujjat tizimdagi har bir o'lchovning fizik asosini, formulalarini, manbalarini
va cheklovlarini tavsiflaydi. PDF hisobotdagi "Usul cheklovlari" bo'limi shu yerdan olinadi.

> Barcha natijalar **ergonomik ko'rsatkichlar va standartlarga muvofiqlik** haqida
> ma'lumot beradi. Tizim tibbiy ta'sirni aniqlamaydi va tibbiy xulosa bermaydi.

---

## 1. Miltillash (PWM) — rolling shutter usuli

### 1.1. Fizik model

Telefon CMOS sensori kadrni qatorma-qator o'qiydi (rolling shutter): r-qator
ekspozitsiyasi `t_r = t_0 + r·t_row` paytda boshlanadi va `T_e` davom etadi.
Qator signali:

```
P_r = V(r) · (1/T_e) ∫_{t_r}^{t_r+T_e} L(t) dt
```

bu yerda `L(t)` — ekran yorqinligi, `V(r)` — sekin o'zgaruvchi omillar (vinyetka,
ekranning notekisligi). Agar `L(t)` chastotasi `f` bilan davriy bo'lsa, profil
fazoviy chastota bilan davriy bo'ladi:

```
ν = f · t_row   [sikl/qator]      ⇒      f = ν / t_row
```

`t_row` manbalari:

| Manba | Formula | Izoh |
|-------|---------|------|
| Sensor ma'lumoti | `t_row = skew / (H − 1)` | `CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW` — birinchi va oxirgi qator ekspozitsiyasi boshlanishi orasidagi vaqt; `H` — tasvir qatorlari soni. Tasvir sensor nisbatida tanlanadi, shunda u butun o'qish oralig'ini qamraydi. |
| Kalibrovka | `t_row = ν_lampa / f_ref` | 50 Hz tarmoqdagi lampa yorug'ligi 100 Hz da tebranadi (quvvat ∝ sin²). O'lchangan `ν` dan `t_row` topiladi va qurilmada saqlanadi. Skew bilan solishtirish uning to'g'riligini tekshiradi. |
| Noma'lum | — | Chastota hisoblanmaydi; faqat "miltillash bor/yo'q" va modulyatsiya (tuzatishsiz). |

### 1.2. Kamera sozlamalari

* Qo'lda ekspozitsiya, maqsad `T_e = 1/8000 s` (qurilma oralig'iga keltiriladi), eng ko'pi `1/2000 s`.
* ISO avtomatik tanlanadi: o'rtacha kod ≈ 45%, to'yinish < 0.3%.
* Fokus cheksizlikda: 5–15 sm dagi ekran xiralashadi, piksel to'ri (moiré) yo'qoladi.
* Chiziqli tonemap (`TONEMAP_MODE_CONTRAST_CURVE`, egri chiziq (0,0)–(1,1)); bo'lmasa
  sRGB teskari funksiyasi qo'llanadi (IEC 61966-2-1) va natija "taxminiy" deb belgilanadi.
* Shovqin kamaytirish va konturlarni kuchaytirish o'chiriladi; antibanding o'chiriladi.
* Profil Y tekisligidan, ustunlarning markaziy 50% qismi bo'yicha, har bir piksel
  chiziqlashtirilgandan **keyin** o'rtachalanadi.

### 1.3. Bitta kadr tahlili

1. Normallashtirish: `y_r = P_r / ⟨P⟩`.
2. Spektr: 3-darajali Legendre trendiga bo'lingan signal × Hann oynasi
   (Harris, 1978), ×8 nollar bilan to'ldirish, radix-2 FFT (Cooley–Tukey, 1965).
   Qidiruv oralig'i: kadrda ≥ 1.5 sikl … 0.45 sikl/qator.
3. Cho'qqi: log-amplituda bo'yicha parabolik interpolyatsiya (J. O. Smith, 2011),
   so'ng oltin kesim usuli bilan model qoldig'ini minimallashtirish.
4. **Birgalikdagi moslash** (eng kichik kvadratlar):
   `y_r ≈ Σ_j t_j·P_j(x_r) + Σ_h [a_h cos(2πhνr) + b_h sin(2πhνr)]`.
   Trend va garmonikalar birga baholanadi — kadrda sikllar kam bo'lganda ko'phad
   tebranishni "yutib yubormasligi" uchun.
5. **Ekspozitsiya tuzatishi.** `T_e` davomida integrallash — kengligi `T_e` bo'lgan
   to'g'ri burchakli filtr. Uning chastota javobi:
   ```
   H(f) = sinc(f·T_e) = sin(π f T_e) / (π f T_e)
   ```
   k-garmonika `s_k = sinc(k·f·T_e)` marta susaygan; tuzatish `(a_k, b_k)/s_k`
   (faqat `|s_k| ≥ 0.3` da, aks holda shovqin kuchayadi).
6. **To'g'ri burchakli PWM modeli.** Ekspozitsiya yuqori garmonikalarni yo'q qilgani
   uchun ular orqali PWM tekisliklarini tiklab bo'lmaydi. Shuning uchun fizik model
   ham moslanadi: "yuqori" holatning to'plangan vaqti
   `G(x) = ⌊x⌋·D + min(x − ⌊x⌋, D)` (x — sikllarda), qator signali
   `s_r = [G(φ_r + w) − G(φ_r)] / w`, `w = f·T_e`, `φ_r = ν r + φ_0`.
   `D` (to'ldirish koeffitsienti) bo'yicha qidiruv, qolgan parametrlar chiziqli.
   Qoldiq garmonik modelnikidan ≤ 5% katta bo'lsa — PWM modeli tanlanadi.
7. **Modulyatsiya chuqurligi** (IEEE 1789-2015, "percent flicker"):
   ```
   Mod% = (L_max − L_min) / (L_max + L_min) × 100
   ```
   Garmonik modelda to'lqin Lanczos σ-faktorlari (`σ_k = sinc(k/(K+1))`) bilan
   tiklanadi (Gibbs tebranishini kamaytirish uchun).
8. **Aniqlash mezoni:** cho'qqi/spektr medianasi ≥ 15 dB, asosiy garmonika
   amplitudasi ≥ max(0.5%, 3σ_A), `σ_A = σ_qoldiq·√(2/N)`; kadrda ≥ 1.5 sikl.

### 1.4. Bir necha kadr bo'yicha

* 24 kadr; aniqlangan kadrlar ichidan medianadan ±3% dagi `ν` lar olinadi.
* Natija: o'rtacha ± 95% ishonch oralig'i, `t_{0.975, n−1}·s/√n` (Styudent).
* **Moiré nazorati.** Haqiqiy miltillash kamera kadrlariga sinxron emas — 0-qatordagi
  faza kadrdan kadrga o'zgaradi. Statik fazoviy naqshda faza o'zgarmaydi. Fazalar
  kogerentligi `R = |Σ e^{iφ_k}| / n` (Mardia & Jupp, 2000) > 0.95 bo'lsa ogohlantiriladi.
* **Ko'r nuqtalar.** `f ≈ k/T_e` da `sinc = 0` — miltillash ko'rinmaydi. Shuning uchun
  birinchi o'tishda miltillash topilmasa, ikkinchi o'tish boshqa ekspozitsiyada
  (≈0.6× yoki 1.6×) bajariladi.

### 1.5. IEEE 1789-2015 bo'yicha baholash

IEEE Std 1789-2015, 8.1-bo'lim (Recommended Practices):

| Zona | f < 90 Hz | 90 Hz ≤ f ≤ 1250 Hz | 1250 < f ≤ 3000 Hz | f > 3000 Hz |
|------|-----------|---------------------|--------------------|-------------|
| Ta'sir kuzatilmaydi (NOEL) | Mod% < 0.01·f | Mod% < 0.0333·f | Mod% < 0.0333·f | cheklov yo'q |
| Past xavf | Mod% < 0.025·f | Mod% < 0.08·f | cheklov yo'q | cheklov yo'q |

Natija ishonch oralig'i qo'shni zonaga o'tsa — "chegaraga yaqin, ekspert tekshiruvi
tavsiya etiladi".

### 1.6. Tekshirilgan aniqlik (simulyatsiya)

`analysis` modulidagi fizik simulyator (rolling shutter + ekspozitsiya integrali +
vinyetka + Gauss shovqini) bo'yicha Monte-Karlo, 200 tasodifiy PWM holati
(f = 150 Hz…20 kHz, D = 0.1…0.9, Mod = 10…100%, T_e = 1/20000…1/4000 s,
t_row = 5…20 µs, shovqin 0.1…0.5%), **bitta kadr**:

| Ko'rsatkich | Median xato | 95% holatda |
|-------------|-------------|-------------|
| Chastota | 0.001% | < 0.06% |
| Modulyatsiya | 0.05 p.p. | < 0.6 p.p. |

Haqiqiy qurilmada asosiy xato manbasi — `t_row` (skew ma'lumotining aniqligi yoki
kalibrovka), kamera javobining chiziqli emasligi va tashqi yorug'lik.

### 1.7. Cheklovlar

* O'lchanadigan eng yuqori chastota ≈ `1/(2·t_row)` (odatda 25–50 kHz);
  amalda `f ≲ 1/(2·T_e)` gacha ishonchli.
* Kadrda < 1.5 sikl bo'lsa (f ≲ 1.5/skew, odatda < 60–100 Hz) chastota aniqlanmaydi.
* Natija laboratoriya fotometri / ossillograf bilan o'lchovning o'rnini bosmaydi.
* Chegaraviy holatlarda ekspert tekshiruvi tavsiya etiladi.

---

## 2. EDID

[`edid-schema.md`](edid-schema.md) va `edid-reader/README.md` ga qarang.
EDID — monitor proshivkasidagi o'z-o'zi haqidagi ma'lumot; u panel turini, haqiqiy
rang chuqurligini (6-bit+FRC) yoki haqiqiy rang qamrovini kafolatlamaydi.

## 3. O'lik piksellar, bir tekislik, banding — 4-bosqichda

## 4. Spektr — 6-bosqichda

## Manbalar

1. IEEE Std 1789-2015, *IEEE Recommended Practices for Modulating Current in High-Brightness LEDs for Mitigating Health Risks to Viewers*.
2. J. W. Cooley, J. W. Tukey, *Math. Comp.* 19 (1965) 297–301.
3. F. J. Harris, "On the use of windows for harmonic analysis with the DFT", *Proc. IEEE* 66 (1978) 51–83.
4. J. O. Smith III, *Spectral Audio Signal Processing*, W3K Publishing, 2011.
5. K. V. Mardia, P. E. Jupp, *Directional Statistics*, Wiley, 2000.
6. C.-K. Liang, L.-W. Chang, H. H. Chen, "Analysis and compensation of rolling shutter effect", *IEEE Trans. Image Process.* 17 (2008) 1323–1330.
7. IEC 61966-2-1:1999, *Default RGB colour space — sRGB*.
8. Android Developers, `CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW`, `CaptureRequest.TONEMAP_CURVE`.
9. VESA, *Enhanced EDID Standard*, Release A Rev. 2 (2006); CTA-861-G (2017).
