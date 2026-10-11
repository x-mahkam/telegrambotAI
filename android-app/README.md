# MonitorCheck — Android ilova (3-bosqich: karkas + miltillash moduli)

## Tuzilma

```
android-app/
├── analysis/   ← sof Kotlin/JVM: FFT, flicker tahlili, IEEE 1789 (Android SDK'siz test qilinadi)
│   └── src/main/kotlin/uz/monitorcheck/analysis/
│       ├── dsp/        Fft, Signal (oyna, sinc, eng kichik kvadratlar, Legendre), Stats
│       ├── flicker/    RowProfile, RowTiming (+kalibrovka), FlickerAnalyzer
│       └── standards/  Ieee1789
└── app/        ← Android: Compose UI + Camera2
    └── src/main/java/uz/monitorcheck/
        ├── camera/     CameraConfig (kamera tanlash), FlickerCamera (Camera2 sessiya)
        ├── data/       CalibrationStore
        └── ui/         home/, flicker/ (ekran, ViewModel, grafiklar), theme/
```

Fizik usul, formulalar va manbalar: [`../docs/methodology.md`](../docs/methodology.md).

## APK'ni olish

GitHub → **Actions** → **Android app** → oxirgi muvaffaqiyatli ishga tushirish →
**MonitorCheck-debug-apk** artefakti (zip ichida `app-debug.apk`).
Telefonda "Noma'lum manbalardan o'rnatish"ga ruxsat bering va o'rnating.

## Ishlab chiquvchi uchun

```bash
./gradlew :analysis:test          # hisob-kitob testlari (Android SDK shart emas)
./gradlew :app:assembleDebug      # ANDROID_HOME yoki local.properties (sdk.dir) kerak
```

`:app` moduli faqat Android SDK topilganda ulanadi (`settings.gradle.kts`), shuning
uchun `:analysis` ni istalgan kompyuterda JDK 17+ bilan tekshirish mumkin.

## Talablar

* Android 8.0+ (API 26).
* Aniq o'lchov uchun: Camera2 `MANUAL_SENSOR` va `SENSOR_ROLLING_SHUTTER_SKEW`
  (aksariyat zamonaviy telefonlarda bor). Skew bo'lmasa — 100 Hz lampa bo'yicha
  kalibrovka; qo'lda ekspozitsiya bo'lmasa — avtomatik rejim (aniqligi pastroq).
