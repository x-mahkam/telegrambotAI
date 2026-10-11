"""
EDID ma'lumotini shartnomada e'lon qilingan tavsif bilan solishtirish.

Har bir tekshiruv natijasi:
  ok    — mos
  fail  — mos emas (aniq ziddiyat)
  check — tekshirish kerak (ma'lumot yetarli emas yoki ikki xil talqin mumkin)
  info  — faqat ma'lumot

Umumiy xulosa: kamida bitta "fail" → MOS_EMAS; aks holda kamida bitta
"check" → TEKSHIRISH_KERAK; aks holda MOS. Hech qanday e'lon qilingan
qiymat kiritilmagan bo'lsa → SOLISHTIRILMADI (yaxlitlik muammolari bo'lsa
TEKSHIRISH_KERAK).
"""
from __future__ import annotations

import difflib
import re
from dataclasses import asdict, dataclass, fields
from datetime import date

from .decoder import EdidInfo
from .messages import msg
from .pnp_ids import PNP_IDS
from .vendors import (
    DE_FACTO,
    GENERIC_CODES,
    PANEL_MAKER_CODES,
    codes_for_brand,
    normalize_brand,
)

# Brend bo'lmagan, lekin monitorlarni boshqa brendlar uchun yig'adigan zavodlar (OEM).
OEM_CODES = {"TPV": "Top Victory (AOC/Philips OEM)", "HKC": "HKC", "KTC": "KTC", "CMT": "CMT"}

# Diagonal bo'yicha ruxsat etilgan farq (dyuym).
# EDID asosiy blokida o'lcham butun santimetrda: har bir tomon ±0.5 sm xato
# beradi → diagonal ±~0.3". Marketing nomlari ("24\"" vs 23.8") yana ~0.2".
DIAG_OK_IN = 0.6
DIAG_CHECK_IN = 1.2
# Chastota: EDID'dagi aniq qiymat (masalan, 74.97 Hz) va nominal (75) farqi.
HZ_TOL = 0.6


@dataclass
class Declared:
    brand: str | None = None
    model: str | None = None
    diagonal_in: float | None = None
    resolution: tuple[int, int] | None = None
    refresh_hz: float | None = None
    bit_depth: int | None = None
    serial: str | None = None

    def is_empty(self) -> bool:
        return all(getattr(self, f.name) in (None, "") for f in fields(self))

    def to_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v not in (None, "")}

    @classmethod
    def from_dict(cls, d: dict) -> "Declared":
        res = d.get("resolution")
        if isinstance(res, str):
            res = parse_resolution(res)
        elif res:
            res = (int(res[0]), int(res[1]))
        return cls(
            brand=d.get("brand") or None,
            model=d.get("model") or None,
            diagonal_in=float(d["diagonal_in"]) if d.get("diagonal_in") else None,
            resolution=res,
            refresh_hz=float(d["refresh_hz"]) if d.get("refresh_hz") else None,
            bit_depth=int(d["bit_depth"]) if d.get("bit_depth") else None,
            serial=d.get("serial") or None,
        )


def parse_resolution(s: str) -> tuple[int, int] | None:
    m = re.match(r"^\s*(\d{3,5})\s*[xX×*]\s*(\d{3,5})\s*$", s or "")
    return (int(m.group(1)), int(m.group(2))) if m else None


@dataclass
class Check:
    key: str
    status: str            # ok | fail | check | info
    declared: str = ""
    actual: str = ""
    note: str = ""


@dataclass
class Comparison:
    verdict: str
    checks: list[Check]

    def to_dict(self) -> dict:
        return {"verdict": self.verdict, "checks": [asdict(c) for c in self.checks]}

    def status_map(self) -> dict[str, str]:
        return {c.key: c.status for c in self.checks}


# --------------------------------------------------------------------------

def _compact(s: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())


def _model_tokens(s: str) -> set[str]:
    """Raqam qatnashgan, uzunligi ≥3 bo'lgan bo'laklar (model kodlari)."""
    toks = re.split(r"[^A-Z0-9]+", (s or "").upper())
    return {t for t in toks if len(t) >= 3 and any(ch.isdigit() for ch in t)}


