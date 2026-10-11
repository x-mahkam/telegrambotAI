"""
Real monitorlar EDID'lari bo'yicha mustaqil tekshiruv.

tests/data/linuxhw/ — linuxhw/EDID ochiq bazasidan (CC BY 4.0) olingan 27 ta
fayl. Har birida xom EDID (hex) va mustaqil `edid-decode` vositasining
(Linux/v4l-utils) to'liq chiqishi bor. Dekoderimiz natijasi edid-decode bilan
solishtiriladi.

Ishlab chiqish jarayonida xuddi shu skript bazadan tasodifiy tanlangan
2000 ta EDID'da ishlatilgan: barcha taqqoslanadigan maydonlar mos kelgan
(farqlar faqat bazadagi fayl ichida bir nechta EDID aralashgan hollarda).
"""
import glob
import os
import re

import pytest

from edid_reader.decoder import decode_edid
from edid_reader.sources import read_file

DATA = os.path.join(os.path.dirname(__file__), "data", "linuxhw")
FILES = sorted(glob.glob(os.path.join(DATA, "*.txt")))


def reference(text: str) -> dict:
    main = text.split("----------------", 1)[1].split("Warnings:")[0].split("Failures:")[0]
    ref = {}

    def g(rx, conv=str):
        m = re.search(rx, text)
        return conv(m.group(1)) if m else None

    ref["mfg"] = g(r"Manufacturer: (\S+)")
    ref["model"] = g(r"Model: (\d+)", int)
    ref["name"] = g(r"Display Product Name: '(.*)'")
    m = re.search(r"Made in: week (\d+) of (\d+)", text)
    ref["week_year"] = (int(m.group(1)), int(m.group(2))) if m else None
    m = re.search(r"Maximum image size: (\d+) cm x (\d+) cm", text)
    ref["size"] = (int(m.group(1)), int(m.group(2))) if m else None
    ref["bpc"] = g(r"Bits per primary color channel: (\d+)", int)
    ref["version"] = g(r"EDID Structure Version & Revision: (\d\.\d)")
    m = re.search(r"DTD 1:\s+(\d+)x(\d+)(i?)\s+([\d.]+) Hz", text)
    ref["dtd1"] = (int(m.group(1)), int(m.group(2)), float(m.group(4))) if m else None
    ref["timings"] = [(int(a), int(b), float(d)) for a, b, i, d in
                      re.findall(r"(\d+)x(\d+)(i?)\s+([\d.]+) Hz", main) if not i]
    return ref


def test_corpus_present():
    assert len(FILES) >= 20


@pytest.mark.parametrize("path", FILES, ids=[os.path.basename(f)[:40] for f in FILES])
def test_against_edid_decode(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    ref = reference(text)
    e = decode_edid(read_file(path).data)

    assert e.checksum_ok
    assert e.manufacturer_id == ref["mfg"]
    assert e.product_code == ref["model"]
    assert e.version == ref["version"]
    if ref["name"] is not None:
        assert (e.name or "") == ref["name"]
    if ref["week_year"]:
        assert (e.week, e.year) == ref["week_year"]
    if ref["size"]:
        assert e.size_cm == ref["size"]
    if ref["bpc"]:
        assert e.bit_depth == ref["bpc"]
    if ref["dtd1"]:
        assert (e.native.width, e.native.height) == ref["dtd1"][:2]
        assert e.native.refresh_hz == pytest.approx(ref["dtd1"][2], abs=0.01)
    if ref["timings"]:
        rmax = max(ref["timings"], key=lambda t: (t[0] * t[1], t[2]))
        assert (e.max_resolution.width, e.max_resolution.height) == rmax[:2]
        assert e.max_refresh_hz == pytest.approx(max(t[2] for t in ref["timings"]), abs=0.6)
        # tabiiy ruxsatdagi maksimal chastota
        nat = [t[2] for t in ref["timings"] if t[:2] == (e.native.width, e.native.height)]
        assert e.max_refresh_native_hz == pytest.approx(max(nat), abs=0.6)
