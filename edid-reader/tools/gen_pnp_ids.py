#!/usr/bin/env python3
"""
edid_reader/pnp_ids.py modulini generatsiya qiladi.

Manba: UEFI Forum PNP ID registri (https://uefi.org/PNP_ID_List), hwdata
loyihasidagi nusxa: https://github.com/vcrhonek/hwdata/blob/master/pnp.ids

Foydalanish:
    curl -L -o pnp.ids https://raw.githubusercontent.com/vcrhonek/hwdata/master/pnp.ids
    python tools/gen_pnp_ids.py pnp.ids > edid_reader/pnp_ids.py

Natija oddiy Python lug'ati (dict) — PyInstaller qo'shimcha ma'lumot faylisiz
yig'adi va dastur internetsiz ishlaydi.
"""
import sys
from datetime import date


def main(path: str) -> None:
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#") or "\t" not in line:
                continue
            code, name = line.split("\t", 1)
            if len(code) == 3:
                rows.append((code.upper(), name.strip()))
    rows.sort()
    out = sys.stdout
    out.write('"""\n')
    out.write("PNP ID (EDID ishlab chiqaruvchi kodi) -> kompaniya nomi.\n\n")
    out.write("AVTOMATIK GENERATSIYA QILINGAN: tools/gen_pnp_ids.py. Qo'lda tahrirlamang.\n")
    out.write("Manba: UEFI Forum PNP ID registri (hwdata/pnp.ids nusxasi), "
              f"{date.today().isoformat()} holatiga, {len(rows)} ta yozuv.\n")
    out.write('"""\n\n')
    out.write("PNP_IDS = {\n")
    for code, name in rows:
        out.write(f"    {code!r}: {name!r},\n")
    out.write("}\n")


if __name__ == "__main__":
    main(sys.argv[1])
