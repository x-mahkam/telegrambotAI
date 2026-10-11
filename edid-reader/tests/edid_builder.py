"""Sinov uchun sintetik EDID yasash (maydonlar aniq ma'lum bo'lishi uchun)."""
from __future__ import annotations


def mfg_bytes(code: str) -> bytes:
    v = 0
    for ch in code:
        v = (v << 5) | (ord(ch) - ord("A") + 1)
    return v.to_bytes(2, "big")


def dtd(w: int, h: int, hb: int, vb: int, clk_khz: int, wmm: int = 0, hmm: int = 0, interlaced=False) -> bytes:
    c = clk_khz // 10
    b = bytearray(18)
    b[0], b[1] = c & 0xFF, c >> 8
    b[2], b[3] = w & 0xFF, hb & 0xFF
    b[4] = ((w >> 8) << 4) | (hb >> 8)
    b[5], b[6] = h & 0xFF, vb & 0xFF
    b[7] = ((h >> 8) << 4) | (vb >> 8)
    b[8], b[9], b[10], b[11] = 48, 32, 0x35, 0
    b[12], b[13] = wmm & 0xFF, hmm & 0xFF
    b[14] = ((wmm >> 8) << 4) | (hmm >> 8)
    b[17] = 0x1E | (0x80 if interlaced else 0)
    return bytes(b)


def text_desc(tag: int, s: str) -> bytes:
    body = s.encode("ascii")[:13]
    if len(body) < 13:
        body += b"\x0a" + b" " * (12 - len(body))
    return bytes([0, 0, 0, tag, 0]) + body


def range_desc(vmin, vmax, hmin, hmax, clk_mhz, flags=0) -> bytes:
    return bytes([0, 0, 0, 0xFD, flags, vmin, vmax, hmin, hmax, clk_mhz // 10, 0x00, 0x0A] + [0x20] * 6)


def dummy_desc() -> bytes:
    return bytes([0, 0, 0, 0x10] + [0] * 14)


def fix_checksum(block: bytearray) -> bytearray:
    block[127] = (256 - sum(block[:127]) % 256) % 256
    return block


def base_block(*, mfg="DEL", product=0xA0F2, serial=12345678, week=10, year=2025,
               version=(1, 4), input_byte=0xA5, size_cm=(53, 30), gamma=120, features=0x0A,
               descriptors=None, n_ext=0, chroma=None, est=(0x21, 0x08, 0x00), std=None) -> bytearray:
    b = bytearray(128)
    b[0:8] = bytes([0, 255, 255, 255, 255, 255, 255, 0])
    b[8:10] = mfg_bytes(mfg)
    b[10:12] = product.to_bytes(2, "little")
    b[12:16] = serial.to_bytes(4, "little")
    b[16], b[17] = week, year - 1990
    b[18], b[19] = version
    b[20] = input_byte
    b[21], b[22] = size_cm
    b[23], b[24] = gamma, features
    # Xromatiklik: sRGB'ga yaqin qiymatlar (10 bitli)
    ch = chroma or {"r": (655, 338), "g": (307, 614), "b": (154, 61), "w": (320, 337)}
    lo1 = ((ch["r"][0] & 3) << 6) | ((ch["r"][1] & 3) << 4) | ((ch["g"][0] & 3) << 2) | (ch["g"][1] & 3)
    lo2 = ((ch["b"][0] & 3) << 6) | ((ch["b"][1] & 3) << 4) | ((ch["w"][0] & 3) << 2) | (ch["w"][1] & 3)
    b[25], b[26] = lo1, lo2
    for i, k in enumerate(["r", "g", "b", "w"]):
        b[27 + 2 * i] = ch[k][0] >> 2
        b[28 + 2 * i] = ch[k][1] >> 2
    b[35:38] = bytes(est)
    stds = list(std or []) + [(1, 1)] * 8
    for i in range(8):
        b[38 + 2 * i], b[39 + 2 * i] = stds[i]
    descs = list(descriptors or []) + [dummy_desc()] * 4
    for i in range(4):
        b[54 + 18 * i:72 + 18 * i] = descs[i]
    b[126] = n_ext
    return fix_checksum(b)


def cta_block(data_blocks: bytes = b"", dtds: list[bytes] | None = None, flags=0xF0) -> bytearray:
    b = bytearray(128)
    b[0], b[1] = 0x02, 0x03
    b[2] = 4 + len(data_blocks)
    b[3] = flags
    b[4:4 + len(data_blocks)] = data_blocks
    off = b[2]
    for d in dtds or []:
        b[off:off + 18] = d
        off += 18
    return fix_checksum(b)


def displayid_block(timings: list[bytes], tag=0x03) -> bytearray:
    b = bytearray(128)
    b[0] = 0x70
    b[1] = 0x12 if tag == 0x03 else 0x20
    payload = b"".join(timings)
    blk = bytes([tag, 0, len(payload)]) + payload
    b[2] = len(blk)
    b[5:5 + len(blk)] = blk
    return fix_checksum(b)


def did_timing(w, h, hb, vb, clk_units, preferred=False) -> bytes:
    """DisplayID Type I/VII 20 baytli deskriptor (qiymatlar −1 bilan saqlanadi)."""
    c = clk_units - 1
    t = bytearray(20)
    t[0:3] = c.to_bytes(3, "little")
    t[3] = 0x80 if preferred else 0
    for off, v in ((4, w), (6, hb), (8, 48), (10, 32), (12, h), (14, vb), (16, 3), (18, 5)):
        t[off:off + 2] = (v - 1).to_bytes(2, "little")
    return bytes(t)
