"""
EDID dekoderi (operatsion tizimga bog'liq emas).

Qo'llab-quvvatlanadi:
  * Asosiy blok: EDID 1.3 va 1.4 (VESA E-EDID Standard Release A Rev. 2, 2006)
  * CTA-861 kengaytmasi (CTA-861-G): Video/Vendor-Specific (HDMI, HDMI Forum)/
    Colorimetry/HDR Static Metadata/YCbCr 4:2:0 bloklari va DTD'lar
  * DisplayID kengaytmasi (1.3 va 2.0): Type I va Type VII aniq vaqt deskriptorlari

Natija — EdidInfo obyekti, uni .to_dict() bilan JSON'ga aylantirish mumkin.

MUHIM CHEKLOV: EDID — monitorning O'ZI haqida bergan ma'lumoti (proshivkaga
yozilgan). U panel turini, haqiqiy rang chuqurligini (masalan, 6-bit+FRC)
yoki haqiqiy rang qamrovini bevosita kafolatlamaydi. Rang chuqurligi —
monitor QABUL QILADIGAN signal chuqurligi.
"""
from __future__ import annotations

import hashlib
import math
from dataclasses import asdict, dataclass, field

from .timings import CTA_VICS, ESTABLISHED_I_II, ESTABLISHED_III, HDMI_VICS
from .vendors import manufacturer_name

EDID_HEADER = bytes([0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00])

INTERFACES = {1: "DVI", 2: "HDMI-a", 3: "HDMI-b", 4: "MDDI", 5: "DisplayPort"}
BIT_DEPTHS = {1: 6, 2: 8, 3: 10, 4: 12, 5: 14, 6: 16}   # EDID 1.4, 14h bayt, 6..4 bitlar

# CIE 1931 xy koordinatalarida rang fazolari uchburchaklari (R, G, B)
# Manbalar: IEC 61966-2-1 (sRGB = ITU-R BT.709), SMPTE EG 432-1 (DCI-P3),
# Adobe RGB (1998), FCC 1953 (NTSC).
GAMUTS = {
    "sRGB": ((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)),
    "DCI-P3": ((0.680, 0.320), (0.265, 0.690), (0.150, 0.060)),
    "AdobeRGB": ((0.640, 0.330), (0.210, 0.710), (0.150, 0.060)),
    "NTSC1953": ((0.670, 0.330), (0.210, 0.710), (0.140, 0.080)),
}


class EdidError(ValueError):
    """EDID ma'lumoti noto'g'ri yoki to'liq emas."""


@dataclass
class Mode:
    """Bitta video rejim (ruxsat + kadrlar chastotasi)."""
    width: int
    height: int
    refresh_hz: float
    interlaced: bool = False
    source: str = ""                 # "DTD", "CTA VIC 16", "EST", "STD", "DisplayID", ...
    pixel_clock_khz: int | None = None
    preferred: bool = False

    @property
    def pixels(self) -> int:
        return self.width * self.height

    def label(self) -> str:
        return f"{self.width}x{self.height}{'i' if self.interlaced else ''}@{self.refresh_hz:.2f}Hz"


@dataclass
class EdidInfo:
    raw_hex: str
    sha256: str
    blocks: int
    checksums_ok: list[bool]
    truncated: bool
    version: str
    manufacturer_id: str
    manufacturer_name: str | None
    product_code: int
    serial_number: int | None
    serial_string: str | None
    week: int | None
    year: int | None
    is_model_year: bool
    name: str | None
    texts: list[str]
    digital: bool
    bit_depth: int | None
    bit_depth_source: str | None
    interface: str | None
    size_cm: tuple[int, int] | None
    image_mm: tuple[int, int] | None
    diagonal_in: float | None
    aspect_ratio: float | None
    gamma: float | None
    srgb_default: bool
    chromaticity: dict[str, tuple[float, float]]
    gamut: dict[str, float] | None
    range_limits: dict | None
    native: Mode | None
    modes: list[Mode]
    max_resolution: Mode | None
    max_refresh_native_hz: float | None
    max_refresh_hz: float | None
    extensions: list[str]
    hdmi: dict | None
    hdr: dict | None
    colorimetry: list[str]
    warnings: list[str] = field(default_factory=list)

    @property
    def checksum_ok(self) -> bool:
        return all(self.checksums_ok)

    @property
    def product_code_hex(self) -> str:
        return f"{self.product_code:04X}"

    def to_dict(self, include_modes: bool = True) -> dict:
        d = asdict(self)
        d["product_code_hex"] = self.product_code_hex
        d["checksum_ok"] = self.checksum_ok
        if not include_modes:
            d.pop("modes")
        return d


