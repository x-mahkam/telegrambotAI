"""
Buyruq qatori / interaktiv interfeys.

Ikki marta bosib ishga tushirilganda (argumentlarsiz) — interaktiv rejim:
monitorlar topiladi, shartnoma ma'lumotlari so'raladi, natija HTML hisobot
sifatida brauzerda ochiladi va papkaga saqlanadi.

Misollar:
  MonitorCheck-EDID.exe
  MonitorCheck-EDID.exe --brand Dell --model P2422H --diagonal 23.8 --resolution 1920x1080 --refresh 60
  MonitorCheck-EDID.exe --all --no-open --out D:\\natijalar
  python -m edid_reader namuna.bin --json
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import socket
import sys
import webbrowser
from datetime import datetime
from pathlib import Path

from . import __version__
from .compare import Declared, compare, parse_resolution
from .decoder import EdidError, decode_edid
from .export import (
    MonitorResult,
    Session,
    full_json,
    html_report,
    qr_payload,
    qr_svg,
    qr_text,
)
from .messages import msg, set_lang
from .sources import detect_all, read_file


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="MonitorCheck-EDID",
                                description="Monitor EDID ma'lumotini o'qish va shartnoma bilan solishtirish")
    p.add_argument("files", nargs="*", help="EDID fayl(lar)i (.bin yoki hex matn); berilmasa — tizimdan o'qiladi")
    p.add_argument("--all", action="store_true", help="ilgari ulangan (faol bo'lmagan) monitorlarni ham ko'rsatish")
    p.add_argument("--brand"), p.add_argument("--model")
    p.add_argument("--diagonal", type=float, help="dyuym")
    p.add_argument("--resolution", help="masalan 1920x1080")
    p.add_argument("--refresh", type=float, help="Hz")
    p.add_argument("--bits", type=int, help="rang chuqurligi, bit")
    p.add_argument("--serial"), p.add_argument("--inventory", help="inventar raqami")
    p.add_argument("--declared-json", help="e'lon qilingan ma'lumotlar JSON fayli")
    p.add_argument("--max-age-years", type=float, default=2.0, help="ishlab chiqarilganidan beri maks. yil (standart 2)")
    p.add_argument("--out", help="natijalar papkasi")
    p.add_argument("--no-open", action="store_true", help="hisobotni brauzerda ochmaslik")
    p.add_argument("--no-interactive", action="store_true", help="savol bermaslik")
    p.add_argument("--json", action="store_true", help="to'liq JSON'ni ekranga chiqarish")
    p.add_argument("--lang", default=None, help="interfeys tili: uz (standart), ru")
    p.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    return p


def _ask(prompt: str, conv):
    while True:
        try:
            s = input(msg(prompt)).strip()
        except EOFError:
            return None
        if not s:
            return None
        try:
            v = conv(s)
            if v is None:
                raise ValueError
            return v
        except ValueError:
            print(msg("cli.bad_value"))


def _interactive_declared() -> tuple[Declared, str | None]:
    print()
    print(msg("cli.enter_declared"))
    d = Declared(
        brand=_ask("cli.q_brand", str),
        model=_ask("cli.q_model", str),
        diagonal_in=_ask("cli.q_diag", lambda s: float(s.replace(",", "."))),
        resolution=_ask("cli.q_res", parse_resolution),
        refresh_hz=_ask("cli.q_hz", lambda s: float(s.replace(",", "."))),
        bit_depth=_ask("cli.q_bits", int),
        serial=_ask("cli.q_serial", str),
    )
    inv = _ask("cli.q_inv", str)
    return d, inv


def _default_out_dir(computer: str, ts: datetime) -> Path:
    base = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path.cwd()
    return base / "MonitorCheck_EDID" / f"{computer}_{ts:%Y%m%d_%H%M%S}"


def _print_summary(results: list[MonitorResult]) -> None:
    for m in results:
        e, c = m.info, m.comparison
        print()
        print(f"[{m.index}] {e.manufacturer_id} ({e.manufacturer_name or '?'}) — {e.name or '?'}"
              f"   {'' if m.source.active is None else ('faol' if m.source.active else 'faol emas')}")
        print(f"    Model kodi 0x{e.product_code_hex}, seriya: {e.serial_string or e.serial_number or '—'}, "
              f"sana: {(str(e.week) + '-hafta ') if e.week else ''}{e.year or '—'}")
        print(f"    Diagonal: {e.diagonal_in or '—'}\", tabiiy ruxsat: {e.native.label() if e.native else '—'}, "
              f"maks. {e.max_refresh_native_hz or '—'} Hz, rang chuqurligi: {e.bit_depth or '—'} bit, EDID {e.version}")
        for ch in c.checks:
            if ch.status != "info" or ch.key in ("brand", "model"):
                print(f"    - {msg('check.' + ch.key)}: {msg('status.' + ch.status).upper()}  {ch.note}")
        print(f"    {msg('cli.verdict')}: {msg('verdict.' + c.verdict)}")


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except Exception:
        pass
    args = _parser().parse_args(argv)
    if args.lang:
        set_lang(args.lang)

    # --json rejimida stdout faqat JSON uchun; holat xabarlari stderr'ga
    log = (lambda *a: print(*a, file=sys.stderr)) if args.json else print
    log(f"{msg('cli.title')} v{__version__}")

    # 1) Manbalar
    sources = [read_file(f) for f in args.files] if args.files else detect_all(args.all)
    if not sources:
        log(msg("cli.none"))
        return 2
    if not args.files and not args.all and all(s.active is False for s in sources):
        log(msg("cli.inactive_note"))

    infos = []
    for s in sources:
        try:
            infos.append((s, decode_edid(s.data)))
        except EdidError as ex:
            log(f"  ! {s.location}: {ex}")
    if not infos:
        return 2
    log(msg("cli.found", n=len(infos)))

    # 2) E'lon qilingan ma'lumotlar
    declared = Declared(args.brand, args.model, args.diagonal, parse_resolution(args.resolution or ""),
                        args.refresh, args.bits, args.serial)
    inventory = args.inventory
    if args.declared_json:
        with open(args.declared_json, encoding="utf-8") as f:
            dj = json.load(f)
        declared = Declared.from_dict(dj)
        inventory = inventory or dj.get("inventory")
    interactive = (not args.no_interactive and not args.json and declared.is_empty()
                   and sys.stdin is not None and sys.stdin.isatty())
    if interactive:
        for s, e in infos:
            print(f"  - {e.manufacturer_id} {e.name or ''}  ({e.native.label() if e.native else '?'})")
        declared, inventory = _interactive_declared()

    # 3) Solishtirish
    ts = datetime.now()
    results = [MonitorResult(i + 1, s, e, compare(e, declared, ts.date(), args.max_age_years))
               for i, (s, e) in enumerate(infos)]
    session = Session(ts, socket.gethostname(), f"{platform.system()} {platform.release()}",
                      declared, inventory, results)

    if args.json:
        print(json.dumps(full_json(session), ensure_ascii=False, indent=2))
        return 0

    _print_summary(results)

    # 4) Saqlash
    out = Path(args.out) if args.out else _default_out_dir(session.computer, ts)
    out.mkdir(parents=True, exist_ok=True)
    (out / "natija.json").write_text(json.dumps(full_json(session), ensure_ascii=False, indent=2), encoding="utf-8")
    report = out / "hisobot.html"
    report.write_text(html_report(session), encoding="utf-8")
    for m in results:
        (out / f"monitor{m.index}_edid.bin").write_bytes(bytes.fromhex(m.info.raw_hex))
        (out / f"monitor{m.index}_qr.svg").write_text(qr_svg(qr_text(qr_payload(session, m))), encoding="utf-8")
    print()
    print(msg("cli.saved", path=str(out)))

    if not args.no_open:
        try:
            if os.name == "nt":
                os.startfile(str(report))  # type: ignore[attr-defined]
            else:
                webbrowser.open(report.resolve().as_uri())
        except Exception:
            pass
    if interactive:
        try:
            input(msg("cli.press_enter"))
        except EOFError:
            pass
    return 0
