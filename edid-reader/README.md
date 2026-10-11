# MonitorCheck — EDID o'quvchi (2-bosqich)

Windows reyestridan barcha ulangan monitorlarning EDID ma'lumotini o'qiydi,
dekodlaydi, shartnomadagi e'lon qilingan model bilan solishtiradi va
natijani **HTML hisobot**, **JSON** va **QR-kod** ko'rinishida saqlaydi.

## Foydalanish (oddiy foydalanuvchi)

1. `MonitorCheck-EDID.exe` ni tekshiriladigan kompyuterga ko'chiring (fleshka).
2. Ikki marta bosing. Administrator huquqi **kerak emas**.
3. Dastur topilgan monitorlarni ko'rsatadi va shartnomadagi ma'lumotlarni
   so'raydi (brend, model, diagonal, ruxsat, chastota, bit, seriya,
   inventar raqami). Bilmaganini Enter bilan o'tkazib yuboring.
4. Hisobot brauzerda ochiladi; QR-kodni telefon ilovasi bilan skanerlang.
   Natijalar `.exe` yonidagi `MonitorCheck_EDID\<kompyuter>_<sana>\` papkasida.

## Buyruq qatori

```
MonitorCheck-EDID.exe --brand Dell --model P2422H --diagonal 23.8 ^
    --resolution 1920x1080 --refresh 60 --bits 8 --inventory INV-0042
MonitorCheck-EDID.exe --all              # ilgari ulangan monitorlar ham
MonitorCheck-EDID.exe edid.bin --json    # faylni dekodlash, JSON chiqarish
```

`--help` — barcha parametrlar.

## Xulosa qoidalari

| Tekshiruv | mos | tekshirish kerak | mos emas |
|-----------|-----|------------------|----------|
| Brend | PNP kod brendga mos | OEM/panel/umumiy kod yoki brend kodi noma'lum | boshqa brend kodi |
| Model | EDID nomi modelga mos | nomda model kodi yo'q | boshqa model kodi |
| Diagonal | farq ≤ 0.6" | 0.6"–1.2" | > 1.2" |
| Tabiiy ruxsat | teng | EDID'da yo'q | boshqa (hatto qabul qilsa ham) |
| Chastota | e'lon ≤ maks. (+0.6 Hz) | aniqlanmadi | e'lon > maks. |
| Rang chuqurligi | EDID ≥ e'lon | EDID 1.3 (ko'rsatilmagan); 6 bit | EDID < e'lon |
| Seriya raqami | mos | EDID'da yo'q | boshqa |
| Sana | — | > 2 yil oldin / kelajakda | — |
| Ishlab chiqaruvchi kodi | — | registrda yo'q / no-name / panel kodi | — |

Umumiy xulosa: bitta "mos emas" → **MOS EMAS**; bitta "tekshirish kerak" →
**TEKSHIRISH KERAK**; aks holda **MOS**.

## Ishlab chiquvchi uchun

```bash
pip install -r requirements-dev.txt
python -m pytest            # 83 test: sintetik EDID, 27 ta real EDID (edid-decode bilan), reyestr, eksport
python -m edid_reader tests/data/linuxhw/hdr__GSM5BB4_7FDB24C20B4F.txt --no-open
```

`.exe` yig'ish: Windows'da `build_exe.bat`, yoki GitHub Actions
("EDID reader" workflow) avtomatik yig'adi.

Tuzilma: `decoder.py` (EDID 1.3/1.4, CTA-861, DisplayID), `timings.py`
(standart rejimlar jadvallari), `vendors.py` + `pnp_ids.py` (ishlab
chiqaruvchilar), `compare.py` (solishtirish), `sources.py` (reyestr/sysfs/fayl),
`export.py` (JSON/QR/HTML), `cli.py`.

QR formati: [`../docs/edid-schema.md`](../docs/edid-schema.md).

## Cheklovlar

EDID — monitor proshivkasidagi ma'lumot; u panel turini, haqiqiy rang
chuqurligini (6-bit+FRC) yoki haqiqiy rang qamrovini kafolatlamaydi va uni
o'zgartirish mumkin. Shuning uchun bu — dastlabki tekshiruv; yakuniy baho
telefon ilovasidagi o'lchovlar bilan birga beriladi.
