package io.translatetube.local.translate

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * 记录"用户已经看到内容安全过滤报错"的视频。
 *
 * 翻译服务商的安全过滤拒绝某几句时，先停下来报错；用户手动点重试后，这个视频的被拒句子才改用
 * 在线备用翻译引擎。这里只存视频 key，翻译成功后清掉。
 */
internal object ContentFilterFallbackStore {
    private const val PREFS = "translation_content_filter_fallback"
    private const val KEY_ARMED = "armed_video_keys"
    private const val MAX_KEYS = 50

    fun arm(context: Context, videoKey: String) = update(context) { it += videoKey }

    fun clear(context: Context, videoKey: String) = update(context) { it -= videoKey }

    fun isArmed(context: Context, videoKey: String): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ARMED, emptySet())
            .orEmpty()
            .contains(videoKey)
    }.getOrDefault(false)

    private fun update(context: Context, change: (MutableSet<String>) -> Unit) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // SharedPreferences returns a set that must not be modified in place.
            val keys = LinkedHashSet(prefs.getStringSet(KEY_ARMED, emptySet()).orEmpty())
            change(keys)
            while (keys.size > MAX_KEYS) keys.remove(keys.first())
            prefs.edit().putStringSet(KEY_ARMED, keys).apply()
        }
    }
}

/** 被安全过滤拒绝的句子：先报错，用户点重试后改走在线备用引擎链。 */
internal object ContentFilterFallbackPolicy {
    /** 一句话的备用翻译结果；[translation] 为空表示所有备用引擎都没翻出有效译文。 */
    data class RowResult(
        val translation: String?,
        val engineName: String?,
        val failures: List<String>,
    )

    /** 免密钥在线引擎，按顺序尝试：微软优先，不行再换其他。 */
    fun defaultOnlineEngines(): List<TranslationEngine> = listOf(
        MicrosoftWebEngine(),
        GoogleWebEngine(),
        BaiduWebEngine(),
        DeepLWebEngine(),
    )

    /**
     * 依次用 [engines] 翻译这一句，第一个返回有效译文的引擎胜出。引擎抛异常或译文没通过 [accept]
     * 都算这个引擎失败，换下一个；取消（[CancellationException]）原样抛出。
     */
    suspend fun translateRow(
        sourceText: String,
        engines: List<TranslationEngine>,
        src: String,
        tgt: String,
        accept: (candidate: String) -> Boolean,
    ): RowResult {
        val failures = ArrayList<String>(engines.size)
        for (engine in engines) {
            try {
                val candidate = engine.translate(listOf(sourceText), src, tgt).singleOrNull()?.trim()
                when {
                    candidate.isNullOrBlank() -> failures += "${engine.displayName}：没有返回译文"
                    !accept(candidate) -> failures += "${engine.displayName}：译文没通过校验"
                    else -> return RowResult(candidate, engine.displayName, failures)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                failures += "${engine.displayName}：${reason.replace('\n', ' ').take(80)}"
            }
        }
        return RowResult(null, null, failures)
    }

    /** "第 41 行「原文」"，最多列 [maxRows] 句，每句原文最多 [maxChars] 字。 */
    fun describeRefusedRows(
        rows: List<Pair<Int, String>>,
        maxRows: Int = 3,
        maxChars: Int = 120,
    ): String {
        if (rows.isEmpty()) return ""
        val shown = rows.take(maxRows).joinToString("；") { (index, text) ->
            val clean = text.replace(Regex("\\s+"), " ").trim()
            val snippet = if (clean.length > maxChars) clean.take(maxChars) + "…" else clean
            "第 ${index + 1} 行「$snippet」"
        }
        return if (rows.size > maxRows) shown + " 等共 ${rows.size} 句" else shown
    }

    /**
     * 安全过滤失败提示。[rows] 是被拒句子（0 起始行号 → 原文）；[fallbackTried] 为 true 表示这次
     * 重试已经用过在线备用引擎，仍然没翻出来。
     */
    fun failureMessage(rows: List<Pair<Int, String>>, fallbackTried: Boolean): String {
        val sentences = describeRefusedRows(rows)
        return if (fallbackTried) {
            "字幕翻译失败：安全过滤拒绝翻译，在线备用翻译引擎（微软、Google、百度、DeepL）也没能翻出这些句子：" +
                sentences + "。已停止处理，可再次重试，或更换翻译引擎。"
        } else {
            "字幕翻译失败：安全过滤拒绝翻译（翻译服务商的内容安全过滤拒绝了这些句子：" + sentences +
                "）。已停止处理。点“重试”会自动改用在线翻译引擎（微软优先，不行再换其他）翻译这些被拒的句子，" +
                "其余句子仍用当前翻译引擎。"
        }
    }
}
