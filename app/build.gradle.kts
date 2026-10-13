import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            // Use the Android Gradle Plugin's standard debug key; CI creates it automatically.
            signingConfig = signingConfigs.getByName("debug")
        }
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

val sherpaAarFile = file("libs/sherpa-onnx.aar")
if (!sherpaAarFile.exists() || sherpaAarFile.length() < 10L * 1024 * 1024) {
    sherpaAarFile.parentFile?.mkdirs()
    val downloadUrls = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
        "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
        "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
    )
    var downloaded = false
    for (urlStr in downloadUrls) {
        try {
            logger.lifecycle("Downloading sherpa-onnx.aar from $urlStr...")
            var cur = urlStr
            var redirects = 0
            while (redirects < 10) {
                val uri = URI(cur)
                val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Missing Location header")
                    cur = if (loc.startsWith("http")) loc else uri.resolve(loc).toString()
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code in 200..299) {
                    val temp = File(sherpaAarFile.parentFile, "sherpa-onnx.aar.tmp")
                    temp.delete()
                    conn.inputStream.use { input: InputStream ->
                        FileOutputStream(temp).use { output: OutputStream ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()
                    if (!temp.renameTo(sherpaAarFile)) {
                        temp.copyTo(sherpaAarFile, overwrite = true)
                        temp.delete()
                    }
                    logger.lifecycle("Downloaded sherpa-onnx.aar successfully (${sherpaAarFile.length()} bytes)")
                    downloaded = true
                    break
                }
                conn.disconnect()
                break
            }
            if (downloaded) break
        } catch (e: Exception) {
            logger.warn("Failed to download sherpa-onnx.aar from $urlStr: ${e.message}")
        }
    }
}

dependencies {
    // Backport newer Java library APIs used by NewPipeExtractor to Android 7–12.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")

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

    // NewPipe Extractor
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
    // Enables Coil to render SVG logos discovered from provider website metadata.
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    // 模型包解压（.tar.bz2）
    implementation("org.apache.commons:commons-compress:1.26.1")
}
