package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.translatetube.local.subs.MlKitSourceLanguageIdentifier
import java.util.Locale

/** Language-pair models shown in the dedicated offline translation screen. */
object SinglePairModelLanguagePolicy {
    private val supportedSources = setOf("ja", "ko", "ru")

    fun canonicalSource(languageTag: String): String =
        LocalTranslationEnginePolicy.canonicalOpusSource(languageTag)

    fun supportsSource(languageTag: String): Boolean =
        canonicalSource(languageTag) in supportedSources
}

/** Offline source→English model followed by the shared English→Chinese ONNX model. */
class SinglePairTranslationEngine(private val context: Context) : TranslationEngine {
    override val id: String = "single-pair-model"
    override val displayName: String = "单语言对翻译模型"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.none { it.isNotBlank() }) return lines.map { "" }

        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)
        if (target !in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn")) {
            throw IllegalStateException("单语言对翻译模型的目标语言固定为简体中文。")
        }

        val normalizedSource = SourceLanguagePolicy.sourceForTranslation(srcLang)
        val sourceUndetermined = SourceLanguagePolicy.isUndetermined(normalizedSource)
        val detectedSource = if (sourceUndetermined) {
            MlKitSourceLanguageIdentifier.detect(lines)
        } else normalizedSource
        if (SourceLanguagePolicy.isUndetermined(detectedSource)) {
            throw IllegalStateException("无法自动识别原文语言，请在单语言对翻译模型管理页手动选择日语、韩语或俄语。")
        }

        val source = SinglePairModelLanguagePolicy.canonicalSource(detectedSource)
        if (!SinglePairModelLanguagePolicy.supportsSource(source)) {
            throw IllegalStateException(
                "单语言对翻译模型支持日语、韩语、俄语 → 英语 → 简体中文；当前识别为“${LangMap.display(source)}”。" +
                    "请在管理页选择受支持的源语言，或切换其他翻译引擎。"
            )
        }
        if (!OpusMtAdditionalModelManager.isReady(context, source)) {
            throw IllegalStateException(
                "单语言对翻译模型（${LangMap.display(source)} → 英语）尚未下载并通过试译验证，请到本地翻译 → 单语言对翻译模型 → 管理中下载。"
            )
        }
        if (!OpusMtModelManager.isReady(context)) {
            throw IllegalStateException(
                "还需要下载并验证共用的英语 → 简体中文模型。请到本地翻译 → 单语言对翻译模型 → 管理中下载共享模型。"
            )
        }

        AppLogStore.info(
            context.applicationContext,
            "Translation",
            "开始执行单语言对本地两阶段翻译",
            diagnosticContext = mapOf(
                "引擎" to id,
                "源语言" to source,
                "目标语言" to "zh-CN",
                "第一阶段模型" to OpusMtAdditionalModelManager.officialModelId(source),
                "第二阶段模型" to "onnx-community/opus-mt-en-zh",
                "字幕条数" to lines.size.toString(),
                "未记录字幕原文" to "true",
            ),
        )

        val englishLines = try {
            OpusMtCt2Runtime.translate(context.applicationContext, source, lines)
        } finally {
            // Free the first-stage model before loading ONNX, especially on lower-memory phones.
            OpusMtCt2Runtime.release(source)
        }
        if (englishLines.size != lines.size) {
            throw IllegalStateException("第一阶段模型返回的字幕条数与原文不一致。")
        }

        return try {
            val chineseLines = withContext(Dispatchers.Default) {
                val output = ArrayList<String>(englishLines.size)
                for (englishLine in englishLines) {
                    currentCoroutineContext().ensureActive()
                    output += if (englishLine.isBlank()) "" else OpusMtRuntime.translateOne(
                        context.applicationContext,
                        englishLine,
                    )
                }
                output
            }
            if (chineseLines.size != lines.size) {
                throw IllegalStateException("第二阶段模型返回的字幕条数与原文不一致。")
            }
            AppLogStore.info(
                context.applicationContext,
                "Translation",
                "单语言对本地两阶段翻译完成",
                diagnosticContext = mapOf(
                    "源语言" to source,
                    "目标语言" to "zh-CN",
                    "翻译路径" to "源语言 → 英语 → 简体中文",
                    "字幕条数" to chineseLines.size.toString(),
                    "未记录字幕原文" to "true",
                ),
            )
            chineseLines
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogStore.error(
                context.applicationContext,
                "Translation",
                "单语言对本地两阶段翻译失败",
                error,
                diagnosticContext = mapOf(
                    "源语言" to source,
                    "目标语言" to "zh-CN",
                    "失败阶段" to "英语 → 简体中文",
                    "字幕条数" to lines.size.toString(),
                    "未记录字幕原文" to "true",
                ),
            )
            throw IllegalStateException("单语言对翻译失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }
}
