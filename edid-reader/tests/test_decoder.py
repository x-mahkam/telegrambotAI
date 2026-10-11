import pytest
from edid_builder import (
    base_block,
    cta_block,
    did_timing,
    displayid_block,
    dtd,
    range_desc,
    text_desc,
)

from edid_reader.decoder import (
    GAMUTS,
    EdidError,
    _diagonal_in,
    decode_edid,
    gamut_metrics,
)

# 1920×1080@60: CEA/VESA standart vaqtlari, 148.5 MHz, H_total 2200, V_total 1125
DTD_1080P = dtd(1920, 1080, 280, 45, 148_500, 527, 296)


def make(**kw):
    descs = kw.pop("descriptors", [DTD_1080P, text_desc(0xFC, "DELL P2422H"),
                                   text_desc(0xFF, "ABC123XYZ"), range_desc(48, 76, 30, 85, 170)])
    return base_block(descriptors=descs, **kw)


def test_identification():
    e = decode_edid(bytes(make()))
    assert e.manufacturer_id == "DEL"
    assert e.manufacturer_name == "Dell Inc."
    assert e.product_code == 0xA0F2 and e.product_code_hex == "A0F2"
    assert e.serial_number == 12345678
    assert e.serial_string == "ABC123XYZ"
    assert e.name == "DELL P2422H"
    assert (e.week, e.year) == (10, 2025)
    assert e.version == "1.4"
    assert e.checksum_ok


def test_basic_params_v14():
    e = decode_edid(bytes(make()))
    assert e.digital and e.bit_depth == 8 and e.bit_depth_source == "EDID 1.4"
    assert e.interface == "DisplayPort"
    assert e.size_cm == (53, 30)
    assert e.image_mm == (527, 296)
    assert e.diagonal_in == 23.8           # √(527² + 296²) / 25.4 = 23.80
    assert e.gamma == 2.2


@pytest.mark.parametrize("code,bits", [(1, 6), (2, 8), (3, 10), (4, 12)])
def test_bit_depth_codes(code, bits):
    e = decode_edid(bytes(make(input_byte=0x80 | (code << 4) | 5)))
    assert e.bit_depth == bits


def test_native_and_refresh():
    e = decode_edid(bytes(make()))
    assert (e.native.width, e.native.height) == (1920, 1080)
    assert e.native.refresh_hz == pytest.approx(60.0, abs=1e-3)   # 148.5e6 / (2200·1125)
    assert e.native.preferred
    assert e.max_refresh_native_hz == 60.0


def test_range_limits():
    e = decode_edid(bytes(make()))
    assert e.range_limits == {"v_min_hz": 48, "v_max_hz": 76, "h_min_khz": 30,
                              "h_max_khz": 85, "max_pixel_clock_mhz": 170}


def test_range_limits_offsets_v14():
    # EDID 1.4: offset bayti 1-bit (0x02) — maks. vertikal chastotaga +255 → 5 + 255 = 260 Hz
    d = make(descriptors=[DTD_1080P, range_desc(48, 5, 30, 85, 600, flags=0x02)])
    assert decode_edid(bytes(d)).range_limits["v_max_hz"] == 260


def test_established_and_standard_timings():
    # 1920×1080@75 standart timing: (1920/8 − 31) = 209, 16:9 → 0b11, 75−60 = 15
    e = decode_edid(bytes(make(std=[(209, 0xC0 | 15)])))
    assert any(m.source == "STD" and (m.width, m.height, m.refresh_hz) == (1920, 1080, 75.0) for m in e.modes)
    assert any(m.source == "EST" and (m.width, m.height) == (640, 480) for m in e.modes)
    assert e.max_refresh_native_hz == 75.0


def test_checksum_bad_detected():
    b = make()
    b[100] ^= 0xFF
    e = decode_edid(bytes(b))
    assert not e.checksum_ok and "checksum_bad" in e.warnings


def test_invalid_header():
    with pytest.raises(EdidError):
        decode_edid(b"\x00" * 128)
    with pytest.raises(EdidError):
        decode_edid(b"\x00\xff")


def test_truncated_extension():
    e = decode_edid(bytes(make(n_ext=1)))
    assert e.truncated and "truncated" in e.warnings


