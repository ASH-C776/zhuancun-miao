plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ashchan.cloudsaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ashchan.cloudsaver"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "0.5.1"
    }

    signingConfigs {
        create("release") {
            // 复用 debug 签名：release 包可直接覆盖安装（无需卸载、数据不丢），
            // 也免去额外密钥管理。个人侧载应用足够；将来上架应用商店再换正式密钥。
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "AndroidDebugKey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8 代码裁剪 + 资源收缩：体积从 ~11.6MB(debug) 大幅下降
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 调试包不混淆，便于排查接口问题
            // 版本名加 -debug 后缀：关于页一眼区分调试版 / 正式版（仍是同一 App，会互相覆盖）
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // 离线环境无 lint-gradle 缓存，且个人应用无需发布前 lint 阻断构建
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    // 加密存储（Token / Cookie 不明文落盘）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 网络：Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 调试 UI 预览
    debugImplementation("androidx.compose.ui:ui-tooling")
}
