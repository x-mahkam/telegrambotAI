# MonitorCheck

Davlat xaridlarida yetkazib berilgan kompyuter monitorlarining sifatini va
**ergonomik ko'rsatkichlari hamda standartlarga muvofiqligini** tekshirish tizimi.
U yetkazib beruvchi e'lon qilinganidan arzonroq yoki sifatsizroq panel
qo'ygan-qo'ymaganini aniqlashga yordam beradi.

| Qism | Holat | Joyi |
|------|-------|------|
| 1. Test sahifasi (HTML) | ✅ tayyor | [`test-page/`](test-page/) |
| 2. EDID o'quvchi (Windows, Python) | ✅ tayyor | [`edid-reader/`](edid-reader/) |
| 3. Android ilova: karkas + miltillash (PWM) moduli | ✅ tayyor | [`android-app/`](android-app/) |
| 4–6. Piksellar/uniformity, PDF hisobot, spektr | ⏳ | `android-app/` |

Arxitektura: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) ·
Marker protokoli: [`docs/marker-spec.md`](docs/marker-spec.md) ·
EDID QR formati: [`docs/edid-schema.md`](docs/edid-schema.md) ·
Metodika: [`docs/methodology.md`](docs/methodology.md)

## 1. Test sahifasi

`test-page/monitor-test.html` faylini tekshirilayotgan kompyuterga ko'chiring
(fleshka orqali), brauzerda oching va **F11** bosing. Internet kerak emas.

Avtomatik tekshiruv (ishlab chiquvchi uchun):

```bash
pip install -r test-page/tools/requirements.txt
python -m playwright install chromium     # agar Chromium o'rnatilmagan bo'lsa
python test-page/tools/verify_markers.py --simulate --save
```

> Tizim tibbiy diagnostika vositasi emas: natijalar o'lchanadigan fizik
> ko'rsatkichlar va ularning standartlarga muvofiqligi haqida ma'lumot beradi.
