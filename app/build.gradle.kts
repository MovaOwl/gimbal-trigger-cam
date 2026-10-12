plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.movaowl.gimbaltrigger"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.movaowl.gimbaltrigger"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-scout"
    }

    // Фиксированный debug-ключ в репозитории: APK из каждой сборки
    // ставится поверх предыдущего без удаления приложения.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
