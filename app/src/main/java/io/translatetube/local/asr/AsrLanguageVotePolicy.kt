package io.translatetube.local.asr

/**
 * 整段视频的语言提示 = 各语音段检测结果按"音频时长"投票的多数，而不是最后一个解码段的结果。
 * 短句（几个字的语气词、结尾一句 "Thanks."）很容易被认成别的语言；按时长加权后，
 * 长段落的结果自然压过它们。只有真正识别出文字的段才参与投票。
 */
internal class AsrLanguageVotePolicy {
    private val weightsMs = LinkedHashMap<String, Long>()
    private var latest = ""

    /** 记一票。语言为空或权重 ≤ 0 的不算。 */
    fun add(language: String?, weightMs: Long) {
        val lang = language?.trim().orEmpty()
        if (lang.isEmpty() || weightMs <= 0L) return
        val total = (weightsMs[lang] ?: 0L)
        weightsMs[lang] = if (total > Long.MAX_VALUE - weightMs) Long.MAX_VALUE else total + weightMs
        latest = lang
    }

    /** 得票时长最多的语言；时长相同取最近出现的那个；没有任何投票返回空串。 */
    fun best(): String {
        if (weightsMs.isEmpty()) return ""
        val topWeight = weightsMs.values.max()
        return if ((weightsMs[latest] ?: 0L) == topWeight) {
            latest
        } else {
            weightsMs.entries.last { it.value == topWeight }.key
        }
    }

    fun clear() {
        weightsMs.clear()
        latest = ""
    }
}
