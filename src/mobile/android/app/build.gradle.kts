plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Задача fetchModels: один раз скачивает модели в app/models-assets (см. models.gradle.kts)
apply(from = "models.gradle.kts")

android {
    // Lint падает с внутренней ошибкой при разборе Gradle-скриптов (баг lint + Kotlin);
    // проверка для release-сборки отключена, чтобы она не блокировала APK.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    namespace = "com.stenograf.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stenograf.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64-v8a — реальные телефоны, x86_64 — эмулятор Android Studio
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Для тестовых сборок release подписывается debug-ключом
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Модели уже сжаты внутри себя; без сжатия APK собирается и копирует их быстрее
        noCompress += listOf("onnx", "txt", "json", "litertlm")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    // Локальное распознавание речи и диаризация (k2-fsa/sherpa-onnx v2.0.1)
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    // Локальная LLM (пунктуация и заглавные буквы): Google LiteRT-LM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

// Модели входят в APK как assets: приложение работает без интернета сразу после установки.
val modelsAssetsDir = layout.projectDirectory.dir("models-assets").asFile.apply { mkdirs() }
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(modelsAssetsDir.absolutePath)
    }
}
tasks.named("preBuild") { dependsOn("fetchModels") }
