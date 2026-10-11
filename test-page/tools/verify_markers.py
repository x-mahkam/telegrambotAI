#!/usr/bin/env python3
"""
Test sahifasini avtomatik tekshirish (1-bosqich).

Nima qiladi:
  1. monitor-test.html ni Chromium'da (headless) turli ekran o'lchamlarida ochadi;
  2. har bir testning skrinshotini oladi;
  3. OpenCV ArUco (DICT_4X4_50) bilan markerlarni topadi va ID'lar
     docs/marker-spec.md dagi formulaga mosligini tekshiradi;
  4. markerlardan ekran burchaklarini tiklaydi (telefon ilovasi qiladigan
     hisob) va haqiqiy burchak bilan solishtiradi;
  5. rasm mazmunini tekshiradi (rang, gradient pog'onalari soni);
  6. --simulate bilan: skrinshotni "telefon surati"ga o'xshatadi
     (perspektiva + fokusdan chiqarish + shovqin) va markerlar baribir
     topilishini tekshiradi.

O'rnatish:  pip install playwright opencv-python-headless numpy
Ishga tushirish:
  python verify_markers.py                     # asosiy tekshiruv
  python verify_markers.py --simulate --save   # + simulyatsiya, rasmlarni saqlash
  python verify_markers.py --chromium /path/to/chrome
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import cv2
import numpy as np
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).resolve().parent
PAGE = (HERE.parent / "monitor-test.html").resolve()
OUT = HERE / "out"

# Ekran konfiguratsiyalari: (eni, bo'yi CSS px, devicePixelRatio)
VIEWPORTS = [(1920, 1080, 1.0), (1366, 768, 1.0), (1536, 864, 1.25), (2560, 1440, 1.0)]

DICT = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)


def detect(img_bgr: np.ndarray):
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    det = cv2.aruco.ArucoDetector(DICT, cv2.aruco.DetectorParameters())
    corners, ids, _ = det.detectMarkers(gray)
    if ids is None:
        return {}
    return {int(i): c.reshape(4, 2) for i, c in zip(ids.flatten(), corners)}


def screen_corner_from_marker(c4: np.ndarray, corner: int) -> np.ndarray:
    """Marker burchaklaridan ekran burchagini tiklash (marker-spec.md, Geometriya).

    c4 — ArUco tartibi: [TL, TR, BR, BL] (marker tekisligida).
    Marker qora qismi 6 katak; sokin zona 1 katak va ekran burchagiga tegadi.
    Shuning uchun ekran burchagi = marker tashqi burchagi + 1 katak tashqariga
    (ikkala qo'shni qirra yo'nalishi bo'yicha).
    """
    tl, tr, br, bl = c4
    ux = (tr - tl + br - bl) / 2 / 6.0   # 1 katak, marker x o'qi bo'yicha
    uy = (bl - tl + br - tr) / 2 / 6.0   # 1 katak, marker y o'qi bo'yicha
    return {0: tl - ux - uy, 1: tr + ux - uy, 2: br + ux + uy, 3: bl - ux + uy}[corner]


def count_steps(row: np.ndarray) -> int:
    """Qator bo'yicha qiymat o'zgarishlari soni + 1 = pog'onalar soni."""
    return int(np.count_nonzero(np.any(np.diff(row.astype(int), axis=0) != 0, axis=1))) + 1


def check_content(code: str, img: np.ndarray, cell: int, errors: list, tag: str):
    h, w = img.shape[:2]
    m = 10 * cell                       # marker zonasidan uzoqroq
    rgb = img[:, :, ::-1]
    solids = {"WHITE": (255, 255, 255), "BLACK": (0, 0, 0), "RED": (255, 0, 0),
              "GREEN": (0, 255, 0), "BLUE": (0, 0, 255), "GRAY50": (128, 128, 128),
              "FLICKER": (255, 255, 255)}
    if code in solids:
        region = rgb[m:h - m, m:w - m].reshape(-1, 3)
        uniq = np.unique(region, axis=0)
        if len(uniq) != 1 or tuple(uniq[0]) != solids[code]:
            errors.append(f"{tag}: kutilgan rang {solids[code]}, topildi {uniq[:5].tolist()}")
    elif code == "BLEED":
        inner = rgb[m:h - m, m:w - m]
        if inner.max() != 0:
            errors.append(f"{tag}: qora soha ichida nol bo'lmagan piksel")
        if tuple(rgb[h // 2, 0]) != (32, 32, 32):
            errors.append(f"{tag}: chegara chizig'i (32) topilmadi")
    elif code == "GRADIENT":
        for b in range(4):
            y = int((b + 0.5) * h / 4)
            n = count_steps(rgb[y])
            if n != 256:
                errors.append(f"{tag}: {b}-polosada {n} pog'ona (kutilgan 256)")
    elif code == "GRADIENT_STEPS":
        for b, (lo, hi) in enumerate([(0, 63), (192, 255)]):
            y = int((b + 0.5) * h / 2)
            row = rgb[y]
            n = count_steps(row)
            if n != 64 or row[0, 0] != lo or row[-1, 0] != hi:
                errors.append(f"{tag}: {b}-polosada {n} pog'ona, {row[0,0]}..{row[-1,0]}")


def simulate_photo(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Telefon suratini taxminiy modellashtirish: perspektiva, defokus, shovqin."""
    h, w = img.shape[:2]
    pad = int(0.08 * w)
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    jit = rng.uniform(-0.04, 0.04, (4, 2)) * [w, h]
    dst = np.float32(src + pad + jit)
    M = cv2.getPerspectiveTransform(src, dst)
    out = cv2.warpPerspective(img, M, (w + 2 * pad, h + 2 * pad), borderValue=(20, 20, 20))
    out = cv2.GaussianBlur(out, (0, 0), 1.5)                      # biroz fokusdan chiqish
    out = out.astype(np.float32) + rng.normal(0, 4, out.shape)   # sensor shovqini
    return np.clip(out, 0, 255).astype(np.uint8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--simulate", action="store_true", help="telefon surati simulyatsiyasi")
    ap.add_argument("--save", action="store_true", help="rasmlarni test-page/tools/out/ ga saqlash")
    ap.add_argument("--chromium", help="Chromium/Chrome bajariladigan fayl yo'li")
    args = ap.parse_args()

    if args.save:
        OUT.mkdir(exist_ok=True)
    rng = np.random.default_rng(42)
    errors: list[str] = []
    checks = 0

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=args.chromium) if args.chromium \
            else p.chromium.launch()
        for (vw, vh, dpr) in VIEWPORTS:
            page = browser.new_page(viewport={"width": vw, "height": vh}, device_scale_factor=dpr)
            page.goto(PAGE.as_uri())
            tests = page.evaluate("MonitorTest.tests")
            for tt in tests:
                tid, code = tt["id"], tt["code"]
                tag = f"{vw}x{vh}@{dpr} #{tid} {code}"
                page.evaluate(f"MonitorTest.go({tid}); MonitorTest.hideOverlays()")
                cell = page.evaluate("MonitorTest.cellSize()")
                expected = page.evaluate(f"MonitorTest.markerIds({tid})")

                png = page.screenshot(full_page=False, scale="device")
                img = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
                h, w = img.shape[:2]
                if args.save:
                    cv2.imwrite(str(OUT / f"{vw}x{vh}_{dpr}_{tid:02d}_{code}.png"), img)

                # 1) ID'lar
                found = detect(img)
                checks += 1
                if sorted(found) != sorted(expected):
                    errors.append(f"{tag}: markerlar {sorted(found)}, kutilgan {expected}")
                else:
                    # 2) ekran burchaklarini tiklash
                    # OpenCV koordinatalari piksel MARKAZIDA: piksel chegarasi = -0.5
                    real = {0: (-0.5, -0.5), 1: (w - 0.5, -0.5), 2: (w - 0.5, h - 0.5), 3: (-0.5, h - 0.5)}
                    for corner, mid in enumerate(expected):
                        est = screen_corner_from_marker(found[mid], corner)
                        # subpiksel aniqlik: ±1 px yetarli
                        if np.hypot(*(est - real[corner])) > 1.0:
                            errors.append(f"{tag}: {corner}-burchak {est.round(1)} ≠ {real[corner]}")
                    # identifikatsiya formulasi
                    for mid in found:
                        if mid // 4 + 1 != tid:
                            errors.append(f"{tag}: id {mid} → test {mid // 4 + 1}")

                # 3) mazmun
                check_content(code, img, cell, errors, tag)

                # 4) simulyatsiya
                if args.simulate:
                    sim = simulate_photo(img, rng)
                    fsim = detect(sim)
                    checks += 1
                    if not set(expected) <= set(fsim):
                        errors.append(f"{tag} [sim]: topildi {sorted(fsim)}, kutilgan {expected}")
                    if args.save:
                        cv2.imwrite(str(OUT / f"{vw}x{vh}_{dpr}_{tid:02d}_{code}_sim.jpg"), sim)

            # markersiz rejim: hech qanday marker bo'lmasligi kerak
            page.evaluate("MonitorTest.go(1); MonitorTest.setMarkers(false); MonitorTest.hideOverlays()")
            img = cv2.imdecode(np.frombuffer(page.screenshot(scale="device"), np.uint8), cv2.IMREAD_COLOR)
            checks += 1
            if detect(img):
                errors.append(f"{vw}x{vh}@{dpr}: M o'chirilganda ham marker topildi")
            page.close()
        browser.close()

    if errors:
        print(f"XATO: {len(errors)} ta muammo ({checks} tekshiruv):")
        for e in errors:
            print("  -", e)
        return 1
    print(f"OK: {checks} ta marker tekshiruvi va barcha mazmun tekshiruvlari muvaffaqiyatli "
          f"({len(VIEWPORTS)} ta ekran o'lchami).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
