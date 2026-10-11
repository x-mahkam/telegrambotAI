# EDID natijasi: QR-kod formati (MCEDID v1)

EDID o'quvchi (`edid-reader`) har bir monitor uchun bitta QR-kod yaratadi.
Telefon ilovasi uni skanerlab, hisobotga qo'shadi. QR ichida — ixcham JSON
(UTF-8, bo'shliqsiz), odatda 400–700 bayt. Xato tuzatish darajasi **M**.

Qiymati bo'sh (`null`) bo'lgan kalitlar umuman yozilmaydi.

| Kalit | Turi | Ma'nosi |
|-------|------|---------|
| `t` | str | Har doim `"MCEDID"` — format belgisi |
| `v` | int | Format versiyasi (`1`) |
| `ts` | str | Vaqt, `YYYY-MM-DDTHH:MM` (kompyuterning mahalliy vaqti) |
| `pc` | str | Kompyuter nomi |
| `inv` | str | Inventar raqami (kiritilgan bo'lsa) |
| `n` | int | Monitor tartib raqami (bir kompyuterda bir nechta bo'lsa) |
| `mfg` | str | PNP ishlab chiqaruvchi kodi (3 harf), masalan `DEL` |
| `mfn` | str | Ishlab chiqaruvchining to'liq nomi |
| `pid` | str | Mahsulot kodi, 4 xonali hex (`A0F2`) |
| `sn` | str | Seriya raqami (matnli deskriptor, bo'lmasa — raqamli) |
| `nm` | str | Monitor nomi (EDID, ≤13 belgi) |
| `wk`, `yr` | int | Ishlab chiqarilgan hafta va yil |
| `cm` | [int,int] | Fizik o'lcham, sm (eni, bo'yi) |
| `in` | float | Diagonal, dyuym |
| `res` | [int,int] | Tabiiy (afzal) ruxsat |
| `hz` | float | Afzal rejim chastotasi |
| `mhz` | float | Tabiiy ruxsatdagi maksimal chastota |
| `bpc` | int | Rang chuqurligi, bit (kanal uchun) |
| `bs` | str | `bpc` manbasi: `E14` (EDID 1.4) yoki `HDMI` (HDMI VSDB, kirish signali) |
| `if` | str | Interfeys (EDID 1.4): `DisplayPort`, `HDMI-a`, `DVI` ... |
| `ev` | str | EDID versiyasi (`1.3`, `1.4`) |
| `srgb` | float | E'lon qilingan sRGB qamrovi, % (CIE 1931 xy) |
| `ntsc` | float | E'lon qilingan uchburchak yuzasi / NTSC 1953 yuzasi, % |
| `hdr` | float | HDR maks. yorqinlik, kd/m² (e'lon qilingan) |
| `ck` | 0/1 | Nazorat yig'indilari to'g'rimi |
| `h` | str | To'liq EDID SHA-256 xeshining birinchi 16 hex belgisi |
| `dc` | obj | E'lon qilingan (shartnoma): `b` brend, `m` model, `d` diagonal, `r` [w,h], `f` Hz, `bit`, `s` seriya |
| `vd` | str | Xulosa: `MOS`, `MOS_EMAS`, `TEKSHIRISH_KERAK`, `SOLISHTIRILMADI` |
| `ch` | obj | Tekshiruvlar holati: kalit → `o` (mos), `f` (mos emas), `c` (tekshirish kerak), `i` (ma'lumot) |

`ch` kalitlari: `integrity`, `vendor_code`, `age`, `brand`, `model`,
`diagonal`, `resolution`, `refresh`, `bit_depth`, `serial`.

## Misol

```json
{"t":"MCEDID","v":1,"ts":"2026-10-11T12:00","pc":"PC-042","inv":"INV-0042","n":1,
 "mfg":"DEL","mfn":"Dell Inc.","pid":"A0F2","sn":"CN0ABC123","nm":"DELL P2422H",
 "wk":40,"yr":2025,"cm":[53,30],"in":23.8,"res":[1920,1080],"hz":60.0,"mhz":60.0,
 "bpc":8,"bs":"E14","if":"DisplayPort","ev":"1.4","srgb":99.6,"ntsc":72.1,"ck":1,
 "h":"3f2a9c...","dc":{"b":"Dell","m":"P2422H","d":23.8,"r":[1920,1080],"f":60},
 "vd":"MOS","ch":{"integrity":"o","vendor_code":"i","age":"i","brand":"o","model":"o",
 "diagonal":"o","resolution":"o","refresh":"o"}}
```

To'liq ma'lumot (barcha rejimlar, xom EDID hex) `natija.json` faylida saqlanadi.
Telefon ilovasi QR'dagi `h` ni keyinchalik `natija.json` dagi `sha256` bilan
solishtirib, ikki manba bir monitorga tegishli ekanini tasdiqlashi mumkin.
