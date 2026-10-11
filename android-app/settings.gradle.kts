// MonitorCheck Android loyihasi.
//  :analysis — sof Kotlin/JVM: FFT, flicker tahlili, IEEE 1789 (Android SDK'siz test qilinadi)
//  :app      — Android ilova (Compose + Camera2). Faqat Android SDK mavjud bo'lsa ulanadi,
//              shuning uchun :analysis testlarini SDK'siz kompyuterda ham ishga tushirish mumkin.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MonitorCheck"

include(":analysis")

val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkAvailable) include(":app")
