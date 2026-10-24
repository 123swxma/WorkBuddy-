import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名材料不进仓库：keystore.properties 与 *.jks 都在 .gitignore 里。
// CI 上没有这个文件，走 GitHub Secrets 注入（见 .github/workflows/release.yml）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

android {
    namespace = "com.workbuddy.proxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.workbuddy.proxy"
        minSdk = 24
        targetSdk = 34
        versionCode = 17
        versionName = "1.9.2"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 有正式 keystore 就用它；没有就退回 debug 签名，保证 clone 下来能直接构建
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

