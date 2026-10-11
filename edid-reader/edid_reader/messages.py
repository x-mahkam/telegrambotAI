"""
Foydalanuvchiga ko'rsatiladigan matnlar. Standart til — o'zbek (lotin).

Rus tilini qo'shish uchun MESSAGES["ru"] ga xuddi shu kalitlarni yozing va
MONITORCHECK_LANG=ru muhit o'zgaruvchisini o'rnating (yoki --lang ru).
Tarjimasi yo'q kalit avtomatik o'zbekchadan olinadi.
"""
from __future__ import annotations

import os

MESSAGES: dict[str, dict[str, str]] = {
    "uz": {
        # Xulosalar
        "verdict.MOS": "MOS",
        "verdict.MOS_EMAS": "MOS EMAS",
        "verdict.TEKSHIRISH_KERAK": "TEKSHIRISH KERAK",
        "verdict.SOLISHTIRILMADI": "SOLISHTIRILMADI (e'lon qilingan ma'lumot kiritilmagan)",
        "status.ok": "mos",
        "status.fail": "mos emas",
        "status.check": "tekshirish kerak",
        "status.info": "ma'lumot",

        # Tekshiruv nomlari
        "check.integrity": "EDID yaxlitligi (nazorat yig'indisi)",
        "check.vendor_code": "Ishlab chiqaruvchi kodi",
        "check.age": "Ishlab chiqarilgan sana",
        "check.brand": "Brend",
        "check.model": "Model",
        "check.diagonal": "Diagonal",
        "check.resolution": "Tabiiy (native) ruxsat",
        "check.refresh": "Maksimal chastota (tabiiy ruxsatda)",
        "check.bit_depth": "Rang chuqurligi",
        "check.serial": "Seriya raqami",

        # Izohlar
        "n.checksum_ok": "Barcha {n} blok nazorat yig'indisi to'g'ri.",
        "n.checksum_bad": "Nazorat yig'indisi xato — EDID buzilgan yoki o'zgartirilgan bo'lishi mumkin.",
        "n.truncated": "EDID to'liq o'qilmadi (kengaytma bloklari yetishmaydi).",
        "n.vendor_known": "Ro'yxatdan o'tgan kod: {name}.",
        "n.vendor_defacto": "Rasmiy UEFI registrida yo'q, lekin amalda keng ishlatiladi: {name}.",
        "n.vendor_unknown": "Kod rasmiy PNP registrida topilmadi — no-name yoki soxta EDID bo'lishi mumkin.",
        "n.vendor_generic": "Umumiy / nomsiz kod ({why}). Brendli monitorda bu g'ayrioddiy.",
        "n.vendor_panel": "Bu panel ishlab chiqaruvchisi kodi ({name}). Noutbuk ichki ekrani uchun normal, alohida monitor uchun g'ayrioddiy.",
        "n.age_ok": "{age:.1f} yil oldin ishlab chiqarilgan.",
        "n.age_old": "{age:.1f} yil oldin ishlab chiqarilgan (chegara: {limit} yil) — eski zaxira yoki ishlatilgan bo'lishi mumkin.",
        "n.age_future": "Sana kelajakda — EDID ma'lumoti noto'g'ri.",
        "n.age_unknown": "Ishlab chiqarilgan sana EDID'da ko'rsatilmagan.",
        "n.model_year": "Faqat model yili ko'rsatilgan.",
        "n.brand_ok": "EDID kodi e'lon qilingan brendga mos.",
        "n.brand_fail": "EDID boshqa ishlab chiqaruvchini ko'rsatadi.",
        "n.brand_oem": "EDID OEM/panel/umumiy kodni ko'rsatadi — monitor boshqa zavodda yig'ilgan bo'lishi mumkin; hujjatlarni tekshiring.",
        "n.brand_unknown": "E'lon qilingan brend uchun PNP kodi noma'lum — qo'lda tekshiring.",
        "n.model_ok": "EDID'dagi nom e'lon qilingan modelga mos.",
        "n.model_fail": "EDID'dagi model nomi boshqa.",
        "n.model_partial": "Nomlar to'liq mos emas (EDID nomi 13 belgigacha qisqartiriladi) — yorliq bilan solishtiring.",
        "n.model_noname": "EDID'da monitor nomi yo'q.",
        "n.diag_ok": "Farq {d:.1f}\" (EDID sm gacha yaxlitlanadi, ±0.6\" ruxsat).",
        "n.diag_check": "Farq {d:.1f}\" — yaxlitlash chegarasidan katta.",
        "n.diag_fail": "Farq {d:.1f}\" — boshqa o'lchamdagi panel.",
        "n.diag_unknown": "EDID'da fizik o'lcham ko'rsatilmagan.",
        "n.res_ok": "Tabiiy ruxsat mos.",
        "n.res_scaled": "Monitor bu ruxsatni faqat QABUL QILADI (masshtablaydi); tabiiy ruxsati {native}.",
        "n.res_fail": "Tabiiy ruxsat boshqa: {native}.",
        "n.res_unknown": "EDID'da afzal (preferred) rejim yo'q.",
        "n.hz_ok": "Tabiiy ruxsatda {max:.0f} Hz gacha.",
        "n.hz_fail": "Tabiiy ruxsatda maksimal {max:.0f} Hz.",
        "n.hz_unknown": "Tabiiy ruxsatdagi maksimal chastota aniqlanmadi.",
        "n.bits_ok": "EDID: {bits} bit ({src}).",
        "n.bits_frc": "EDID: {bits} bit ({src}). Diqqat: bu kirish signali chuqurligi; panel 8-bit+FRC bo'lishi mumkin — gradient testi bilan tekshiring.",
        "n.bits_fail": "EDID: {bits} bit ({src}) — e'lon qilinganidan kam.",
        "n.bits_unknown": "EDID 1.3 da rang chuqurligi ko'rsatilmaydi — gradient testi bilan tekshiring.",
        "n.bits_six": "EDID 6 bit ko'rsatmoqda — 6-bitli panel belgisi.",
        "n.serial_ok": "Seriya raqami mos.",
        "n.serial_fail": "EDID seriya raqami: {sn}.",
        "n.serial_none": "EDID'da seriya raqami yo'q.",
        "n.no_declaration": "Shartnoma ma'lumoti kiritilmagan — faqat EDID yaxlitligi tekshirildi.",

        # CLI
        "cli.title": "MonitorCheck — EDID o'quvchi",
        "cli.found": "Topilgan monitorlar: {n}",
        "cli.none": "Hech qanday monitor EDID'i topilmadi.",
        "cli.inactive_note": "Faol monitor aniqlanmadi — reyestrdagi barcha yozuvlar ko'rsatilmoqda (ilgari ulangan monitorlar ham bo'lishi mumkin).",
        "cli.enter_declared": "Shartnomadagi e'lon qilingan ma'lumotlarni kiriting (bilmasangiz — Enter):",
        "cli.q_brand": "  Brend (masalan, Dell): ",
        "cli.q_model": "  Model (masalan, P2422H): ",
        "cli.q_diag": "  Diagonal, dyuym (masalan, 23.8): ",
        "cli.q_res": "  Ruxsat (masalan, 1920x1080): ",
        "cli.q_hz": "  Chastota, Hz (masalan, 75): ",
        "cli.q_bits": "  Rang chuqurligi, bit (6/8/10): ",
        "cli.q_serial": "  Seriya raqami (monitor yorlig'idan): ",
        "cli.q_inv": "  Inventar raqami: ",
        "cli.bad_value": "    Noto'g'ri qiymat, e'tiborsiz qoldirildi.",
        "cli.saved": "Natijalar saqlandi: {path}",
        "cli.press_enter": "Chiqish uchun Enter bosing...",
        "cli.verdict": "XULOSA",

        # Hisobot
        "r.title": "EDID tekshiruvi hisoboti",
        "r.computer": "Kompyuter",
        "r.date": "Sana",
        "r.inventory": "Inventar raqami",
        "r.monitor": "Monitor",
        "r.active": "faol",
        "r.inactive": "faol emas (ilgari ulangan)",
        "r.checks": "Shartnoma bilan solishtirish",
        "r.declared": "E'lon qilingan",
        "r.actual": "EDID bo'yicha",
        "r.status": "Holat",
        "r.note": "Izoh",
        "r.decoded": "EDID ma'lumotlari",
        "r.qr": "Telefon ilovasi bilan skanerlang",
        "r.raw": "Xom EDID (hex)",
        "r.limits": "Usulning cheklovlari",
        "r.limits_text": (
            "EDID — monitor proshivkasiga yozilgan, monitorning o'zi haqidagi ma'lumot; "
            "uni ishlab chiqaruvchi yoki yetkazib beruvchi o'zgartirishi mumkin. "
            "EDID panel turini (IPS/VA/TN), yorug'lik manbaini va haqiqiy rang chuqurligini "
            "(masalan, 6-bit+FRC) bevosita ko'rsatmaydi; ko'rsatilgan rang chuqurligi monitor "
            "qabul qiladigan signalga tegishli. Rang qamrovi (sRGB/NTSC) EDID'da e'lon qilingan "
            "birlamchi ranglardan hisoblangan, o'lchanmagan. Diagonal EDID'dagi o'lchamdan "
            "(sm yoki mm aniqlikda) hisoblanadi. Shuning uchun bu natija dastlabki tekshiruv "
            "bo'lib, ekran testlari (telefon ilovasi) bilan birga baholanadi."
        ),
    },
    "ru": {},
}

_LANG = os.environ.get("MONITORCHECK_LANG", "uz").lower()


def set_lang(lang: str) -> None:
    global _LANG
    _LANG = lang.lower()


def msg(key: str, **kw) -> str:
    s = MESSAGES.get(_LANG, {}).get(key) or MESSAGES["uz"].get(key) or key
    return s.format(**kw) if kw else s
