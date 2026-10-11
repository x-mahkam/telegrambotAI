"""
Natijalarni eksport qilish: to'liq JSON, ixcham QR yuklamasi va HTML hisobot.

QR yuklamasi formati: docs/edid-schema.md ("MCEDID" v1).
"""
from __future__ import annotations

import html
import io
import json
from dataclasses import dataclass
from datetime import datetime

from . import __version__
from .compare import Comparison, Declared
from .decoder import EdidInfo
from .messages import msg
from .sources import EdidSource

QR_TYPE = "MCEDID"
QR_VERSION = 1


@dataclass
class MonitorResult:
    index: int
    source: EdidSource
    info: EdidInfo
    comparison: Comparison


@dataclass
class Session:
    timestamp: datetime
    computer: str
    os: str
    declared: Declared
    inventory: str | None
    monitors: list[MonitorResult]


# --------------------------------------------------------------------------
# JSON
# --------------------------------------------------------------------------

def full_json(s: Session) -> dict:
    return {
        "tool": "MonitorCheck EDID reader",
        "tool_version": __version__,
        "timestamp": s.timestamp.isoformat(timespec="seconds"),
        "computer": s.computer,
        "os": s.os,
        "inventory": s.inventory,
        "declared": s.declared.to_dict(),
        "monitors": [{
            "index": m.index,
            "origin": m.source.origin,
            "location": m.source.location,
            "active": m.source.active,
            "edid": m.info.to_dict(),
            "comparison": m.comparison.to_dict(),
            "qr_payload": qr_payload(s, m),
        } for m in s.monitors],
    }


_BIT_SRC = {"EDID 1.4": "E14", "HDMI VSDB (kirish signali)": "HDMI"}
_STATUS = {"ok": "o", "fail": "f", "check": "c", "info": "i"}


def qr_payload(s: Session, m: MonitorResult) -> dict:
    """QR-kod uchun ixcham yuklama (qisqa kalitlar, bo'sh qiymatlarsiz).

    Kalitlar ma'nosi docs/edid-schema.md da. Hajm odatda 450–700 bayt —
    ekrandan telefon kamerasi bilan bemalol o'qiladi.
    """
    e = m.info
    d = s.declared
    p = {
        "t": QR_TYPE, "v": QR_VERSION,
        "ts": s.timestamp.strftime("%Y-%m-%dT%H:%M"),
        "pc": s.computer,
        "inv": s.inventory,
        "n": m.index,
        "mfg": e.manufacturer_id,
        "mfn": e.manufacturer_name,
        "pid": e.product_code_hex,
        "sn": e.serial_string or (str(e.serial_number) if e.serial_number else None),
        "nm": e.name,
        "wk": e.week, "yr": e.year,
        "cm": list(e.size_cm) if e.size_cm else None,
        "in": e.diagonal_in,
        "res": [e.native.width, e.native.height] if e.native else None,
        "hz": round(e.native.refresh_hz, 2) if e.native else None,
        "mhz": e.max_refresh_native_hz,
        "bpc": e.bit_depth,
        "bs": _BIT_SRC.get(e.bit_depth_source or "", None),
        "if": e.interface,
        "ev": e.version,
        "srgb": e.gamut.get("sRGB_coverage_pct") if e.gamut else None,
        "ntsc": e.gamut.get("NTSC1953_area_pct") if e.gamut else None,
        "hdr": e.hdr.get("max_luminance_cd_m2") if e.hdr else None,
        "ck": 1 if e.checksum_ok else 0,
        "h": e.sha256[:16],
        "dc": {k: v for k, v in {
            "b": d.brand, "m": d.model, "d": d.diagonal_in,
            "r": list(d.resolution) if d.resolution else None,
            "f": d.refresh_hz, "bit": d.bit_depth, "s": d.serial}.items() if v not in (None, "")} or None,
        "vd": m.comparison.verdict,
        "ch": {c.key: _STATUS[c.status] for c in m.comparison.checks},
    }
    return {k: v for k, v in p.items() if v not in (None, "", [], {})}


