import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

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
        versionCode = 9
        versionName = "1.0.7"
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


/*
 * NewPipe 0.26.5 uses URLDecoder.decode(String, Charset), which is missing
 * from Android 9's java.net.URLDecoder. Core-library desugaring does not
 * rewrite this platform call. Rewrite only those method references to the
 * signature-compatible app shim before D8 packages the dependency.
 */
val newPipeExtractorRuntime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
}
val newPipeOriginalJar = providers.provider {
    newPipeExtractorRuntime.resolvedConfiguration.resolvedArtifacts.single {
        it.moduleVersion.id.group == "com.github.TeamNewPipe" &&
            it.moduleVersion.id.name == "NewPipeExtractor"
    }.file
}
val newPipeTransitiveFiles = providers.provider {
    newPipeExtractorRuntime.resolvedConfiguration.resolvedArtifacts
        .filterNot {
            it.moduleVersion.id.group == "com.github.TeamNewPipe" &&
                it.moduleVersion.id.name == "NewPipeExtractor"
        }
        .map { it.file }
}
val patchedNewPipeJar = layout.buildDirectory.file("patched-newpipe/NewPipeExtractor-android9.jar")

private data class NewPipeCpEntry(
    val index: Int,
    val tag: Int,
    val start: Int,
    val end: Int,
    val text: String? = null,
    val a: Int? = null,
    val b: Int? = null,
)

private fun cpU2(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private fun putCpU2(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 8) and 0xff).toByte()
    bytes[offset + 1] = (value and 0xff).toByte()
}

private fun rewriteNewPipeClass(bytes: ByteArray): Pair<ByteArray, Int> {
    if (bytes.size < 10 || cpU2(bytes, 0) != 0xcafe) return bytes to 0
    val cpCount = cpU2(bytes, 8)
    val entries = linkedMapOf<Int, NewPipeCpEntry>()
    var index = 1
    var offset = 10
    while (index < cpCount) {
        val start = offset
        val tag = bytes[offset].toInt() and 0xff
        var text: String? = null
        var a: Int? = null
        var b: Int? = null
        when (tag) {
            1 -> {
                val length = cpU2(bytes, offset + 1)
                text = String(bytes, offset + 3, length, Charsets.UTF_8)
                offset += 3 + length
            }
            3, 4 -> offset += 5
            5, 6 -> offset += 9
            7, 8, 16, 19, 20 -> {
                a = cpU2(bytes, offset + 1)
                offset += 3
            }
            9, 10, 11, 12, 17, 18 -> {
                a = cpU2(bytes, offset + 1)
                b = cpU2(bytes, offset + 3)
                offset += 5
            }
            15 -> offset += 4
            else -> throw GradleException("Unknown class-file constant-pool tag $tag")
        }
        entries[index] = NewPipeCpEntry(index, tag, start, offset, text, a, b)
        index += if (tag == 5 || tag == 6) 2 else 1
    }
    val cpEnd = offset
    val utf8ByIndex = entries.values.filter { it.tag == 1 }.associate { it.index to it.text.orEmpty() }
    val classNameByIndex = entries.values.filter { it.tag == 7 }
        .associate { it.index to utf8ByIndex[it.a] }
    val nameAndTypeByIndex = entries.values.filter { it.tag == 12 }
        .associate { it.index to (utf8ByIndex[it.a].orEmpty() to utf8ByIndex[it.b].orEmpty()) }

    val targets = entries.values.filter { entry ->
        if (entry.tag != 10 && entry.tag != 11) return@filter false
        val owner = classNameByIndex[entry.a]
        val (name, descriptor) = nameAndTypeByIndex[entry.b] ?: return@filter false
        val charsetOverload = "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
        (owner == "java/net/URLDecoder" && name == "decode" && descriptor == charsetOverload) ||
            (owner == "java/net/URLEncoder" && name == "encode" && descriptor == charsetOverload)
    }
    if (targets.isEmpty()) return bytes to 0

    val additions = ByteArrayOutputStream()
    val additionStream = DataOutputStream(additions)
    var newCpCount = cpCount
    val utf8Indexes = entries.values.filter { it.tag == 1 }
        .associate { it.text.orEmpty() to it.index }.toMutableMap()
    fun appendUtf8(value: String): Int {
        utf8Indexes[value]?.let { return it }
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= 65535) { "Constant-pool UTF-8 value is too long" }
        val addedIndex = newCpCount++
        additionStream.writeByte(1)
        additionStream.writeShort(encoded.size)
        additionStream.write(encoded)
        utf8Indexes[value] = addedIndex
        return addedIndex
    }

    val compatName = "io/translatetube/local/compat/Android9JavaApiCompat"
    var compatClassIndex = entries.values.firstOrNull {
        it.tag == 7 && utf8ByIndex[it.a] == compatName
    }?.index
    if (compatClassIndex == null) {
        val compatUtf8Index = appendUtf8(compatName)
        compatClassIndex = newCpCount++
        additionStream.writeByte(7)
        additionStream.writeShort(compatUtf8Index)
    }

    val cpBytes = bytes.copyOfRange(10, cpEnd)
    targets.forEach { entry ->
        // Method descriptors are identical; only redirect the owner to our API-28-safe shim.
        cpBytes[entry.start - 10] = 10.toByte() // CONSTANT_Methodref (the shim is a class)
        putCpU2(cpBytes, entry.start - 10 + 1, compatClassIndex!!)
    }

    val output = ByteArrayOutputStream(bytes.size + additions.size())
    val data = DataOutputStream(output)
    data.write(bytes, 0, 8)
    data.writeShort(newCpCount)
    data.write(cpBytes)
    data.write(additions.toByteArray())
    data.write(bytes, cpEnd, bytes.size - cpEnd)
    return output.toByteArray() to targets.size
}

