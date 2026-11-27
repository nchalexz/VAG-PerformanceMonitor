plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.nchalexz.aaperfmon"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.nchalexz.aaperfmon"
        minSdk = 24                 // Android 7, охватывает почти все живые телефоны
        targetSdk = 36              // требование Google Play с 31.08.2026
        versionCode = 8
        versionName = "0.6.0"
    }

    // Подпись включается ТОЛЬКО когда задана переменная окружения
    // VAGDIAG_KEYSTORE. Не задана — release собирается неподписанным,
    // debug работает как прежде. Паролей в коде нет.
    val ksPath = System.getenv("VAGDIAG_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("VAGDIAG_STOREPASS")
                keyAlias = System.getenv("VAGDIAG_KEYALIAS") ?: "vagdiag"
                keyPassword = System.getenv("VAGDIAG_KEYPASS")
                // Все схемы подписи сразу: v1 понимают старые телефоны,
                // v2 — начиная с Android 7, v3 позволяет когда-нибудь
                // сменить ключ, не теряя установленную базу.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    flavorDimensions += "car"
    productFlavors {
        // Один и тот же applicationId: это одна программа в двух сборках.
        // Пользователь ставит либо обычную, либо с Android Auto.
        create("standard") {
            dimension = "car"
        }
        create("aa") {
            dimension = "car"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Драйверы CH340, FTDI, CP210x, PL2303 — микросхемы, которые стоят
    // внутри USB-адаптеров ELM327. Единственная внешняя зависимость.
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    // Android Auto (Car App Library) только в AA-сборке. Обычная сборка
    // не тянет автомобильные зависимости и не объявляет CarAppService.
    "aaImplementation"("androidx.car.app:app:1.7.0")
    "aaImplementation"("androidx.car.app:app-projected:1.7.0")

    // Тесты каталога и движка телеметрии — обычные JVM-тесты: ни эмулятор,
    // ни автомобиль для них не нужны.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // JVM-тесты не должны видеть android.jar-заглушку org.json: она
    // возвращает null из toString() и не проверяет реальный JSON-контракт.
    testImplementation("org.json:json:20260814")
}
