package io.translatetube.local.tts

/** Bounds one subtitle cue before a TTS provider can allocate an unbounded audio buffer. */
internal object TtsTextPolicy {
    const val MAX_CODE_POINTS = 1_000

    fun validate(text: String) {
        if (text.isBlank()) {
            throw TtsInputLimitException("字幕文本为空，无法生成配音。")
        }
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints > MAX_CODE_POINTS) {
            throw TtsInputLimitException(
                "单条字幕超过 ${MAX_CODE_POINTS} 个字符。为避免配音合成占用过多内存，请先拆分这条字幕。"
            )
        }
    }

    /** 文本里有没有可以念出来的内容（字母、数字、汉字）。只有标点/符号/表情的字幕念不出声。 */
    fun hasSpeakableContent(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * 去掉在线引擎无法处理、常导致"返回 0 字节音频"的字符：控制符、零宽/格式符、私用区、
     * 未分配码位、落单的代理项、变体选择符以及表情/象形符号（U+1F000 以上）。
     * 其余符号（℃、%、+ 等）保留——引擎会按字面念出。
     */
    fun sanitizeForSpeech(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val keep = when {
                cp == '\n'.code || cp == '\t'.code -> true
                cp == '\r'.code -> false
                cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF -> false
                cp >= 0x1F000 -> false
                else -> when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
                    Character.SURROGATE, Character.UNASSIGNED -> false
                    else -> true
                }
            }
            if (keep) out.appendCodePoint(cp)
        }
        return out.toString().replace(Regex("[ \\u3000]{2,}"), " ").trim()
    }

    /**
     * 整段反复拿不到音频时的小段重试：按句读切开，太短的小段并入下一段，避免一个字一次请求。
     * 没有可切的位置时返回原文一段。
     */
    fun splitIntoPieces(text: String, minCodePoints: Int = 8): List<String> {
        val breaks = setOf('。', '！', '？', '；', '，', '、', '\n', '.', '!', '?', ';', ',')
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in breaks && current.codePointCount(0, current.length) >= minCodePoints) {
                pieces += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) {
            if (pieces.isNotEmpty() && current.codePointCount(0, current.length) < minCodePoints) {
                pieces[pieces.lastIndex] = pieces.last() + current
            } else {
                pieces += current.toString()
            }
        }
        return pieces.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

internal class TtsInputLimitException(message: String) : IllegalArgumentException(message)