# --------------------------------------------------------------------------
# Yordamchi funksiyalar
# --------------------------------------------------------------------------

def _le16(b: bytes, i: int) -> int:
    return b[i] | (b[i + 1] << 8)


def _text(b: bytes) -> str:
    """Displey deskriptoridagi 13 baytli matn (0x0A bilan tugaydi, 0x20 bilan to'ldiriladi)."""
    s = b.split(b"\x0a", 1)[0].decode("cp437", errors="replace")
    return "".join(ch for ch in s if ch.isprintable()).strip()


def _manufacturer_id(b: bytes) -> str:
    """08h–09h baytlar: 3 ta 5-bitli harf, 1='A' ... 26='Z' (big-endian)."""
    v = (b[8] << 8) | b[9]
    letters = [(v >> 10) & 0x1F, (v >> 5) & 0x1F, v & 0x1F]
    return "".join(chr(ord("A") + c - 1) if 1 <= c <= 26 else "@" for c in letters)


def _chromaticity(b: bytes) -> dict[str, tuple[float, float]]:
    """19h–22h baytlar: 10 bitli x, y koordinatalar (qiymat / 1024)."""
    lo1, lo2 = b[25], b[26]

    def c(hi: int, lo: int) -> float:
        return round(((hi << 2) | lo) / 1024.0, 4)

    return {
        "red": (c(b[27], (lo1 >> 6) & 3), c(b[28], (lo1 >> 4) & 3)),
        "green": (c(b[29], (lo1 >> 2) & 3), c(b[30], lo1 & 3)),
        "blue": (c(b[31], (lo2 >> 6) & 3), c(b[32], (lo2 >> 4) & 3)),
        "white": (c(b[33], (lo2 >> 2) & 3), c(b[34], lo2 & 3)),
    }


def _area(poly) -> float:
    """Ko'pburchak yuzasi (Gauss "shoelace" formulasi)."""
    s = 0.0
    for i in range(len(poly)):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % len(poly)]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2.0


def _ccw(poly):
    s = sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1]
            for i in range(len(poly)))
    return list(poly) if s > 0 else list(reversed(poly))


def _clip(subject, clip):
    """Sutherland–Hodgman: qavariq ko'pburchaklar kesishmasi."""
    out = _ccw(subject)
    clip = _ccw(clip)
    for i in range(len(clip)):
        a, b = clip[i], clip[(i + 1) % len(clip)]
        inp, out = out, []
        if not inp:
            break

        def inside(p):
            return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0

        def inter(p, q):
            x1, y1, x2, y2 = p[0], p[1], q[0], q[1]
            x3, y3, x4, y4 = a[0], a[1], b[0], b[1]
            den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
            t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
            return (x1 + t * (x2 - x1), y1 + t * (y2 - y1))

        for j in range(len(inp)):
            p, q = inp[j], inp[(j + 1) % len(inp)]
            if inside(q):
                if not inside(p):
                    out.append(inter(p, q))
                out.append(q)
            elif inside(p):
                out.append(inter(p, q))
    return out


def gamut_metrics(chroma: dict[str, tuple[float, float]]) -> dict[str, float] | None:
    """EDID'da e'lon qilingan birlamchi ranglar uchburchagi bo'yicha ko'rsatkichlar.

    * <name>_coverage_pct — standart rang fazosining qancha qismi qoplanadi
      (kesishma yuzasi / standart yuzasi), CIE 1931 xy tekisligida.
    * NTSC1953_area_pct — yuzalar nisbati (ishlab chiqaruvchilar "72% NTSC"
      deb yozadigan ko'rsatkich).
    Eslatma: xy tekisligi perseptiv bir tekis emas; ko'rsatkichlar faqat
    e'lon qilingan qiymatlarni solishtirish uchun.
    """
    tri = [chroma["red"], chroma["green"], chroma["blue"]]
    a = _area(tri)
    if a < 1e-4:
        return None
    res = {}
    for name, ref in GAMUTS.items():
        inter = _clip(tri, list(ref))
        res[f"{name}_coverage_pct"] = round(100 * _area(inter) / _area(ref), 1) if len(inter) >= 3 else 0.0
    res["NTSC1953_area_pct"] = round(100 * a / _area(GAMUTS["NTSC1953"]), 1)
    res["sRGB_area_pct"] = round(100 * a / _area(GAMUTS["sRGB"]), 1)
    return res


