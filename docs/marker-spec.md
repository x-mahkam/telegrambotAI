# Test sahifasi ↔ telefon ilovasi: marker protokoli (v1)

Test sahifasi (`test-page/monitor-test.html`) har bir test rasmining **to'rtta
burchagiga** kichik ArUco marker chizadi. Telefon ilovasi ulardan:

1. **Qaysi test ko'rsatilayotganini** aniqlaydi (marker ID orqali);
2. **Ekranning to'rt burchagini** topadi va perspektivani tuzatadi (gomografiya).

## Lug'at

* OpenCV `DICT_4X4_50` (4×4 ma'lumot biti + 1 katak qora chegara = 6×6 katak).
* Marker atrofida **1 katak oq "sokin zona"** (quiet zone) bor → jami 8×8 katak.
* Marker hech qachon aylantirilmaydi (doim "tik" holatda chiziladi).

## ID formulasi

```
markerId = (testId − 1) × 4 + burchak
burchak:   0 = yuqori-chap, 1 = yuqori-o'ng, 2 = pastki-o'ng, 3 = pastki-chap
testId  = markerId / 4 + 1   (butun bo'lish)
burchak = markerId mod 4
```

Bitta markerning o'zi testni aniqlash uchun yetarli; to'rttasi ham ko'rinsa,
perspektiva tuzatiladi.

| testId | Kod | Rasm | Asosiy maqsad | Marker ID'lar |
|-------:|-----|------|---------------|---------------|
| 1 | `WHITE` | Oq (255,255,255) | o'lik (qora) piksellar, uniformity | 0–3 |
| 2 | `BLACK` | Qora (0,0,0) | yopishgan (yonib turgan) piksellar | 4–7 |
| 3 | `RED` | Qizil (255,0,0) | subpiksel nuqsonlari | 8–11 |
| 4 | `GREEN` | Yashil (0,255,0) | subpiksel nuqsonlari | 12–15 |
| 5 | `BLUE` | Ko'k (0,0,255) | subpiksel nuqsonlari | 16–19 |
| 6 | `GRAY50` | Kulrang (128,128,128) | yorqinlik bir tekisligi (3×3) | 20–23 |
| 7 | `BLEED` | Qora + 1 px chegara (32,32,32) | yorug'lik sizishi (backlight bleed) | 24–27 |
| 8 | `GRADIENT` | 4 polosa: kulrang/R/G/B, 0→255, 256 pog'ona | banding, rang chuqurligi | 28–31 |
| 9 | `GRADIENT_STEPS` | 2 polosa: kulrang 0→63 va 192→255, har biri 64 pog'ona | zinapoyalarni sanash (6-bit aniqlash) | 32–35 |
| 10 | `FLICKER` | Bir tekis oq maydon (255 / 192 / 128) | PWM miltillash | 36–39 |

`testId = 0` — yordam (boshlang'ich) ekrani, unda marker yo'q.

## Geometriya

Katak o'lchami (qurilma pikselida):

```
c = max(4, round(min(W, H) / 135))      W, H — ekran o'lchami, qurilma pikselida
```

Masalan, 1920×1080 → `c = 8 px`, marker (sokin zona bilan) `8c = 64 px`.

Sokin zona **ekran burchagiga to'liq tegib turadi**. Demak:

* markerning qora chegarasi tashqi burchagi ekran burchagidan `(c, c)` ichkarida;
* ekranning haqiqiy burchagi = markerning tashqi burchagi + 1 katak (marker
  o'qlari bo'ylab tashqariga). Telefon ilovasi buni markerning o'z
  o'lchamidan (6 katak) hisoblaydi — ekran ruxsatini bilish shart emas.

## Tahlil uchun niqoblash (masking)

Telefon ilovasi o'lik piksel / uniformity / bleed tahlilida har bir burchakdagi
`8c × 8c` sohani (zaxira bilan ~`10c × 10c`) hisobga olmaydi.

Yorug'lik sizishi (BLEED) testida markerlarning oq sokin zonasi burchaklarga
oz miqdorda yorug'lik qo'shadi. Shuning uchun tavsiya etilgan tartib:
1) markerli rasm (identifikatsiya va geometriya), 2) **M** tugmasi bilan
markerlarni o'chirib, kamerani qimirlatmasdan ikkinchi rasm (tahlil uchun).

## Avtomatlashtirish API (sinov uchun)

Sahifa `window.MonitorTest` obyektini beradi:

```js
MonitorTest.go(testId)      // testga o'tish (0 = yordam)
MonitorTest.tests           // [{id, code, ...}]
MonitorTest.markerIds(testId) // [tl, tr, br, bl]
MonitorTest.cellSize()      // joriy c (qurilma pikselida)
```

URL orqali ham: `monitor-test.html#t=8` (8-test), `#t=8&m=0` (markersiz),
`?lang=ru` (til).
