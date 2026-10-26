package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.subs.MlKitSourceLanguageIdentifier
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Source languages available to the direct Chinese single-pair translation entry. */
object SinglePairModelLanguagePolicy {
    private val supportedSources = setOf("ja", "ko", "ru")

    fun canonicalSource(languageTag: String): String =
        LocalTranslationEnginePolicy.canonicalOpusSource(languageTag)

    fun supportsSource(languageTag: String): Boolean =
        canonicalSource(languageTag) in supportedSources
}

/**
 * Direct source→Simplified Chinese translation. This entry reuses the verified SMaLL-100
 * multilingual runtime, so Japanese/Korean/Russian are translated in one model pass without
 * an English intermediate or a second model load.
 */
class SinglePairTranslationEngine(private val context: Context) : TranslationEngine {
    private val appContext = context.applicationContext
    override val id: String = "single-pair-direct-small100"
    override val displayName: String = "单语言对直译模型"

    /**
     * Resolve and validate the subtitle language before playback begins translation batching.
     * Prefer distinctive script detection, with ML Kit as a fallback for ambiguous text.
     */
    suspend fun validateConfiguration(
        srcLang: String,
        tgtLang: String,
        sampleLines: List<String> = emptyList(),
    ): String {
        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)
        if (target !in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn", "zh-sg")) {
            throw IllegalStateException("单语言对直译模型的目标语言固定为简体中文。")
        }

        val normalizedSource = SourceLanguagePolicy.sourceForTranslation(srcLang)
        val sourceUndetermined = SourceLanguagePolicy.isUndetermined(normalizedSource)
        val scriptHint = io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(sampleLines)
        // Trust a distinctive supported source script over a possibly stale provider/track hint.
        val detectedSource = when {
            SinglePairModelLanguagePolicy.supportsSource(scriptHint) -> scriptHint
            sourceUndetermined -> MlKitSourceLanguageIdentifier.detect(sampleLines)
            else -> normalizedSource
        }

        if (SourceLanguagePolicy.isUndetermined(detectedSource)) {
            throw IllegalStateException(
                "单语言对直译模型无法可靠识别原文语言。请在管理页手动选择日语、韩语或俄语后重试。"
            )
        }

        val source = SinglePairModelLanguagePolicy.canonicalSource(detectedSource)
        if (!SinglePairModelLanguagePolicy.supportsSource(source)) {
            throw IllegalStateException(
                "单语言对直译模型仅支持日语、韩语、俄语直接翻译至简体中文；当前识别为“${LangMap.display(source)}”。" +
                    "请在管理页选择受支持的源语言，或切换其他翻译引擎。"
            )
        }
        if (!Small100LanguagePolicy.supportsSource(source)) {
            throw IllegalStateException(
                "当前 SMaLL-100 模型版本不支持直接翻译源语言“${LangMap.display(source)}”（${source}）。"
            )
        }
        if (!Small100ModelManager.isReady(appContext)) {
            throw IllegalStateException(
                "单语言对翻译已改为直接翻译，不再经过英语。请到本地翻译 → 单语言对翻译模型 → 管理中下载并验证共用的 SMaLL-100 模型（约 615 MB）。"
            )
        }
        return source
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.none { it.isNotBlank() }) return lines.map { "" }

        val source = validateConfiguration(srcLang, tgtLang, lines)
        AppLogStore.info(
            appContext,
            "Translation",
            "开始执行单语言对本地直译",
            diagnosticContext = mapOf(
                "引擎" to id,
                "源语言" to source,
                "目标语言" to "zh-CN",
                "模型" to "casawolice/small100-onnx",
                "翻译路径" to "${source} → 简体中文",
                "英语中转" to "false",
                "字幕条数" to lines.size.toString(),
                "未记录字幕原文" to "true",
            ),
        )

        return try {
            // Small100Engine calls the single multilingual model directly for each subtitle line.
            val chineseLines = Small100Engine(appContext).translate(lines, source, tgtLang)
            if (chineseLines.size != lines.size) {
                throw IllegalStateException("直译模型返回的字幕条数与原文不一致。")
            }
            AppLogStore.info(
                appContext,
                "Translation",
                "单语言对本地直译完成",
                diagnosticContext = mapOf(
                    "源语言" to source,
                    "目标语言" to "zh-CN",
                    "翻译路径" to "${source} → 简体中文",
                    "模型调用次数阶段" to "单模型直译",
                    "英语中转" to "false",
                    "字幕条数" to chineseLines.size.toString(),
                    "未记录字幕原文" to "true",
                ),
            )
            chineseLines
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogStore.error(
                appContext,
                "Translation",
                "单语言对本地直译失败",
                error,
                diagnosticContext = mapOf(
                    "源语言" to source,
                    "目标语言" to "zh-CN",
                    "翻译路径" to "${source} → 简体中文",
                    "英语中转" to "false",
                    "字幕条数" to lines.size.toString(),
                    "未记录字幕原文" to "true",
                ),
            )
            throw IllegalStateException("单语言对直译失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }
}
