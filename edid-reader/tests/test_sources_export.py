import json
from datetime import datetime

import pytest
from edid_builder import base_block, dtd, text_desc

from edid_reader.cli import main
from edid_reader.compare import Declared, compare
from edid_reader.decoder import decode_edid
from edid_reader.export import (
    MonitorResult,
    Session,
    full_json,
    html_report,
    qr_payload,
    qr_svg,
    qr_text,
)
from edid_reader.sources import EdidSource, read_file, read_windows_registry

EDID_A = bytes(base_block(descriptors=[dtd(1920, 1080, 280, 45, 148_500, 527, 296), text_desc(0xFC, "DELL P2422H")]))
EDID_B = bytes(base_block(mfg="SAM", product=0x0D2C,
                          descriptors=[dtd(2560, 1440, 160, 41, 241_500, 597, 336), text_desc(0xFC, "S27A600")]))


# ---- Soxta winreg moduli (Windows reyestri tuzilishini takrorlaydi) ----------

class FakeKey:
    def __init__(self, node):
        self.node = node

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeWinreg:
    HKEY_LOCAL_MACHINE = "HKLM"

    def __init__(self, tree):
        self.tree = tree

    def OpenKey(self, parent, path):
        node = self.tree if parent == "HKLM" else parent.node
        for part in path.split("\\"):
            if not isinstance(node, dict) or part not in node:
                raise OSError(path)
            node = node[part]
        return FakeKey(node)

    def EnumKey(self, key, i):
        names = [k for k, v in key.node.items() if isinstance(v, dict)]
        if i >= len(names):
            raise OSError
        return names[i]

    def QueryValueEx(self, key, name):
        if name not in key.node or isinstance(key.node[name], dict):
            raise OSError(name)
        return key.node[name], 3


def registry_tree():
    disp = {
        "DELA0F2": {
            "5&1a2b&0&UID4352": {"Device Parameters": {"EDID": EDID_A}, "Control": {}},
            "5&1a2b&0&UID4353": {"Device Parameters": {"EDID": EDID_A}},             # o'sha monitor, boshqa port
        },
        "SAM0D2C": {"4&9f&0&UID1": {"Device Parameters": {"EDID": EDID_B}}},       # ilgari ulangan
        "Default_Monitor": {"1&0&0": {"Device Parameters": {}}},                   # EDID yo'q
    }
    return {"SYSTEM": {"CurrentControlSet": {"Enum": {"DISPLAY": disp}}}}


def test_registry_active_only():
    src = read_windows_registry(winreg=FakeWinreg(registry_tree()))
    assert len(src) == 1 and src[0].active and src[0].hardware_id == "DELA0F2"
    assert src[0].location.endswith("DELA0F2\\5&1a2b&0&UID4352")


def test_registry_all_and_dedupe():
    src = read_windows_registry(include_inactive=True, winreg=FakeWinreg(registry_tree()))
    assert sorted(s.hardware_id for s in src) == ["DELA0F2", "SAM0D2C"]


def test_registry_no_active_falls_back_to_all():
    tree = registry_tree()
    del tree["SYSTEM"]["CurrentControlSet"]["Enum"]["DISPLAY"]["DELA0F2"]["5&1a2b&0&UID4352"]["Control"]
    src = read_windows_registry(winreg=FakeWinreg(tree))
    assert len(src) == 2 and not any(s.active for s in src)


def test_registry_missing_key():
    assert read_windows_registry(winreg=FakeWinreg({})) == []


def test_read_file_binary_and_hex(tmp_path):
    (tmp_path / "a.bin").write_bytes(EDID_A)
    (tmp_path / "a.txt").write_text(" ".join(f"{b:02x}" for b in EDID_A))
    (tmp_path / "a2.txt").write_text(EDID_A.hex())
    for n in ("a.bin", "a.txt", "a2.txt"):
        assert read_file(str(tmp_path / n)).data == EDID_A


# ---- Eksport ---------------------------------------------------------------

def session(declared=None):
    e = decode_edid(EDID_A)
    d = declared or Declared("Dell", "P2422H", 23.8, (1920, 1080), 60, 8)
    m = MonitorResult(1, EdidSource(EDID_A, "file", "a.bin", True), e, compare(e, d))
    return Session(datetime(2026, 10, 11, 12, 0), "PC-042", "Windows 11", d, "INV-0042", [m])


def test_qr_payload_compact_and_complete():
    s = session()
    p = qr_payload(s, s.monitors[0])
    assert p["t"] == "MCEDID" and p["v"] == 1
    assert p["mfg"] == "DEL" and p["pid"] == "A0F2" and p["res"] == [1920, 1080]
    assert p["inv"] == "INV-0042" and p["vd"] in ("MOS", "TEKSHIRISH_KERAK")
    assert p["dc"]["m"] == "P2422H"
    assert len(qr_text(p).encode()) < 900          # telefon ekrandan bemalol o'qishi uchun
    assert None not in p.values()


def test_qr_roundtrip_with_zxing():
    zxingcpp = pytest.importorskip("zxingcpp")
    np = pytest.importorskip("numpy")
    import qrcode
    s = session()
    text = qr_text(qr_payload(s, s.monitors[0]))
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
    qr.add_data(text.encode())
    qr.make(fit=True)
    img = (1 - np.array(qr.get_matrix(), dtype=np.uint8)) * 255
    img = np.kron(img, np.ones((5, 5), dtype=np.uint8))
    res = zxingcpp.read_barcodes(img)
    assert res and json.loads(res[0].text) == json.loads(text)


def test_svg_and_html():
    s = session()
    svg = qr_svg("salom")
    assert svg.startswith("<svg") and "path" in svg
    h = html_report(s)
    assert "EDID tekshiruvi hisoboti" in h and "PC-042" in h and "<svg" in h
    assert "Usulning cheklovlari" in h


def test_full_json_serializable():
    j = full_json(session())
    txt = json.dumps(j, ensure_ascii=False)
    assert '"manufacturer_id": "DEL"' in txt and j["monitors"][0]["comparison"]["verdict"]


def test_cli_end_to_end(tmp_path, capsys):
    f = tmp_path / "m.bin"
    f.write_bytes(EDID_A)
    out = tmp_path / "out"
    rc = main([str(f), "--brand", "Dell", "--model", "P2422H", "--resolution", "1920x1080",
               "--out", str(out), "--no-open", "--no-interactive"])
    assert rc == 0
    assert (out / "hisobot.html").exists() and (out / "natija.json").exists()
    assert (out / "monitor1_edid.bin").read_bytes() == EDID_A
    assert "XULOSA" in capsys.readouterr().out


def test_cli_json_mode(tmp_path, capsys):
    f = tmp_path / "m.bin"
    f.write_bytes(EDID_A)
    assert main([str(f), "--json"]) == 0
    j = json.loads(capsys.readouterr().out)
    assert j["monitors"][0]["edid"]["name"] == "DELL P2422H"
