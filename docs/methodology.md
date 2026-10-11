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
* **Qamrov.** Ekspozitsiya T_e bo'lsa, ishonchli tekshiriladigan chastotalar
  f ≤ 0.6034/T_e (|sinc| ≥ 0.5). "Miltillash yo'q" xulosasi faqat qamrov ≥ 3000 Hz
  (NOEL chegarasi) bo'lganda beriladi; aks holda natija "noaniq". Masalan, avtomatik
  rejimda 1/100 s ekspozitsiyada qamrov atigi ~60 Hz.
* **Qo'lda ekspozitsiya tekshiruvi.** Ilova so'ralgan T_e ni kadr natijasidagi haqiqiy
  `SENSOR_EXPOSURE_TIME` bilan solishtiradi (±30%); bajarilmasa — avtomatik rejimga o'tadi.
* Natija laboratoriya fotometri / ossillograf bilan o'lchovning o'rnini bosmaydi.
* Chegaraviy holatlarda ekspert tekshiruvi tavsiya etiladi.

---

## 2. EDID

[`edid-schema.md`](edid-schema.md) va `edid-reader/README.md` ga qarang.
EDID — monitor proshivkasidagi o'z-o'zi haqidagi ma'lumot; u panel turini, haqiqiy
rang chuqurligini (6-bit+FRC) yoki haqiqiy rang qamrovini kafolatlamaydi.

## 3. Piksellar, bir tekislik, chet sizishi, rang chuqurligi

### 3.1. Geometriya (perspektivani tuzatish)

* Test sahifasi har bir rasm burchagiga ArUco DICT_4X4_50 markerini chizadi
  ([`marker-spec.md`](marker-spec.md)). Ilova ularni o'zining detektori bilan topadi
  (OpenCV'siz): moslashuvchan bo'sag'a → bog'liq sohalar → qavariq qobiq → to'rtburchak →
  6×6 katakni gomografiya bilan o'qish → lug'at bilan solishtirish (4 burilish).
* Burchaklar subpiksel aniqlikda: har bir tomon bo'ylab 11 ta kesimda qora↔oq o'tishning
  50% nuqtasi, chiziq (umumiy eng kichik kvadratlar), kesishma. Muhim: bu **chiziqli**
  yorqinlikda bajariladi — kamera xiraligi chiziqli yorug'likda sodir bo'ladi, gamma-kodlangan
  qiymatlarda 50% nuqta ~0.5–1 px siljiydi.
* Ekran: 4 marker × 4 burchak = 16 nuqta. Noma'lumlar — gomografiya H (8) va ekran
  o'lchami kataklarda (W_c, H_c); qayta proyeksiya xatosi W_c, H_c bo'yicha minimallashtiriladi
  (normallashtirilgan DLT, Hartley & Zisserman, 2004). Natija — ekranning haqiqiy tomonlar
  nisbati va har bir tasvir nuqtasining ekrandagi joyi.
* Tekshirilgan aniqlik: sintetik suratlarda marker burchagi < 0.6 px, ekran burchagi < 3 px;
  Chromium'da chizilgan haqiqiy test sahifasi (perspektiva + xiralik + shovqin + JPEG)
  suratlarida barcha markerlar va ekran burchaklari to'g'ri topilgan.

### 3.2. Yorqinlik bir tekisligi (1- va 6-testlar)

9 nuqtali usul (VESA FPDM 2.0, 306-1; ISO 9241-305): zonalar markazlari (1/6, 1/2, 5/6)
atrofida ekranning ±5% li yamoqlarida chiziqli yorqinlikning kesilgan o'rtachasi.

```
U = L_min / L_max × 100 %,     zona / markaz × 100 %
```

Ichki baho: U ≥ 80% — yaxshi; 70–80% — qoniqarli; < 70% — past.
Cheklovlar: telefon linzasi vinyetkasi (ISP qisman tuzatadi), LCD yorqinligining qarash
burchagiga bog'liqligi (ilova burchaklarga qarash burchagini pinhole model bo'yicha hisoblaydi,
> 25° bo'lsa ogohlantiradi).

### 3.3. Piksel nuqsonlari (1–5-testlar)

Fon — har bir kanal uchun blok medianalari (blok ≈ ekran kengligining 1/48 i) + bilinear
interpolyatsiya. Og'ish: yorqin fonda `max_c |I_c − B_c| / B_Y`, qora fonda `Y − B_Y`.
Shovqin σ = 1.4826·MAD; nomzod: og'ish > max(6σ, 22%) (qora fonda > max(10σ, 0.01)).
Nomzodlar 8-qo'shnilik bo'yicha guruhlanadi; o'lcham yarim maksimum bo'yicha
(yorug' nuqtaning "yoyilishi" hisobga olinmasligi uchun). Tasnif: ≤ 9 ekran pikseli —
piksel nuqsoni (o'lik / yonib turgan / subpiksel), kattasi — dog' (chang, mura).

Sinf — ISO 9241-307:2008, 1 million pikselga: 0 sinf: 0/0/0; I: 1/1/2; II: 2/2/5;
III: 5/15/50 (1-tur yonib turgan, 2-tur o'lik, 3-tur subpiksel). Bir joydagi nuqson turli
testlarda bir marta hisoblanadi. Ekran pikseli kamerada < 1.2 px bo'lsa ogohlantiriladi.

### 3.4. Chetlardan yorug'lik sizishi (7-test)

Ikkita surat: qisqa ekspozitsiya (−2 EV) — markerlar va geometriya; uzun (+1 EV) — tahlil.
12×8 katakli xarita, markaz = 1; baho — chet kataklaridagi eng katta nisbat:
< 1.5 sezilarli emas; 1.5–3 o'rtacha; > 3 kuchli (ichki mezon).

### 3.5. Rang chuqurligi (9-test)

Ikki polosa (0→63, 192→255), har biri 64 katak. Katak o'rtachasi m_k, standart xato se_k;
o'sish ahamiyatli: `d_k > 4·√(se_k² + se_{k+1}²)` va `d_k > 0.25·⟨d⟩`. 8-bit panelda
64 pog'ona, 6-bitda (FRC'siz) 4 tadan birlashib 16 pog'ona; bunda o'sishlar 4 ga karrali
joylarda to'planadi. 6-bit+FRC panel vaqt bo'yicha aralashtirish tufayli 8-bit kabi
ko'rinadi — buni kamera bilan ajratib bo'lmaydi.

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