def test_cta_vics_hdmi_deep_color_and_hdr():
    vdb = bytes([0x40 | 3, 16, 97, 63])                         # VIC 16, 97 (4K60), 63 (1080p120)
    hdmi = bytes([0x60 | 7, 0x03, 0x0C, 0x00, 0x10, 0x00, 0x30, 60])   # DC_30, DC_36, 300 MHz
    hdr = bytes([0xE0 | 6, 6, 0x05, 0x01, 0x60, 0x40, 0x10])    # SDR+PQ, CV 96 → 50·2³ = 400
    ext = cta_block(vdb + hdmi + hdr)
    e = decode_edid(bytes(make(version=(1, 3), input_byte=0x80, n_ext=1)) + bytes(ext))
    assert e.bit_depth == 12 and e.bit_depth_source.startswith("HDMI")
    assert e.hdmi["max_tmds_mhz"] == 300
    assert e.hdr["max_luminance_cd_m2"] == 400.0
    assert "PQ (SMPTE ST 2084)" in e.hdr["eotf"]
    assert e.max_resolution.width == 3840
    assert e.max_refresh_native_hz == 120.0                     # VIC 63
    assert e.extensions == ["CTA-861 rev3"]


def test_displayid_type1_high_refresh():
    # 2560×1440@144: H_total 2720, V_total 1525 → f_pix = 2720·1525·144 = 597.312 MHz
    t = did_timing(2560, 1440, 160, 85, 59_731, preferred=True)   # 10 kHz birlikda
    base = make(descriptors=[dtd(2560, 1440, 160, 41, 241_500, 597, 336), text_desc(0xFC, "XV272U")],
                n_ext=1)
    e = decode_edid(bytes(base) + bytes(displayid_block([t])))
    assert e.extensions == ["DisplayID 1.2"]
    assert e.max_refresh_native_hz == pytest.approx(144.0, abs=0.01)


def test_displayid_type7_units():
    t = did_timing(3840, 2160, 80, 62, 1_188_000)              # Type VII: 1 kHz birlikda
    base = make(descriptors=[dtd(3840, 2160, 160, 62, 533_250, 597, 336)], n_ext=1)
    e = decode_edid(bytes(base) + bytes(displayid_block([t], tag=0x22)))
    m = [m for m in e.modes if m.source == "DisplayID T7"][0]
    assert m.refresh_hz == pytest.approx(1_188_000e3 / (3920 * 2222), abs=0.01)


def test_size_from_cm_when_dtd_size_bogus():
    # DTD'da 16×9 mm (nisbat sifatida yozilgan) — sm qiymatidan foydalanilishi kerak
    e = decode_edid(bytes(make(descriptors=[dtd(1920, 1080, 280, 45, 148_500, 16, 9)])))
    assert e.image_mm is None and e.diagonal_in == _diagonal_in(530, 300)


def test_gamut_metrics_srgb_reference():
    c = {"red": GAMUTS["sRGB"][0], "green": GAMUTS["sRGB"][1], "blue": GAMUTS["sRGB"][2], "white": (0.3127, 0.329)}
    g = gamut_metrics(c)
    assert g["sRGB_coverage_pct"] == pytest.approx(100.0, abs=0.1)
    assert g["sRGB_area_pct"] == pytest.approx(100.0, abs=0.1)
    # Qo'lda hisob: S_sRGB = 0.11205, S_NTSC = 0.15820 → 70.8%
    # (amaliyotda yaxlitlab "sRGB ≈ 72% NTSC" deyiladi)
    assert g["NTSC1953_area_pct"] == pytest.approx(70.8, abs=0.05)


def test_gamut_wide_panel_full_srgb_coverage():
    c = {"red": GAMUTS["DCI-P3"][0], "green": GAMUTS["DCI-P3"][1], "blue": GAMUTS["DCI-P3"][2], "white": (0.3127, 0.329)}
    g = gamut_metrics(c)
    assert g["DCI-P3_coverage_pct"] == pytest.approx(100.0, abs=0.1)
    assert g["sRGB_coverage_pct"] == pytest.approx(100.0, abs=0.1)
    assert g["sRGB_area_pct"] > 120
