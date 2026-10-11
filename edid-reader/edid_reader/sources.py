"""
EDID manbalari: Windows reyestri (asosiy), Linux sysfs (sinov uchun) va fayl.

Windows reyestri tuzilishi:
  HKLM\\SYSTEM\\CurrentControlSet\\Enum\\DISPLAY\\<Apparat ID>\\<Instance>\\
        Device Parameters\\EDID   (REG_BINARY)
  <Instance>\\Control             — kalit faqat hozir ULANGAN monitorda bor
                                    (Windows uni ishga tushganda yaratadi).
Reyestrda ilgari ulangan barcha monitorlar saqlanib qoladi, shuning uchun
"Control" kaliti bo'yicha faol monitorlar ajratiladi.
"""
from __future__ import annotations

import glob
import hashlib
import os
import re
from dataclasses import dataclass

DISPLAY_KEY = r"SYSTEM\CurrentControlSet\Enum\DISPLAY"


@dataclass
class EdidSource:
    data: bytes
    origin: str            # "registry" | "sysfs" | "file"
    location: str          # reyestr yo'li, sysfs yo'li yoki fayl nomi
    active: bool | None    # None — noma'lum
    hardware_id: str | None = None


def read_windows_registry(include_inactive: bool = False, winreg=None) -> list[EdidSource]:
    """Windows reyestridan barcha monitorlar EDID'ini o'qish.

    winreg parametri unit testlarda soxta modul berish uchun.
    """
    if winreg is None:
        import winreg  # type: ignore  # faqat Windows'da mavjud
    out: list[EdidSource] = []
    try:
        root = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, DISPLAY_KEY)
    except OSError:
        return out
    with root:
        for hw_id in _subkeys(winreg, root):
            try:
                hw = winreg.OpenKey(root, hw_id)
            except OSError:
                continue
            with hw:
                for inst in _subkeys(winreg, hw):
                    path = f"{hw_id}\\{inst}"
                    try:
                        with winreg.OpenKey(hw, inst + r"\Device Parameters") as dp:
                            value, _type = winreg.QueryValueEx(dp, "EDID")
                    except OSError:
                        continue
                    try:
                        with winreg.OpenKey(hw, inst + r"\Control"):
                            active = True
                    except OSError:
                        active = False
                    if value:
                        out.append(EdidSource(bytes(value), "registry",
                                              "HKLM\\" + DISPLAY_KEY + "\\" + path, active, hw_id))
    return _select(out, include_inactive)


def _subkeys(winreg, key) -> list[str]:
    names, i = [], 0
    while True:
        try:
            names.append(winreg.EnumKey(key, i))
        except OSError:
            return names
        i += 1


def read_linux_sysfs(include_inactive: bool = False, root: str = "/sys/class/drm") -> list[EdidSource]:
    out = []
    for path in sorted(glob.glob(os.path.join(root, "card*-*", "edid"))):
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError:
            continue
        if not data:
            continue
        status_file = os.path.join(os.path.dirname(path), "status")
        try:
            active = open(status_file).read().strip() == "connected"
        except OSError:
            active = None
        out.append(EdidSource(data, "sysfs", path, active, os.path.basename(os.path.dirname(path))))
    return _select(out, include_inactive)


def read_file(path: str) -> EdidSource:
    """Binar (.bin) yoki matnli hex fayl (shu jumladan edid-decode chiqishi)."""
    with open(path, "rb") as f:
        raw = f.read()
    data = raw
    if not raw.startswith(b"\x00\xff\xff\xff\xff\xff\xff\x00"):
        text = raw.decode("latin-1")
        if "edid-decode (hex):" in text:
            text = text.split("edid-decode (hex):", 1)[1].split("----------------", 1)[0]
        hx = re.findall(r"\b[0-9a-fA-F]{2}\b", text)
        if not hx:
            hx = re.findall(r"[0-9a-fA-F]{2}", re.sub(r"\s+", "", text))
        data = bytes(int(h, 16) for h in hx)
    return EdidSource(data, "file", os.path.basename(path), None)


def _select(items: list[EdidSource], include_inactive: bool) -> list[EdidSource]:
    """Bir xil EDID'larni birlashtirish; faol monitorlarni ajratish.

    Agar birorta ham faol monitor aniqlanmasa (masalan, masofaviy ish stoli),
    hammasi qaytariladi — CLI buni foydalanuvchiga aytadi.
    """
    seen: dict[str, EdidSource] = {}
    for s in items:
        h = hashlib.sha256(s.data).hexdigest()
        if h not in seen or (s.active and not seen[h].active):
            seen[h] = s
    uniq = list(seen.values())
    if include_inactive:
        return uniq
    active = [s for s in uniq if s.active]
    return active if active else uniq


def detect_all(include_inactive: bool = False) -> list[EdidSource]:
    if os.name == "nt":
        return read_windows_registry(include_inactive)
    if os.path.isdir("/sys/class/drm"):
        return read_linux_sysfs(include_inactive)
    return []