def _dtd(b: bytes, source: str) -> tuple[Mode | None, tuple[int, int] | None]:
    """18 baytli Detailed Timing Descriptor (EDID 1.4, 3.10.2-bo'lim)."""
    clk = _le16(b, 0)                                   # 10 kHz birlikda
    if clk == 0:
        return None, None
    ha = b[2] | ((b[4] & 0xF0) << 4)
    hb = b[3] | ((b[4] & 0x0F) << 8)
    va = b[5] | ((b[7] & 0xF0) << 4)
    vb = b[6] | ((b[7] & 0x0F) << 8)
    wmm = b[12] | ((b[14] & 0xF0) << 4)
    hmm = b[13] | ((b[14] & 0x0F) << 8)
    interlaced = bool(b[17] & 0x80)
    if ha == 0 or va == 0:
        return None, None
    # Kadrlar chastotasi f = f_pixel / (H_total × V_total).
    # Interlaced rejimda V_active bitta maydon (field) uchun; maydonlar chastotasi
    # f_pixel / (H_total × (V_active + V_blank + 0.5)).
    vt = va + vb + (0.5 if interlaced else 0)
    refresh = clk * 10_000 / ((ha + hb) * vt)
    m = Mode(ha, va * 2 if interlaced else va, round(refresh, 3), interlaced, source, clk * 10)
    return m, ((wmm, hmm) if wmm and hmm else None)


def _diagonal_in(w_mm: float, h_mm: float) -> float:
    """Diagonal (dyuym) = √(w² + h²) / 25.4."""
    return round(math.hypot(w_mm, h_mm) / 25.4, 1)


# --------------------------------------------------------------------------
# Asosiy funksiya
# --------------------------------------------------------------------------