def model_match(edid_name: str, declared: str) -> str:
    """'ok' | 'fail' | 'check' qaytaradi."""
    a, b = _compact(edid_name), _compact(declared)
    if not a or not b:
        return "check"
    if len(min(a, b, key=len)) >= 4 and (a in b or b in a):
        return "ok"
    ta, tb = _model_tokens(edid_name), _model_tokens(declared)
    for x in ta:
        for y in tb:
            if (len(min(x, y, key=len)) >= 4 and (x in y or y in x)) or \
                    difflib.SequenceMatcher(None, x, y).ratio() >= 0.85:
                return "ok"
    if ta and tb:
        return "fail"
    return "check"


def _fmt_res(m) -> str:
    return f"{m.width}x{m.height}" if m else "—"


def compare(info: EdidInfo, decl: Declared | None = None, today: date | None = None,
            max_age_years: float = 2.0) -> Comparison:
    decl = decl or Declared()
    today = today or date.today()
    checks: list[Check] = []

    # 1) Yaxlitlik
    if not info.checksum_ok:
        checks.append(Check("integrity", "check", note=msg("n.checksum_bad")))
    elif info.truncated:
        checks.append(Check("integrity", "check", note=msg("n.truncated")))
    else:
        checks.append(Check("integrity", "ok", note=msg("n.checksum_ok", n=info.blocks)))

    # 2) Ishlab chiqaruvchi kodi
    code = info.manufacturer_id
    actual_vendor = f"{code} — {info.manufacturer_name or '?'}"
    if code in GENERIC_CODES:
        checks.append(Check("vendor_code", "check", actual=actual_vendor,
                            note=msg("n.vendor_generic", why=GENERIC_CODES[code])))
    elif code in PANEL_MAKER_CODES:
        checks.append(Check("vendor_code", "check", actual=actual_vendor,
                            note=msg("n.vendor_panel", name=PANEL_MAKER_CODES[code])))
    elif code in PNP_IDS:
        checks.append(Check("vendor_code", "info", actual=actual_vendor,
                            note=msg("n.vendor_known", name=PNP_IDS[code].rstrip("."))))
    elif code in DE_FACTO:
        checks.append(Check("vendor_code", "info", actual=actual_vendor,
                            note=msg("n.vendor_defacto", name=DE_FACTO[code])))
    else:
        checks.append(Check("vendor_code", "check", actual=actual_vendor, note=msg("n.vendor_unknown")))

    # 3) Ishlab chiqarilgan sana. Hafta → taxminiy sana: yil + (hafta − 0.5)/52.
    if info.year:
        frac = (info.week - 0.5) / 52 if info.week else 0.5
        age = today.year + (today.timetuple().tm_yday / 365.25) - (info.year + frac)
        actual = f"{info.year}" + (f", {info.week}-hafta" if info.week else "")
        if info.is_model_year:
            checks.append(Check("age", "info", actual=actual, note=msg("n.model_year")))
        elif age < -0.1:
            checks.append(Check("age", "check", actual=actual, note=msg("n.age_future")))
        elif age > max_age_years:
            checks.append(Check("age", "check", actual=actual,
                                note=msg("n.age_old", age=age, limit=max_age_years)))
        else:
            checks.append(Check("age", "info", actual=actual, note=msg("n.age_ok", age=max(age, 0))))
    else:
        checks.append(Check("age", "info", note=msg("n.age_unknown")))

    # 4) Brend
    if decl.brand:
        codes = codes_for_brand(decl.brand)
        name_hit = info.manufacturer_name and normalize_brand(decl.brand) in normalize_brand(info.manufacturer_name)
        if code in codes or name_hit:
            st, note = "ok", msg("n.brand_ok")
        elif code in OEM_CODES or code in GENERIC_CODES or code in PANEL_MAKER_CODES:
            st, note = "check", msg("n.brand_oem")
        elif not codes:
            st, note = "check", msg("n.brand_unknown")
        else:
            st, note = "fail", msg("n.brand_fail")
        checks.append(Check("brand", st, decl.brand, actual_vendor, note))

    # 5) Model
    if decl.model:
        if not info.name:
            checks.append(Check("model", "check", decl.model, "—", msg("n.model_noname")))
        else:
            st = model_match(info.name, decl.model)
            note = {"ok": "n.model_ok", "fail": "n.model_fail", "check": "n.model_partial"}[st]
            checks.append(Check("model", st, decl.model, info.name, msg(note)))

    # 6) Diagonal
    if decl.diagonal_in:
        if info.diagonal_in is None:
            checks.append(Check("diagonal", "check", f"{decl.diagonal_in}\"", "—", msg("n.diag_unknown")))
        else:
            d = abs(info.diagonal_in - decl.diagonal_in)
            st = "ok" if d <= DIAG_OK_IN else "check" if d <= DIAG_CHECK_IN else "fail"
            checks.append(Check("diagonal", st, f"{decl.diagonal_in}\"", f"{info.diagonal_in}\"",
                                msg({"ok": "n.diag_ok", "check": "n.diag_check", "fail": "n.diag_fail"}[st], d=d)))

    # 7) Tabiiy ruxsat
    if decl.resolution:
        dres = f"{decl.resolution[0]}x{decl.resolution[1]}"
        nat = info.native
        if nat is None:
            checks.append(Check("resolution", "check", dres, "—", msg("n.res_unknown")))
        elif (nat.width, nat.height) == tuple(decl.resolution):
            checks.append(Check("resolution", "ok", dres, _fmt_res(nat), msg("n.res_ok")))
        else:
            accepted = any((m.width, m.height) == tuple(decl.resolution) for m in info.modes)
            note = msg("n.res_scaled" if accepted else "n.res_fail", native=_fmt_res(nat))
            checks.append(Check("resolution", "fail", dres, _fmt_res(nat), note))

    # 8) Chastota (tabiiy ruxsatda)
    if decl.refresh_hz:
        mx = info.max_refresh_native_hz
        if mx is None:
            checks.append(Check("refresh", "check", f"{decl.refresh_hz:g} Hz", "—", msg("n.hz_unknown")))
        else:
            st = "ok" if decl.refresh_hz <= mx + HZ_TOL else "fail"
            checks.append(Check("refresh", st, f"{decl.refresh_hz:g} Hz", f"{mx:g} Hz",
                                msg("n.hz_ok" if st == "ok" else "n.hz_fail", max=mx)))

    # 9) Rang chuqurligi
    bits, src = info.bit_depth, info.bit_depth_source or ""
    if decl.bit_depth:
        if bits is None:
            checks.append(Check("bit_depth", "check", f"{decl.bit_depth} bit", "—", msg("n.bits_unknown")))
        elif bits < decl.bit_depth:
            checks.append(Check("bit_depth", "fail", f"{decl.bit_depth} bit", f"{bits} bit",
                                msg("n.bits_fail", bits=bits, src=src)))
        else:
            key = "n.bits_frc" if decl.bit_depth >= 10 else "n.bits_ok"
            checks.append(Check("bit_depth", "ok", f"{decl.bit_depth} bit", f"{bits} bit",
                                msg(key, bits=bits, src=src)))
    elif bits == 6:
        checks.append(Check("bit_depth", "check", "", "6 bit", msg("n.bits_six")))

    # 10) Seriya raqami
    if decl.serial:
        cands = [s for s in (info.serial_string, str(info.serial_number) if info.serial_number else None) if s]
        if not cands:
            checks.append(Check("serial", "check", decl.serial, "—", msg("n.serial_none")))
        else:
            ds = _compact(decl.serial)
            ok = any(len(_compact(c)) >= 4 and (_compact(c) in ds or ds in _compact(c)) for c in cands)
            checks.append(Check("serial", "ok" if ok else "fail", decl.serial, " / ".join(cands),
                                msg("n.serial_ok") if ok else msg("n.serial_fail", sn=" / ".join(cands))))

    statuses = {c.status for c in checks}
    if decl.is_empty():
        verdict = "TEKSHIRISH_KERAK" if "check" in statuses or "fail" in statuses else "SOLISHTIRILMADI"
    elif "fail" in statuses:
        verdict = "MOS_EMAS"
    elif "check" in statuses:
        verdict = "TEKSHIRISH_KERAK"
    else:
        verdict = "MOS"
    return Comparison(verdict, checks)
