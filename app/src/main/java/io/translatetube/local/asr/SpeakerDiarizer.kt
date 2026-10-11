package io.translatetube.local.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Optional local speaker diarization using the OfflineSpeakerDiarization API bundled in
 * sherpa-onnx. Reflection keeps the app compilable with older AARs; if its JNI/API is too old,
 * the UI reports that the bundled runtime must be updated instead of crashing playback.
 */
object SpeakerDiarizer {
    data class Segment(val startSec: Float, val endSec: Float, val speaker: Int, val confidence: Float)

    private const val SEGMENTATION_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
    private const val EMBEDDING_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun diarize(
        context: Context,
        samples: FloatArray,
        onProgress: (String) -> Unit = {},
    ): List<Segment> = withContext(Dispatchers.IO) {
        require(samples.isNotEmpty()) { "没有可用于人物识别的音频" }
        // Check the bundled AAR before downloading ~45 MB of models.
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization", true, javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("当前内置 sherpa-onnx.aar 不包含说话人分离模块，需要更新 sherpa-onnx.aar 后才能自动识别；可先手动切换字幕音色", e)
        } catch (e: LinkageError) {
            throw IllegalStateException("当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试", e)
        }
        onProgress("下载人物识别模型…")
        val dir = File(context.filesDir, "models/speaker").apply { mkdirs() }
        val segmentation = File(dir, "segmentation.onnx")
        val embedding = File(dir, "embedding.onnx")
        if (!segmentation.isFile || segmentation.length() < 1_000_000L) {
            val archive = File(context.cacheDir, "speaker-segmentation.tar.bz2")
            try {
                download(SEGMENTATION_URL, archive, 32L * 1024 * 1024)
                extractSegmentation(archive, segmentation)
            } finally {
                archive.delete()
            }
        }
        if (!embedding.isFile || embedding.length() < 5_000_000L) {
            val temp = File(dir, "embedding.onnx.download")
            try {
                download(EMBEDDING_URL, temp, 100L * 1024 * 1024)
                if (temp.length() < 5_000_000L) throw java.io.IOException("人物声纹模型文件不完整")
                if (embedding.exists() && !embedding.delete()) throw java.io.IOException("无法替换旧声纹模型")
                if (!temp.renameTo(embedding)) throw java.io.IOException("无法安装声纹模型")
            } finally {
                temp.delete()
            }
        }

        onProgress("识别人物说话片段…")
        try {
            val loader = javaClass.classLoader!!
            fun type(name: String) = Class.forName("com.k2fsa.sherpa.onnx.$name", true, loader)
            val pyannoteClass = type("OfflineSpeakerSegmentationPyannoteModelConfig")
            val segmentationClass = type("OfflineSpeakerSegmentationModelConfig")
            val embeddingClass = type("SpeakerEmbeddingExtractorConfig")
            val clusteringClass = type("FastClusteringConfig")
            val configClass = type("OfflineSpeakerDiarizationConfig")
            val diarizerClass = type("OfflineSpeakerDiarization")

            val pyannote = pyannoteClass.constructors.first { it.parameterCount == 2 }
                .newInstance(segmentation.absolutePath, 0.1f)
            val segmentationConfig = segmentationClass.constructors.first { it.parameterCount == 4 }
                .newInstance(pyannote, 1, false, "cpu")
            val embeddingConfig = embeddingClass.constructors.first { it.parameterCount == 4 }
                .newInstance(embedding.absolutePath, 1, false, "cpu")
            val clustering = clusteringClass.constructors.firstOrNull { it.parameterCount == 3 }
                ?.newInstance(-1, 0.5f, true)
                ?: clusteringClass.constructors.first { it.parameterCount == 2 }
                    .newInstance(-1, 0.5f)
            val config = configClass.constructors.first { it.parameterCount == 5 }
                .newInstance(segmentationConfig, embeddingConfig, clustering, 0.2f, 0.5f)
            val instance = diarizerClass.constructors.first { it.parameterCount == 2 }
                .newInstance(null, config)
            try {
                val sampleRate = diarizerClass.getMethod("sampleRate").invoke(instance) as Int
                require(sampleRate == 16000) { "人物识别模型要求 16kHz 音频，当前为 ${sampleRate}Hz" }
                // AudioExtract caps analysis at 8 minutes on low-memory devices.
                // Avoid copyOf(): duplicating a multi-minute FloatArray can cause OOM on older phones.
                val raw = diarizerClass.getMethod("process", FloatArray::class.java).invoke(instance, samples)
                val count = java.lang.reflect.Array.getLength(raw)
                val out = ArrayList<Segment>(count)
                for (i in 0 until count) {
                    val item = java.lang.reflect.Array.get(raw, i)
                    val cls = item.javaClass
                    val start = (cls.getMethod("getStart").invoke(item) as Number).toFloat()
                    val end = (cls.getMethod("getEnd").invoke(item) as Number).toFloat()
                    val speaker = (cls.getMethod("getSpeaker").invoke(item) as Number).toInt()
                    val confidence = (runCatching { cls.getMethod("getConfidence").invoke(item) as Number }.getOrNull()?.toFloat() ?: -2f)
                    if (end > start && speaker >= 0) out += Segment(start, end, speaker, confidence)
                }
                out
            } finally {
                runCatching { diarizerClass.getMethod("release").invoke(instance) }
            }
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "当前内置 sherpa-onnx AAR 不包含说话人分离模块，需要更新 sherpa-onnx.aar 后才能自动识别；可先手动切换字幕音色",
                e,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("说话人分离模型初始化或推理失败：${e.targetException.message}", e.targetException)
        } catch (e: LinkageError) {
            throw IllegalStateException("当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试", e)
        }
    }

    private fun download(url: String, destination: File, maxBytes: Long) {
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()
        try {
            val request = Request.Builder().url(url).header("User-Agent", "TranslateTube/1.0").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("模型下载失败 HTTP ${response.code}")
                val body = response.body ?: throw java.io.IOException("模型下载响应为空")
                if (body.contentLength() > maxBytes) throw java.io.IOException("模型文件超过大小限制")
                var total = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw java.io.IOException("模型文件超过大小限制")
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                }
                if (total == 0L) throw java.io.IOException("下载的模型文件为空")
            }
            if (destination.exists() && !destination.delete()) throw java.io.IOException("无法替换旧模型文件")
            if (!temp.renameTo(destination)) throw java.io.IOException("无法安装模型文件")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun extractSegmentation(archive: File, destination: File) {
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()
        var found = false
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive))).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (entry.isSymbolicLink || entry.isLink) throw java.io.IOException("模型包包含不安全的链接")
                    if (entry.isDirectory || !entry.name.replace("\\", "/").endsWith("/model.onnx") &&
                        entry.name != "model.onnx") continue
                    if (found) throw java.io.IOException("模型包包含多个候选模型")
                    var total = 0L
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = tar.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > 30L * 1024 * 1024) throw java.io.IOException("分离模型超过大小限制")
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                    if (total < 1_000_000L) throw java.io.IOException("分离模型文件不完整")
                    found = true
                }
            }
            if (!found) throw java.io.IOException("模型包中未找到 model.onnx")
            if (destination.exists() && !destination.delete()) throw java.io.IOException("无法替换旧分离模型")
            if (!temp.renameTo(destination)) throw java.io.IOException("无法安装分离模型")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }
}
