plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.translatetube.local"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.translatetube.local"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.5"
        ndk {
            // arm64 单架构（304MB 传输上限内；v7a 版存档）
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("/home/z/godot/civ.keystore")
            storePassword = "civsandbox"
            keyAlias = "civ"
            keyPassword = "civsandbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "META-INF/*"
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // 本地 aar：sherpa-onnx（TTS Kokoro + ASR Whisper 推理引擎）
    implementation(files("libs/sherpa-onnx.aar"))

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // 播放
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")

    // YouTube 解析（纯本地，无服务器）
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    // 离线翻译（bundled 引擎，不依赖 GMS）
    implementation("com.google.mlkit:translate:17.0.3")

    // 数据
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 网络/异步/图片
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    // 模型包解压（.tar.bz2）
    implementation("org.apache.commons:commons-compress:1.26.1")
}
