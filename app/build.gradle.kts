import io.translatetube.build.PatchNewPipeApiTask
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Keep the unmodified NewPipe artifact outside the app compile classpath; its calls to newer
// Java APIs must be rewritten before D8 sees the bytecode.
val newPipeExtractorArtifacts = configurations.create("newPipeExtractorArtifacts") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Release 签名仅从本机 signing.properties 或环境变量读取，不将密钥和密码写入仓库。
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.isFile) signingPropsFile.inputStream().use { properties -> load(properties) }
}
fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseSigningReady = !releaseStorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStorePath).isFile

android {
    namespace = "io.translatetube.local"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.translatetube.local"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.6"
        ndk {
            // arm64 单架构（304MB 传输上限内；v7a 版存档）
            abiFilters += "arm64-v8a"
        }
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有本机签名配置时，仍可 assembleRelease；生成未签名 APK。
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/*"
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // Backport newer Java library APIs used by NewPipeExtractor to Android 7–12.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // 本地 aar：sherpa-onnx（TTS Kokoro + ASR Whisper 推理引擎）
    implementation(files("libs/sherpa-onnx.aar"))

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
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

    // Resolve NewPipe and its transitive dependencies separately. The extractor bytecode is
    // patched at build time because its calls to Java 10/33 APIs are unavailable on Android 9.
    add(newPipeExtractorArtifacts.name, "com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

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
    // Enables Coil to render SVG logos discovered from provider website metadata.
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    // 模型包解压（.tar.bz2）
    implementation("org.apache.commons:commons-compress:1.26.1")
}


// Patch extractor bytecode for Android 9, and put its transitive dependencies on the normal app classpath.
val newPipeExtractorResolvedArtifacts = providers.provider {
    newPipeExtractorArtifacts.resolvedConfiguration.resolvedArtifacts.toList()
}
val rawNewPipeExtractor = providers.provider {
    newPipeExtractorResolvedArtifacts.get().firstOrNull {
        it.moduleVersion.id.group.equals("com.github.TeamNewPipe", ignoreCase = true)
            && it.moduleVersion.id.name == "NewPipeExtractor"
    }?.file ?: throw GradleException(
        "Could not find com.github.TeamNewPipe:NewPipeExtractor in the resolved artifacts."
    )
}
val patchNewPipeExtractor = tasks.register<PatchNewPipeApiTask>("patchNewPipeExtractor") {
    inputJar.set(layout.file(rawNewPipeExtractor))
    outputJar.set(layout.buildDirectory.file("patched-libs/NewPipeExtractor-android9.jar"))
}
dependencies.add(
    "implementation",
    files(patchNewPipeExtractor.flatMap { it.outputJar }).builtBy(patchNewPipeExtractor)
)
dependencies.add(
    "implementation",
    files(providers.provider {
        newPipeExtractorResolvedArtifacts.get()
            .filterNot {
                it.moduleVersion.id.group.equals("com.github.TeamNewPipe", ignoreCase = true)
                    && it.moduleVersion.id.name == "NewPipeExtractor"
            }
            .map { it.file }
    })
)
