# MonitorCheck — umumiy arxitektura

Davlat xaridlarida (birja orqali) yetkazib berilgan kompyuter monitorlarining
**ergonomik ko'rsatkichlari va standartlarga muvofiqligini** tekshirish tizimi.

> Tizim tibbiy diagnostika vositasi emas. U ekranning o'lchanadigan fizik
> ko'rsatkichlarini (miltillash, bir tekislik, rang chuqurligi va h.k.)
> e'lon qilingan tavsif va ochiq standartlar (IEEE 1789-2015, VESA, ISO 9241-30x)
> bilan solishtiradi, xolos.

## 1. Komponentlar va ma'lumot oqimi

```
 ┌──────────────── Tekshirilayotgan kompyuter ────────────────┐
 │                                                            │
 │  [1] test-page/monitor-test.html      [2] edid-reader.exe  │
 │      (brauzer, F11)                       (Windows reyestri)│
 │      test rasmlari + ArUco markerlar      EDID → JSON + QR  │
 └───────────────┬──────────────────────────────────┬─────────┘
                 │ kamera (rasm / video)            │ QR-kod (ekranda)
                 ▼                                  ▼
 ┌──────────────────────── [3] Android ilova ─────────────────┐
 │  ui/ (Compose)  ──►  camera/ (Camera2)  ──►  analysis/     │
 │                                              (sof Kotlin,  │
 │                                               unit testlar)│
 │  report/ (PDF)  ◄──  data/ (natijalar, sessiya)  ◄─────────┘
 └────────────────────────────────────────────────────────────┘
                 │
                 ▼
          PDF hisobot (offline, telefonda)
```

Hamma hisob-kitob telefonda bajariladi, internet talab qilinmaydi.

### Komponentlar o'rtasidagi "shartnomalar"

| Shartnoma | Kim yozadi | Kim o'qiydi | Hujjat |
|-----------|-----------|-------------|--------|
| Test ID ↔ ArUco marker ID | test sahifasi | Android `markers` moduli | [`marker-spec.md`](marker-spec.md) |
| EDID JSON sxemasi (QR ichida) | EDID o'quvchi | Android `edid` moduli | `edid-schema.md` (2-bosqich) |

## 2. Fayl tuzilishi (rejalashtirilgan)

```
telegrambotAI/
├── README.md
├── docs/
│   ├── ARCHITECTURE.md          ← shu hujjat
│   ├── marker-spec.md           ← test/marker protokoli (1-bosqich)
│   ├── edid-schema.md           ← EDID JSON / QR formati (2-bosqich)
│   └── methodology.md           ← fizik usullar, formulalar, cheklovlar
│
├── test-page/                   ── 1-bosqich
│   ├── monitor-test.html        ← yagona mustaqil fayl (o'rnatishsiz)
│   └── tools/
│       └── verify_markers.py    ← avtomatik tekshiruv (Chromium + OpenCV)
│
├── edid-reader/                 ── 2-bosqich (Python → PyInstaller .exe)
│   ├── edid_reader/
│   │   ├── __main__.py          ← CLI / oddiy GUI
│   │   ├── registry.py          ← winreg orqali EDID o'qish
│   │   ├── decoder.py           ← EDID 1.3/1.4 + CTA-861 dekoder (OS'ga bog'liq emas)
│   │   ├── pnp_ids.py           ← PNP ishlab chiqaruvchi kodlari jadvali
│   │   ├── compare.py           ← shartnoma modeli bilan solishtirish
│   │   └── export.py            ← JSON + QR
│   ├── tests/                   ← pytest (namuna EDID binarlari bilan)
│   └── build_exe.bat
│
└── android-app/                 ── 3–6-bosqichlar
    ├── settings.gradle.kts
    ├── app/src/main/java/uz/monitorcheck/
    │   ├── MainActivity.kt
    │   ├── ui/                  ← Compose ekranlar (har modulga alohida)
    │   ├── camera/              ← Camera2: qo'lda ekspozitsiya, RAW, metadata
    │   ├── analysis/            ← SOF HISOB-KITOB (Android API'siz)
    │   │   ├── dsp/             ← FFT, oyna funksiyalari, statistika
    │   │   ├── flicker/         ← qator profili, chastota, modulyatsiya, sinc tuzatish
    │   │   ├── standards/       ← IEEE 1789-2015 baholash
    │   │   ├── geometry/        ← ArUco, gomografiya (perspektiva tuzatish)
    │   │   ├── pixels/          ← o'lik piksel, 3×3 uniformity, banding
    │   │   └── spectrum/        ← panjara kalibrovkasi, spektr (6-bosqich)
    │   ├── edid/                ← QR skaner + JSON parse
    │   ├── report/              ← PDF generatsiya (android.graphics.pdf)
    │   └── data/                ← sessiya modeli, saqlash
    ├── app/src/main/res/
    │   ├── values/strings.xml       ← o'zbek (lotin) — standart
    │   └── values-ru/strings.xml    ← rus (keyinchalik)
    └── app/src/test/            ← JVM unit testlar (analysis/ uchun)
```

**Asosiy tamoyil:** `analysis/` paketi faqat sof Kotlin (`DoubleArray`, `FloatArray`)
bilan ishlaydi, Android sinflariga bog'lanmaydi. Shuning uchun FFT, IEEE 1789
baholash, sinc tuzatish va boshqalar kompyuterda oddiy JUnit bilan, sintetik
signallarda (ma'lum chastota/modulyatsiya) tekshiriladi.

## 3. Bosqichlar

| # | Bosqich | Natija |
|---|---------|--------|
| 1 | Test sahifasi (HTML) | `test-page/monitor-test.html`, `docs/marker-spec.md` |
| 2 | EDID o'quvchi (Python) | `edid-reader/`, `.exe`, JSON + QR |
| 3 | Android karkas + flicker moduli | Camera2 qo'lda rejim, FFT, IEEE 1789 |
| 4 | O'lik piksel va uniformity | perspektiva tuzatish, 3×3 zonalar, banding |
| 5 | PDF hisobot | yagona hisobot, umumiy xulosa, cheklovlar |
| 6 | Spektr moduli (ixtiyoriy) | difraksion panjara, Hg kalibrovka, nisbiy spektr |

Har bosqich oxirida sinov yo'riqnomasi beriladi va keyingisiga faqat tasdiqdan
so'ng o'tiladi.