def decode_edid(data: bytes) -> EdidInfo:
    data = bytes(data)
    if len(data) < 128:
        raise EdidError(f"EDID juda qisqa: {len(data)} bayt (kamida 128 kerak)")
    if data[:8] != EDID_HEADER:
        raise EdidError("EDID sarlavhasi (00 FF FF FF FF FF FF 00) topilmadi")

    base = data[:128]
    n_ext = base[126]
    nblocks = len(data) // 128
    truncated = nblocks < 1 + n_ext
    nblocks = min(nblocks, 1 + n_ext)
    blocks = [data[i * 128:(i + 1) * 128] for i in range(nblocks)]
    checksums = [sum(bl) % 256 == 0 for bl in blocks]

    major, minor = base[18], base[19]
    v14 = (major, minor) >= (1, 4)
    warnings: list[str] = []
    if not all(checksums):
        warnings.append("checksum_bad")
    if truncated:
        warnings.append("truncated")

    # --- Identifikatsiya (08h–11h) ---
    mid = _manufacturer_id(base)
    product = _le16(base, 10)
    serial = int.from_bytes(base[12:16], "little") or None
    week, yb = base[16], base[17]
    is_model_year = v14 and week == 0xFF
    year = 1990 + yb if yb else None
    week = None if week in (0, 0xFF) else week

    # --- Asosiy parametrlar (14h–18h) ---
    digital = bool(base[20] & 0x80)
    bit_depth = bit_src = interface = None
    if digital and v14:
        bit_depth = BIT_DEPTHS.get((base[20] >> 4) & 7)
        bit_src = "EDID 1.4" if bit_depth else None
        interface = INTERFACES.get(base[20] & 0x0F)
    size_cm = aspect = None
    if base[21] and base[22]:
        size_cm = (base[21], base[22])
        aspect = round(base[21] / base[22], 3)
    elif v14 and base[21]:
        aspect = round((base[21] + 99) / 100, 3)            # landshaft nisbati
    elif v14 and base[22]:
        aspect = round(100 / (base[22] + 99), 3)            # portret nisbati
    gamma = None if base[23] == 0xFF else round((base[23] + 100) / 100, 2)
    srgb_default = bool(base[24] & 0x04)
    chroma = _chromaticity(base)

    modes: list[Mode] = []
    name = serial_str = None
    texts: list[str] = []
    rlimits = None
    native: Mode | None = None
    native_mm = None

    # --- Established I/II (23h–25h) ---
    for (bi, bit), (w, h, r, il) in ESTABLISHED_I_II.items():
        if base[35 + bi] & (1 << bit):
            modes.append(Mode(w, h, r, il, "EST"))

    # --- Standard timings (26h–35h) va FAh deskriptor ---
    def std_timing(b0: int, b1: int):
        if b0 in (0x00, 0x01) and b1 in (0x00, 0x01) or b0 == 0:
            return
        w = (b0 + 31) * 8
        ar = (b1 >> 6) & 3
        if ar == 0:
            h = w * 10 // 16 if (major, minor) >= (1, 3) else w
        else:
            h = w * {1: 3, 2: 4, 3: 9}[ar] // {1: 4, 2: 5, 3: 16}[ar]
        modes.append(Mode(w, h, float((b1 & 0x3F) + 60), False, "STD"))

    for i in range(38, 54, 2):
        std_timing(base[i], base[i + 1])

    # --- 4 ta 18 baytli deskriptor (36h–7Dh) ---
    for k, off in enumerate(range(54, 126, 18)):
        d = base[off:off + 18]
        if d[0] or d[1]:
            m, mm = _dtd(d, "DTD")
            if m:
                if native is None:
                    m.preferred = True
                    native, native_mm = m, mm
                modes.append(m)
            continue
        tag = d[3]
        if tag == 0xFC:
            name = _text(d[5:18])
        elif tag == 0xFF:
            serial_str = _text(d[5:18])
        elif tag == 0xFE:
            t = _text(d[5:18])
            if t:
                texts.append(t)
        elif tag == 0xFD:
            fl = d[4] if v14 else 0
            rlimits = {
                "v_min_hz": d[5] + (255 if fl & 1 else 0),
                "v_max_hz": d[6] + (255 if fl & 2 else 0),
                "h_min_khz": d[7] + (255 if fl & 4 else 0),
                "h_max_khz": d[8] + (255 if fl & 8 else 0),
                "max_pixel_clock_mhz": d[9] * 10,
            }
        elif tag == 0xFA:
            for i in range(5, 17, 2):
                std_timing(d[i], d[i + 1])
        elif tag == 0xF7:
            for i in range(44):
                if d[6 + i // 8] & (0x80 >> (i % 8)):
                    w, h, r, il = ESTABLISHED_III[i]
                    modes.append(Mode(w, h, r, il, "EST3"))
        elif tag == 0xF8:
            for i in range(6, 18, 3):
                c0, c1, c2 = d[i], d[i + 1], d[i + 2]
                if not (c0 or c1 or c2):
                    continue
                lines = ((c0 | ((c1 & 0xF0) << 4)) + 1) * 2
                aw, ah = {0: (4, 3), 1: (16, 9), 2: (16, 10), 3: (15, 9)}[(c1 >> 2) & 3]
                w = 8 * ((lines * aw // ah) // 8)
                for bit, r in ((4, 50.0), (3, 60.0), (2, 75.0), (1, 85.0), (0, 60.0)):
                    if c2 & (1 << bit):
                        modes.append(Mode(w, lines, r, False, "CVT3"))

    # --- Kengaytmalar ---
    exts: list[str] = []
    hdmi = hdr = None
    colorimetry: list[str] = []
    for bl in blocks[1:]:
        tag = bl[0]
        if tag == 0x02:
            exts.append(f"CTA-861 rev{bl[1]}")
            h, hd, col = _parse_cta(bl, modes)
            hdmi = hdmi or h
            hdr = hdr or hd
            colorimetry += col
            if native is None:
                first = next((m for m in modes if m.source == "CTA DTD"), None)
                if first:
                    first.preferred = True
                    native = first
        elif tag == 0x70:
            exts.append(f"DisplayID {bl[1] >> 4}.{bl[1] & 0xF}")
            _parse_displayid(bl, modes)
            if native is None:
                pref = next((m for m in modes if m.source.startswith("DisplayID") and m.preferred), None)
                native = pref
        elif tag == 0xF0:
            exts.append("Block Map")
        else:
            exts.append(f"0x{tag:02X}")

    # HDMI VSDB chuqur rang bitlari (EDID 1.3 da yagona manba)
    if bit_depth is None and hdmi is not None:
        dc = hdmi.get("deep_color_bpc", [])
        bit_depth = max([8] + dc)
        bit_src = "HDMI VSDB (kirish signali)"

    # --- Fizik o'lcham va diagonal ---
    image_mm = None
    diag = None
    if native_mm and size_cm:
        # DTD'dagi mm qiymati asosiy blokdagi sm qiymatiga ±15% mos kelsa — aniqroq.
        if all(abs(native_mm[i] / 10 - size_cm[i]) <= max(1.5, 0.15 * size_cm[i]) for i in (0, 1)):
            image_mm = native_mm
    if image_mm:
        diag = _diagonal_in(*image_mm)
    elif size_cm:
        diag = _diagonal_in(size_cm[0] * 10, size_cm[1] * 10)
    else:
        warnings.append("size_unknown")

    # --- Maksimal qiymatlar ---
    prog = [m for m in modes if not m.interlaced]
    max_res = max(prog, key=lambda m: (m.pixels, m.refresh_hz), default=None)
    max_native_hz = None
    if native:
        same = [m.refresh_hz for m in prog if (m.width, m.height) == (native.width, native.height)]
        max_native_hz = round(max(same), 2) if same else None
    max_hz = round(max((m.refresh_hz for m in prog), default=0), 2) or None

    if not name:
        warnings.append("no_name")
    if serial is None and not serial_str:
        warnings.append("no_serial")

    return EdidInfo(
        raw_hex=data[:nblocks * 128].hex(),
        sha256=hashlib.sha256(data[:nblocks * 128]).hexdigest(),
        blocks=nblocks,
        checksums_ok=checksums,
        truncated=truncated,
        version=f"{major}.{minor}",
        manufacturer_id=mid,
        manufacturer_name=manufacturer_name(mid),
        product_code=product,
        serial_number=serial,
        serial_string=serial_str or None,
        week=week,
        year=year,
        is_model_year=is_model_year,
        name=name or None,
        texts=texts,
        digital=digital,
        bit_depth=bit_depth,
        bit_depth_source=bit_src,
        interface=interface,
        size_cm=size_cm,
        image_mm=image_mm,
        diagonal_in=diag,
        aspect_ratio=aspect,
        gamma=gamma,
        srgb_default=srgb_default,
        chromaticity=chroma,
        gamut=gamut_metrics(chroma),
        range_limits=rlimits,
        native=native,
        modes=modes,
        max_resolution=max_res,
        max_refresh_native_hz=max_native_hz,
        max_refresh_hz=max_hz,
        extensions=exts,
        hdmi=hdmi,
        hdr=hdr,
        colorimetry=sorted(set(colorimetry)),
        warnings=warnings,
    )


# --------------------------------------------------------------------------
# CTA-861 kengaytmasi
# --------------------------------------------------------------------------

def _svd_modes(svds: bytes, source: str, modes: list[Mode]) -> None:
    for b in svds:
        if 1 <= b <= 127:
            vic = b
        elif 129 <= b <= 192:
            vic = b & 0x7F                     # 7-bit — "native" belgisi
        elif 193 <= b <= 253:
            vic = b
        else:
            continue
        if vic in CTA_VICS:
            w, h, r, il = CTA_VICS[vic]
            modes.append(Mode(w, h, round(r, 3), il, f"{source} {vic}"))


def _parse_cta(bl: bytes, modes: list[Mode]):
    d = bl[2]
    hdmi = hdr = None
    colorimetry: list[str] = []
    i = 4
    end = d if 4 <= d <= 127 else 4
    while i < end:
        hdr_b = bl[i]
        tag, ln = hdr_b >> 5, hdr_b & 0x1F
        p = bl[i + 1:i + 1 + ln]
        i += 1 + ln
        if tag == 2:                                     # Video Data Block
            _svd_modes(p, "CTA VIC", modes)
        elif tag == 3 and ln >= 3:                       # Vendor-Specific
            oui = p[0] | (p[1] << 8) | (p[2] << 16)
            if oui == 0x000C03:                          # HDMI 1.x (HDMI LLC)
                hdmi = hdmi or {}
                if ln >= 6:
                    dc = []
                    # 6-bayt: DC_48bit(6) DC_36bit(5) DC_30bit(4) — HDMI 1.4b, 8.3.2
                    if p[5] & 0x10:
                        dc.append(10)
                    if p[5] & 0x20:
                        dc.append(12)
                    if p[5] & 0x40:
                        dc.append(16)
                    hdmi["deep_color_bpc"] = dc
                if ln >= 7 and p[6]:
                    hdmi["max_tmds_mhz"] = p[6] * 5
                if ln >= 8:
                    j = 8
                    if p[7] & 0x80:
                        j += 2
                    if p[7] & 0x40:
                        j += 2
                    if p[7] & 0x20 and j + 1 < ln:
                        vic_len = p[j + 1] >> 5
                        for v in p[j + 2:j + 2 + vic_len]:
                            if v in HDMI_VICS:
                                w, h, r, il = HDMI_VICS[v]
                                modes.append(Mode(w, h, r, il, f"HDMI VIC {v}"))
            elif oui == 0xC45DD8 and ln >= 5:            # HDMI Forum (HDMI 2.x)
                hdmi = hdmi or {}
                hdmi["hdmi_forum"] = True
                if p[4]:
                    hdmi["max_tmds_mhz"] = max(hdmi.get("max_tmds_mhz", 0), p[4] * 5)
        elif tag == 7 and ln >= 1:                       # Extended tag
            et = p[0]
            if et == 5 and ln >= 2:                      # Colorimetry
                names = ["xvYCC601", "xvYCC709", "sYCC601", "opYCC601",
                         "opRGB", "BT2020cYCC", "BT2020YCC", "BT2020RGB"]
                colorimetry += [n for k, n in enumerate(names) if p[1] & (1 << k)]
                if ln >= 3 and p[2] & 0x80:
                    colorimetry.append("DCI-P3")
            elif et == 6 and ln >= 3:                    # HDR Static Metadata
                eotf = [n for k, n in enumerate(["SDR", "HDR (an'anaviy)", "PQ (SMPTE ST 2084)", "HLG"])
                        if p[1] & (1 << k)]
                hdr = {"eotf": eotf}
                # Yorqinlik kodlari: L_max = 50·2^(CV/32) kd/m²,
                # L_min = L_max·(CV/255)²/100  (CTA-861-G, 7.5.13)
                if ln >= 4 and p[3]:
                    hdr["max_luminance_cd_m2"] = round(50 * 2 ** (p[3] / 32), 1)
                if ln >= 5 and p[4]:
                    hdr["max_frame_avg_cd_m2"] = round(50 * 2 ** (p[4] / 32), 1)
                if ln >= 6 and "max_luminance_cd_m2" in hdr:
                    hdr["min_luminance_cd_m2"] = round(hdr["max_luminance_cd_m2"] * (p[5] / 255) ** 2 / 100, 4)
            elif et == 14:                               # YCbCr 4:2:0 Video Data
                _svd_modes(p[1:], "CTA VIC", modes)

    # CTA blok ichidagi DTD'lar
    if 4 <= d <= 127:
        off = d
        while off + 18 <= 127:
            seg = bl[off:off + 18]
            if not (seg[0] or seg[1]):
                break
            m, _ = _dtd(seg, "CTA DTD")
            if m:
                modes.append(m)
            off += 18
    return hdmi, hdr, colorimetry


# --------------------------------------------------------------------------
# DisplayID kengaytmasi (VESA DisplayID 1.3 / 2.0)
# --------------------------------------------------------------------------

def _parse_displayid(bl: bytes, modes: list[Mode]) -> None:
    length = bl[2]
    i, end = 5, min(5 + length, 127)
    while i + 3 <= end:
        tag, ln = bl[i], bl[i + 2]
        p = bl[i + 3:i + 3 + ln]
        i += 3 + ln
        if tag == 0 and ln == 0:
            break
        if tag in (0x03, 0x22):
            # Type I (1.3): piksel chastotasi 10 kHz birlikda; Type VII (2.0): 1 kHz
            unit_khz = 10 if tag == 0x03 else 1
            for k in range(0, len(p) - 19, 20):
                t = p[k:k + 20]
                clk = ((t[0] | (t[1] << 8) | (t[2] << 16)) + 1) * unit_khz
                ha, hb = _le16(t, 4) + 1, _le16(t, 6) + 1
                va, vb = _le16(t, 12) + 1, _le16(t, 14) + 1
                il = bool(t[3] & 0x10)
                r = clk * 1000 / ((ha + hb) * (va + vb))
                modes.append(Mode(ha, va, round(r, 3), il,
                                  "DisplayID T1" if tag == 0x03 else "DisplayID T7", clk,
                                  preferred=bool(t[3] & 0x80)))