val patchNewPipeAndroid9Api = tasks.register("patchNewPipeAndroid9Api") {
    inputs.file(newPipeOriginalJar)
    outputs.file(patchedNewPipeJar)
    doLast {
        val inputJar = newPipeOriginalJar.get()
        val outputJar = patchedNewPipeJar.get().asFile
        outputJar.parentFile.mkdirs()
        var patchedReferences = 0
        JarFile(inputJar).use { source ->
            JarOutputStream(outputJar.outputStream().buffered()).use { destination ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val normalizedName = entry.name.uppercase(Locale.ROOT)
                    if (normalizedName.startsWith("META-INF/") &&
                        (normalizedName.endsWith(".SF") || normalizedName.endsWith(".RSA") ||
                            normalizedName.endsWith(".DSA") || normalizedName.endsWith(".EC"))
                    ) continue
                    val sourceBytes = source.getInputStream(entry).use { it.readBytes() }
                    val patchedBytes = if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        val (rewritten, count) = rewriteNewPipeClass(sourceBytes)
                        patchedReferences += count
                        rewritten
                    } else sourceBytes
                    val outputEntry = JarEntry(entry.name)
                    if (entry.time > 0L) outputEntry.time = entry.time
                    destination.putNextEntry(outputEntry)
                    destination.write(patchedBytes)
                    destination.closeEntry()
                }
            }
        }
        if (patchedReferences == 0) {
            throw GradleException(
                "NewPipe Android 9 patch found no URLDecoder/URLEncoder Charset overloads; " +
                    "refusing to build an APK with the known API crash."
            )
        }
        logger.lifecycle("Patched $patchedReferences NewPipe URL charset call-site(s) for Android 9.")
    }
}


dependencies {
    // Backport newer Java library APIs used by NewPipeExtractor to Android 7–12.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    // Provide a real org.json implementation for JVM unit tests; Android SDK stubs return empty values.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

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
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")

    // NewPipe Extractor: patch Android 9-incompatible URL charset overloads before D8.
    add(newPipeExtractorRuntime.name, "com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    add("implementation", files(patchedNewPipeJar).builtBy(patchNewPipeAndroid9Api))
    add("implementation", files(newPipeTransitiveFiles))

    // yt-dlp 通用站点提取（B站/TikTok/Twitter 等 1000+ 网站；YouTube 仍走 NewPipe 优先）
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")

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
