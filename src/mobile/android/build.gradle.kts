plugins {
    id("com.android.application") version "9.0.1" apply false
    // Kotlin в AGP 9 встроен; плагин Compose задаёт версию компилятора Kotlin
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}
