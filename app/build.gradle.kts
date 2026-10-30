plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.srep.novatv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.srep.novatv"
        minSdk = 24
        targetSdk = 34
        // Номер сборки растёт сам при каждом запуске GitHub Actions — новая сборка всегда ставится ПОВЕРХ старой
        val build = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        versionCode = 100 + build
        versionName = "1.1.$build"
        // Переопределяются: gradle -PportalUrl=https://... -PupdateUrl=https://.../update.json
        val portal = providers.gradleProperty("portalUrl").orElse("https://srepportal.craft-host.ru").get()
        val upd = providers.gradleProperty("updateUrl").orElse("").get()
        buildConfigField("String", "PORTAL_URL", "\"$portal\"")
        buildConfigField("String", "UPDATE_URL", "\"$upd\"")
    }

    // ВАЖНО: подпись всегда одна и та же. Иначе Android не даёт обновить приложение «поверх»
    // (приходится удалять — и все данные пропадают). Файл app/novatv.keystore ОБЯЗАТЕЛЬНО держите в репозитории.
    val ksPath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        create("nova") {
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("novatv.keystore")
                storePassword = "novatv-sign"
                keyAlias = "novatv"
                keyPassword = "novatv-sign"
            }
        }
    }
    buildTypes {
        // Сборка без отладочного режима и с оптимизацией — на слабых приставках (Eltex) работает в разы плавнее
        debug {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nova")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nova")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
