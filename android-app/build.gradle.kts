// Plaginlar buildscript orqali ulanadi: Android Gradle Plugin faqat SDK mavjud bo'lganda
// classpath'ga qo'shiladi (settings.gradle.kts dagi shart bilan bir xil).
buildscript {
    val kotlinVersion = "2.1.21"
    val agpVersion = "8.9.1"
    val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        if (sdkAvailable) {
            classpath("com.android.tools.build:gradle:$agpVersion")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        }
    }
}
