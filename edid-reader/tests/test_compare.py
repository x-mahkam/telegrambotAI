from datetime import date

import pytest
from edid_builder import base_block, cta_block, dtd, text_desc

from edid_reader.compare import Declared, compare, model_match, parse_resolution
from edid_reader.decoder import decode_edid

TODAY = date(2026, 1, 15)
DTD_1080P = dtd(1920, 1080, 280, 45, 148_500, 527, 296)


def info(mfg="DEL", name="DELL P2422H", year=2025, week=40, descs=None, ext=b"", **kw):
    d = descs or [DTD_1080P, text_desc(0xFC, name), text_desc(0xFF, "CN0ABC123")]
    b = bytes(base_block(mfg=mfg, year=year, week=week, descriptors=d, n_ext=1 if ext else 0, **kw))
    return decode_edid(b + ext)


GOOD = Declared("Dell", "P2422H", 23.8, (1920, 1080), 60, 8, "CN0ABC123")


def statuses(c):
    return c.status_map()


def test_all_match():
    c = compare(info(), GOOD, TODAY)
    assert c.verdict == "MOS", c.checks
    s = statuses(c)
    for k in ("brand", "model", "diagonal", "resolution", "refresh", "bit_depth", "serial"):
        assert s[k] == "ok"


def test_no_declaration():
    c = compare(info(), Declared(), TODAY)
    assert c.verdict == "SOLISHTIRILMADI"
    assert set(statuses(c)) == {"integrity", "vendor_code", "age"}


def test_wrong_brand_is_fail():
    c = compare(info(mfg="SAM"), GOOD, TODAY)
    assert statuses(c)["brand"] == "fail" and c.verdict == "MOS_EMAS"


def test_oem_code_is_check_not_fail():
    c = compare(info(mfg="TPV"), Declared(brand="Philips"), TODAY)
    assert statuses(c)["brand"] == "check"


def test_generic_vendor_code_flagged():
    c = compare(info(mfg="RTK"), Declared(), TODAY)
    assert statuses(c)["vendor_code"] == "check"
    assert c.verdict == "TEKSHIRISH_KERAK"


def test_unregistered_vendor_code_flagged():
    c = compare(info(mfg="QQZ"), Declared(), TODAY)
    assert statuses(c)["vendor_code"] == "check"


def test_lower_native_resolution_is_fail_even_if_accepted():
    # Panel 1366×768, lekin 1080p'ni qabul qiladi (CTA VIC 16) — "1920x1080 monitor" emas
    descs = [dtd(1366, 768, 160, 38, 85_500, 410, 230), text_desc(0xFC, "D1918H")]
    ext = bytes(cta_block(bytes([0x40 | 1, 16])))
    e = info(descs=descs, ext=ext, size_cm=(41, 23))
    c = compare(e, Declared(resolution=(1920, 1080)), TODAY)
    chk = [x for x in c.checks if x.key == "resolution"][0]
    assert chk.status == "fail" and "QABUL QILADI" in chk.note


def test_refresh_claim_too_high():
    c = compare(info(), Declared(refresh_hz=75), TODAY)
    assert statuses(c)["refresh"] == "fail"


def test_refresh_tolerance():
    # 1920×1080@74.97 (EDID aniq qiymati) e'lon qilingan "75 Hz" ga mos
    descs = [dtd(1920, 1080, 280, 45, 185_550, 527, 296), text_desc(0xFC, "X")]
    c = compare(info(descs=descs), Declared(refresh_hz=75), TODAY)
    assert statuses(c)["refresh"] == "ok"


@pytest.mark.parametrize("declared,expected", [(23.8, "ok"), (24.0, "ok"), (24.5, "check"), (27.0, "fail"), (21.5, "fail")])
def test_diagonal_tolerance(declared, expected):
    c = compare(info(), Declared(diagonal_in=declared), TODAY)
    assert statuses(c)["diagonal"] == expected


def test_bit_depth_claims():
    e8 = info()                                             # EDID 1.4, 8 bit
    assert statuses(compare(e8, Declared(bit_depth=10), TODAY))["bit_depth"] == "fail"
    assert statuses(compare(e8, Declared(bit_depth=8), TODAY))["bit_depth"] == "ok"
    e6 = info(input_byte=0x95)                              # 6 bit, DisplayPort
    assert statuses(compare(e6, Declared(), TODAY))["bit_depth"] == "check"
    e13 = info(version=(1, 3), input_byte=0x80)             # 1.3 — chuqurlik noma'lum
    assert statuses(compare(e13, Declared(bit_depth=8), TODAY))["bit_depth"] == "check"


def test_old_stock_flagged():
    c = compare(info(year=2021, week=5), Declared(), TODAY, max_age_years=2)
    assert statuses(c)["age"] == "check"
    c = compare(info(year=2025, week=30), Declared(), TODAY, max_age_years=2)
    assert statuses(c)["age"] == "info"


def test_serial_mismatch():
    c = compare(info(), Declared(serial="CN0ZZZ999"), TODAY)
    assert statuses(c)["serial"] == "fail"


@pytest.mark.parametrize("edid,decl,exp", [
    ("DELL P2422H", "P2422H", "ok"),
    ("DELL P2422H", "Dell P2422H 23.8\"", "ok"),
    ("S24F350", "Samsung S24F350FHI", "ok"),
    ("LG ULTRAGEAR", "27GN800-B", "check"),        # EDID nomida model kodi yo'q
    ("DELL E2220H", "P2422H", "fail"),
    ("VA2249 Series", "VA2249S", "ok"),
])
def test_model_match(edid, decl, exp):
    assert model_match(edid, decl) == exp


def test_parse_resolution():
    assert parse_resolution("1920x1080") == (1920, 1080)
    assert parse_resolution(" 2560 × 1440 ") == (2560, 1440)
    assert parse_resolution("1080p") is None


def test_declared_from_dict():
    d = Declared.from_dict({"brand": "Dell", "resolution": "1920x1080", "diagonal_in": "23.8", "bit_depth": 8})
    assert d.resolution == (1920, 1080) and d.diagonal_in == 23.8 and d.bit_depth == 8
