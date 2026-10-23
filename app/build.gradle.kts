plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.workbuddy.proxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.workbuddy.proxy"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "1.9.1"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // debug 签名：产物可直接侧载安装，不需要用户自建密钥库
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 刻意零第三方运行时依赖：只用 Android 平台 API + Kotlin stdlib，
    // 让 APK 保持极小、离线可构建、不随上游库升级而漂移。
}