def qr_text(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def qr_svg(text: str) -> str:
    """QR-kodni SVG ko'rinishida (Pillow kutubxonasisiz)."""
    import qrcode
    import qrcode.image.svg
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(text.encode("utf-8"))
    qr.make(fit=True)
    img = qr.make_image(image_factory=qrcode.image.svg.SvgPathFillImage)
    buf = io.BytesIO()
    img.save(buf)
    svg = buf.getvalue().decode("utf-8")
    return svg[svg.index("<svg"):]                  # XML sarlavhasisiz — HTML ichiga qo'yish uchun


# --------------------------------------------------------------------------
# HTML hisobot
# --------------------------------------------------------------------------

_CSS = """
:root { --ok:#1a7f37; --fail:#cf222e; --check:#bf8700; --info:#57606a; --bg:#fff; --fg:#1f2328; --mut:#57606a; --line:#d0d7de; }
* { box-sizing: border-box; }
body { margin: 0; padding: 24px 16px; font: 15px/1.5 "Segoe UI", Roboto, Arial, sans-serif; color: var(--fg); background: var(--bg); }
.wrap { max-width: 1040px; margin: 0 auto; }
h1 { font-size: 24px; margin: 0 0 4px; }
h2 { font-size: 19px; margin: 32px 0 8px; border-bottom: 2px solid var(--line); padding-bottom: 4px; }
h3 { font-size: 16px; margin: 20px 0 6px; }
.meta { color: var(--mut); }
table { border-collapse: collapse; width: 100%; font-size: 14px; }
td, th { border-bottom: 1px solid var(--line); padding: 5px 8px; text-align: left; vertical-align: top; }
th { color: var(--mut); font-weight: 600; }
.kv td:first-child { color: var(--mut); width: 38%; }
.verdict { display: inline-block; padding: 6px 14px; border-radius: 6px; color: #fff; font-weight: 700; font-size: 17px; }
.v-MOS { background: var(--ok); } .v-MOS_EMAS { background: var(--fail); }
.v-TEKSHIRISH_KERAK { background: var(--check); } .v-SOLISHTIRILMADI { background: var(--info); }
.s-ok { color: var(--ok); font-weight: 600; } .s-fail { color: var(--fail); font-weight: 600; }
.s-check { color: var(--check); font-weight: 600; } .s-info { color: var(--info); }
.grid { display: grid; grid-template-columns: 1fr 380px; gap: 24px; align-items: start; }
.qr { text-align: center; border: 1px solid var(--line); border-radius: 8px; padding: 12px; }
.qr svg { width: 100%; height: auto; }
.qr small { color: var(--mut); }
pre { white-space: pre-wrap; word-break: break-all; font: 12px Consolas, monospace; background: #f6f8fa; padding: 10px; border-radius: 6px; }
.limits { background: #fff8c5; border: 1px solid #d4a72c; border-radius: 6px; padding: 10px 14px; font-size: 14px; }
@media (max-width: 760px) { .grid { grid-template-columns: 1fr; } }
@media print { h2 { break-before: page; } h2:first-of-type { break-before: auto; } details { display: none; } }
"""


def _e(v) -> str:
    return html.escape("" if v is None else str(v))


def _kv_rows(e: EdidInfo) -> list[tuple[str, str]]:
    rows = [
        ("Ishlab chiqaruvchi (PNP)", f"{e.manufacturer_id} — {e.manufacturer_name or 'noma’lum'}"),
        ("Mahsulot kodi", f"0x{e.product_code_hex} ({e.product_code})"),
        ("Monitor nomi", e.name or "—"),
        ("Seriya raqami", " / ".join(x for x in [e.serial_string, str(e.serial_number) if e.serial_number else None] if x) or "—"),
        ("Ishlab chiqarilgan", (f"{e.week}-hafta, " if e.week else "") + (str(e.year) if e.year else "—")
         + (" (model yili)" if e.is_model_year else "")),
        ("EDID versiyasi", e.version + (f"; kengaytmalar: {', '.join(e.extensions)}" if e.extensions else "")),
        ("Fizik o'lcham", (f"{e.size_cm[0]} × {e.size_cm[1]} sm" if e.size_cm else "—")
         + (f" (aniq: {e.image_mm[0]} × {e.image_mm[1]} mm)" if e.image_mm else "")),
        ("Diagonal", f"{e.diagonal_in}\"  ({e.diagonal_in * 2.54:.1f} sm)" if e.diagonal_in else "—"),
        ("Tabiiy ruxsat (afzal rejim)", e.native.label() if e.native else "—"),
        ("Tabiiy ruxsatda maks. chastota", f"{e.max_refresh_native_hz} Hz" if e.max_refresh_native_hz else "—"),
        ("Qabul qilinadigan maks. ruxsat", e.max_resolution.label() if e.max_resolution else "—"),
        ("Rang chuqurligi", f"{e.bit_depth} bit ({e.bit_depth_source})" if e.bit_depth else "ko'rsatilmagan"),
        ("Interfeys", e.interface or ("raqamli" if e.digital else "analog")),
        ("Gamma", e.gamma or "—"),
    ]
    if e.gamut:
        g = e.gamut
        rows.append(("Rang qamrovi (e'lon qilingan, CIE xy)",
                     f"sRGB {g['sRGB_coverage_pct']}%, DCI-P3 {g['DCI-P3_coverage_pct']}%, "
                     f"Adobe RGB {g['AdobeRGB_coverage_pct']}%; NTSC yuzasi {g['NTSC1953_area_pct']}%"))
    if e.hdr:
        rows.append(("HDR", ", ".join(e.hdr.get("eotf", []))
                     + (f"; maks. {e.hdr['max_luminance_cd_m2']} kd/m²" if e.hdr.get("max_luminance_cd_m2") else "")))
    if e.range_limits:
        r = e.range_limits
        rows.append(("Chastota diapazoni", f"V {r['v_min_hz']}–{r['v_max_hz']} Hz, H {r['h_min_khz']}–{r['h_max_khz']} kHz"))
    rows.append(("SHA-256", e.sha256))
    return rows


def html_report(s: Session) -> str:
    parts = [
        "<!DOCTYPE html><html lang='uz'><head><meta charset='utf-8'>",
        "<meta name='viewport' content='width=device-width, initial-scale=1'>",
        f"<title>{_e(msg('r.title'))} — {_e(s.computer)}</title><style>{_CSS}</style></head><body><div class='wrap'>",
        f"<h1>{_e(msg('r.title'))}</h1>",
        f"<div class='meta'>{_e(msg('r.computer'))}: <b>{_e(s.computer)}</b> · {_e(msg('r.date'))}: "
        f"{s.timestamp:%Y-%m-%d %H:%M} · {_e(s.os)}"
        + (f" · {_e(msg('r.inventory'))}: <b>{_e(s.inventory)}</b>" if s.inventory else "")
        + f" · MonitorCheck EDID v{__version__}</div>",
    ]
    for m in s.monitors:
        e, c = m.info, m.comparison
        status = msg("r.active") if m.source.active else (msg("r.inactive") if m.source.active is False else "")
        title = f"{m.index}. {e.manufacturer_name or e.manufacturer_id} {e.name or ''}".strip()
        parts.append(f"<h2>{_e(msg('r.monitor'))} {_e(title)} <small class='meta'>{_e(status)}</small></h2>")
        parts.append("<div class='grid'><div>")
        parts.append(f"<p><span class='verdict v-{c.verdict}'>{_e(msg('verdict.' + c.verdict))}</span></p>")
        parts.append(f"<h3>{_e(msg('r.checks'))}</h3><table><tr><th></th><th>{_e(msg('r.declared'))}</th>"
                     f"<th>{_e(msg('r.actual'))}</th><th>{_e(msg('r.status'))}</th><th>{_e(msg('r.note'))}</th></tr>")
        for ch in c.checks:
            parts.append(f"<tr><td>{_e(msg('check.' + ch.key))}</td><td>{_e(ch.declared)}</td><td>{_e(ch.actual)}</td>"
                         f"<td class='s-{ch.status}'>{_e(msg('status.' + ch.status))}</td><td>{_e(ch.note)}</td></tr>")
        parts.append("</table>")
        parts.append(f"<h3>{_e(msg('r.decoded'))}</h3><table class='kv'>")
        parts += [f"<tr><td>{_e(k)}</td><td>{_e(v)}</td></tr>" for k, v in _kv_rows(e)]
        parts.append("</table></div>")
        text = qr_text(qr_payload(s, m))
        parts.append(f"<div class='qr'>{qr_svg(text)}<div><b>{_e(msg('r.qr'))}</b></div>"
                     f"<small>{len(text.encode('utf-8'))} bayt · {_e(e.sha256[:16])}</small></div></div>")
        parts.append(f"<details><summary>{_e(msg('r.raw'))} — {_e(m.source.location)}</summary>"
                     f"<pre>{_e(' '.join(e.raw_hex[i:i + 2] for i in range(0, len(e.raw_hex), 2)))}</pre></details>")
    parts.append(f"<h2>{_e(msg('r.limits'))}</h2><div class='limits'>{_e(msg('r.limits_text'))}</div>")
    parts.append("</div></body></html>")
    return "\n".join(parts)
