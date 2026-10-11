"""
Ishlab chiqaruvchi kodlari bo'yicha yordamchi jadvallar.

* PNP_IDS (pnp_ids.py) — rasmiy UEFI registri.
* DE_FACTO — rasmiy registrda YO'Q, lekin monitorlarda keng ishlatiladigan
  kodlar. Manba: linuxhw/EDID ochiq bazasi (CC BY 4.0) statistikasi —
  har bir kod yuzlab/minglab real monitorda shu brend bilan uchraydi.
* GENERIC_CODES — "nomsiz" (no-name) yoki zavod sozlamasida qolgan kodlar.
  Brendli monitorda bunday kod bo'lishi — tekshirish uchun asos.
* PANEL_MAKER_CODES — panel ishlab chiqaruvchilari. Noutbuk ichki ekranida
  normal; lekin alohida (tashqi) brendli monitorda g'ayrioddiy.
* BRAND_ALIASES — shartnomadagi brend nomi -> mumkin bo'lgan PNP kodlar.
"""
from __future__ import annotations

from .pnp_ids import PNP_IDS

DE_FACTO = {
    "AOC": "AOC (TPV) — de-fakto kod",
    "BNQ": "BenQ Corporation — de-fakto kod",
    "BBY": "Insignia (Best Buy) — de-fakto kod",
    "PKB": "Packard Bell — de-fakto kod",
    "WAM": "Pixio — de-fakto kod",
    "ONN": "onn. (Walmart) — de-fakto kod",
    "AOP": "AOpen — de-fakto kod",
    "XMD": "Xiaomi — de-fakto kod",
    "ENV": "Envision Peripherals — de-fakto kod",
    "SEK": "Seiki — de-fakto kod",
    "XEC": "Sansui — de-fakto kod",
    "PLN": "Planar — de-fakto kod",
    "ASR": "ASRock — de-fakto kod",
    "GRU": "Grundig — de-fakto kod",
    "WET": "Westinghouse — de-fakto kod",
    "HAT": "Huion — de-fakto kod",
}

GENERIC_CODES = {
    "XXX": "nomsiz (placeholder) kod",
    "OEM": "umumiy OEM kod",
    "OOO": "nomsiz (placeholder) kod",
    "@@@": "bo'sh kod (barcha bitlar nol)",
    "RTK": "Realtek skaler platasining zavod kodi (registrda 'Invalid')",
    "MST": "MStar skaler platasining zavod kodi bo'lishi mumkin",
}

PANEL_MAKER_CODES = {
    "AUO": "AU Optronics", "BOE": "BOE", "CMN": "Chimei Innolux", "INL": "InnoLux",
    "IVO": "InfoVision", "LGD": "LG Display", "SDC": "Samsung Display", "SHP": "Sharp",
    "HSD": "HannStar", "CSO": "CSOT", "QDS": "Quanta Display", "JDI": "Japan Display",
}

# Kalit — kichik harflarda, bo'shliqsiz brend nomi.
BRAND_ALIASES = {
    "dell": ["DEL"], "alienware": ["DEL", "AWD"],
    "samsung": ["SAM", "SDC", "STN"], "lg": ["GSM", "LGD"], "lgelectronics": ["GSM"],
    "hp": ["HWP", "HPN", "HPQ"], "hewlettpackard": ["HWP", "HPN", "HPQ"],
    "lenovo": ["LEN"], "acer": ["ACR"], "asus": ["AUS", "ACI"],
    "aoc": ["AOC"], "philips": ["PHL"], "benq": ["BNQ"], "viewsonic": ["VSC"],
    "msi": ["MSI"], "iiyama": ["IVM"], "nec": ["NEC"], "eizo": ["ENC"],
    "gigabyte": ["GBT"], "aorus": ["GBT"], "xiaomi": ["XMI", "XMD"], "redmi": ["XMI", "XMD"],
    "huawei": ["HWV"], "hisense": ["HEC"], "apple": ["APP"], "sony": ["SNY"],
    "fujitsu": ["FUS"], "toshiba": ["TSB"], "sharp": ["SHP"], "panasonic": ["MEI"],
    "haier": ["HRE"], "hkc": ["HKC"], "packardbell": ["PKB"], "aopen": ["AOP"],
    "asrock": ["ASR"], "planar": ["PLN"],
}


def manufacturer_name(code: str) -> str | None:
    """PNP kod -> kompaniya nomi (rasmiy registr, so'ng de-fakto jadval)."""
    return PNP_IDS.get(code) or DE_FACTO.get(code)


def normalize_brand(s: str) -> str:
    return "".join(ch for ch in s.lower() if ch.isalnum())


def codes_for_brand(brand: str) -> list[str]:
    """Brend nomidan mumkin bo'lgan PNP kodlar ro'yxati.

    Avval qo'lda tuzilgan BRAND_ALIASES, topilmasa — registrdagi nomlar
    ichidan qidiruv (masalan "Artel" registrda bo'lsa, shu yerda topiladi).
    """
    key = normalize_brand(brand)
    if not key:
        return []
    if key in BRAND_ALIASES:
        return list(BRAND_ALIASES[key])
    if len(key) == 3 and key.upper() in PNP_IDS:
        return [key.upper()]
    found = [c for c, n in {**PNP_IDS, **DE_FACTO}.items()
             if len(key) >= 3 and key in normalize_brand(n)]
    return sorted(found)
