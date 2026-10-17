package io.translatetube.local.subs

import java.util.Locale

/** 一条字幕行：用于 UI 显示 / 翻译 / 配音对轴 */
data class SubLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,          // 原文
    val zh: String? = null,    // 中文译文
    val speakerId: Int = -1,   // -1=使用当前默认音色；非负数=Kokoro 音色 ID
)

object SubtitleParsers {

    private const val MAX_SUBTITLE_CUE_COUNT = 500_000

    private fun ensureCueCapacity(currentSize: Int) {
        require(currentSize < MAX_SUBTITLE_CUE_COUNT) {
            "字幕条数超过安全上限（${MAX_SUBTITLE_CUE_COUNT}），已停止处理以保护设备内存"
        }
    }

    // Hot-path patterns are shared so long subtitle files do not recompile them per cue.
    private val webVttTimestampRegex = Regex("""(\d{1,}:)?\d{1,2}:\d{2}[.,]\d{1,6}\s*-->\s*(\d{1,}:)?\d{1,2}:\d{2}[.,]\d{1,6}(?=\s|$)""")
    private val ttmlCueRegex = Regex(
        """<(?:[A-Za-z_][\w.-]*:)?p\b([^>]*)>(.*?)</(?:[A-Za-z_][\w.-]*:)?p\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ttmlAttributeRegex = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")
    private val ttmlLineBreakRegex = Regex("""<(?:[A-Za-z_][\w.-]*:)?br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ttmlRootRegex = Regex("""<(?:[A-Za-z_][\w.-]*:)?tt[\s>]""", RegexOption.IGNORE_CASE)
    private val translationProgressRegex = Regex("^(正在)?翻译中\\d*/\\d*行?$")
    private val translationChunkRegex = Regex("(?<=[。！？!?；;，,])")
    private val whitespaceRegex = Regex("\\s+")
    private val inlineWhitespaceRegex = Regex("[ \\t]+")
    private val subtitleControlCharactersRegex = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val unusualSubtitleWhitespaceRegex = Regex("[\\u00A0\\u202F\\u2007\\u3000]")

    /** Normalize visually equivalent Unicode spaces and discard hidden control bytes from captions. */
    private fun normalizeSubtitleWhitespace(text: String): String = text
        .replace(subtitleControlCharactersRegex, "")
        .replace('\uFEFF', ' ')
        .replace(unusualSubtitleWhitespaceRegex, " ")
        .replace(whitespaceRegex, " ")
        .trim()
    private val captionComparisonTrailingPunctuation = setOf(
        '.', ',', '!', '?', '。', '，', '！', '？', '；', ';', ':', '：', '…', '、'
    )
    private val placeholderNormalizationRegex = Regex("[\\s…。.！!·:：]+")
    private val placeholderTranslations = setOf(
        "正在翻译", "正在翻译中", "翻译中", "译文暂不可用", "暂无译文",
        "translating", "translationinprogress", "loadingtranslation",
    )
    private val punctuationSymbolsWhitespaceRegex = Regex("[\\p{P}\\p{S}\\s]")
    private val chineseCharRegex = Regex("[\\u4E00-\\u9FFF]")
    private val assDialogueHeaderRegex = Regex("""(?im)^\s*Dialogue\s*:""")
    private val assOverrideRegex = Regex("""\{[^}]*\}""")
    private val assLinebreakRegex = Regex("""\\N|\\n""")
    private val assHardSpaceRegex = Regex("""\\h""")
    private val defaultAssFormat = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"
    )
    private val htmlTagRegex = Regex("""</?[A-Za-z][^<>]*>|<\d{2,}:\d{2}:\d{2}[.,]\d{1,6}>""")



    fun fmtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0L) / 1000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    /** WebVTT → 行列表（YouTube timedtext 返回 vtt） */
    fun parseVtt(src: String): List<SubLine> = parseVtt(src) {}

    internal fun parseVtt(src: String, checkActive: () -> Unit): List<SubLine> {
        val out = mutableListOf<SubLine>()
        var idx = 0
        var pendingStart = -1L
        var pendingEnd = -1L
        val buf = StringBuilder()
        var inNoteBlock = false
        fun flush() {
            val txt = buf.toString().trim()
            if (pendingStart >= 0 && pendingEnd > pendingStart && txt.isNotEmpty()) {
                ensureCueCapacity(out.size)
                out += SubLine(idx++, pendingStart, pendingEnd, txt)
            }
            buf.clear(); pendingStart = -1; pendingEnd = -1
        }
        for ((lineIndex, raw) in src.lineSequence().withIndex()) {
            if ((lineIndex and 0xff) == 0) checkActive()
            val line = raw.trim()
            if (line.isEmpty()) {
                // A blank line closes a WebVTT cue. Do not attach comments or orphan text to it.
                if (!inNoteBlock) flush()
                inNoteBlock = false
                continue
            }
            if (inNoteBlock) continue
            if (pendingStart < 0 &&
                (line == "NOTE" || line.startsWith("NOTE ") || line.startsWith("NOTE\t"))
            ) {
                inNoteBlock = true
                continue
            }

            val m = webVttTimestampRegex.find(line)
            // WebVTT cue identifiers occupy their own line. Do not accept a timestamp substring
            // embedded in arbitrary text as a valid cue boundary.
            if (m != null && m.range.first == 0) {
                flush()
                val parts = m.value.split("-->")
                pendingStart = parseVttTs(parts[0].trim()) ?: -1L
                pendingEnd = parseVttTs(parts[1].trim()) ?: -1L
            } else if (pendingStart >= 0 && !line.startsWith("WEBVTT") &&
                !line.startsWith("Kind:") && !line.startsWith("Language:")
            ) {
                buf.appendLine(stripTags(line))
            }
        }
        flush()
        return normalizeTimeline(out, checkActive)
    }

    /** SRT（1 行格式 00:00:01,000 --> 00:00:04,000） */
    fun parseSrt(src: String): List<SubLine> = parseSrt(src) {}

    internal fun parseSrt(src: String, checkActive: () -> Unit): List<SubLine> =
        parseVtt(src, checkActive)

    /** 将字幕行导出为标准 WebVTT，跳过空文本和非法时间窗，并按时间排序。 */
    fun toVtt(lines: List<SubLine>): String = buildVtt(lines) { it.text }

    /**
     * Return translated text while preserving source captions copied into [SubLine.zh].
     * Chinese-source flows intentionally set zh == text; duplicate-translation cleanup must not
     * erase legitimate repeated words from the original caption.
     */
    fun preferredTranslationText(line: SubLine): String? {
        val raw = line.zh?.let(::normalizeSubtitleWhitespace)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val source = normalizeSubtitleWhitespace(line.text)
        if (source.isNotEmpty() && source == raw) return source
        return cleanTranslatedText(raw)
    }

    /**
     * Export translated captions as SRT. Invalid cues are dropped before sequential numbering.
     * Bilingual output keeps Chinese first and the original text below it.
     */
    fun toSrt(lines: List<SubLine>, bilingual: Boolean = false): String = buildString {
        var sequence = 0
        lines.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<SubLine> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
            .forEach { line ->
                val source = line.text.trim()
                val translated = preferredTranslationText(line)
                val caption = when {
                    bilingual && translated != null && !sameCaptionText(source, translated) ->
                        "$translated\n$source"
                    translated != null -> translated
                    else -> source
                }.trim()
                if (caption.isBlank()) return@forEach

                sequence++
                appendLine(sequence)
                appendLine(formatSrtTime(line.startMs) + " --> " + formatSrtTime(line.endMs))
                caption.lines().forEach { appendLine(it.trimEnd()) }
                appendLine()
            }
    }

    /**
     * Copy the playable subtitle timeline as plain text in the same Chinese-first order as exports.
     * Malformed timing rows are omitted so copy/export/playback agree on which cues are valid.
     */
    fun toPlainText(lines: List<SubLine>, bilingual: Boolean = true): String = buildString {
        lines.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<SubLine> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
            .forEach { line ->
                val source = line.text.trim()
                val translated = preferredTranslationText(line)
                val caption = when {
                    bilingual && translated != null && !sameCaptionText(source, translated) ->
                        "$translated\n$source"
                    translated != null -> translated
                    else -> source
                }.trim()
                if (caption.isBlank()) return@forEach
                append(caption).append('\n').append('\n')
            }
    }.trim()

    /** Export Chinese-first bilingual WebVTT without cloning the entire subtitle list. */
    fun toBilingualVtt(lines: List<SubLine>): String = buildVtt(lines) { line ->
        val source = line.text.trim()
        val translated = preferredTranslationText(line)
        when {
            translated != null && !sameCaptionText(source, translated) -> "$translated\n$source"
            translated != null -> translated
            else -> source
        }
    }

    private fun buildVtt(lines: List<SubLine>, captionText: (SubLine) -> String): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        lines.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<SubLine> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
            .forEach { line ->
                val caption = captionText(line)
                if (caption.isBlank()) return@forEach
                appendLine(formatVttTime(line.startMs) + " --> " + formatVttTime(line.endMs))
                caption.lines().forEach { appendLine(escapeVttCueText(it).trimEnd()) }
                appendLine()
            }
    }

    /** Emit plain text as WebVTT cue text, not accidental markup or entity references. */
    private fun escapeVttCueText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun sameCaptionText(left: String, right: String): Boolean {
        fun normalize(value: String): String = normalizeSubtitleWhitespace(value)
            .trimEnd { it.isWhitespace() || it in captionComparisonTrailingPunctuation }
            .lowercase(Locale.ROOT)
        return normalize(left) == normalize(right)
    }

    private fun formatSrtTime(ms: Long): String = formatTimestamp(ms, ',')

    private fun formatVttTime(ms: Long): String = formatTimestamp(ms, '.')

    /** Avoid per-cue Formatter/vararg allocations when exporting long subtitle tracks. */
    private fun formatTimestamp(ms: Long, decimalSeparator: Char): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return buildString(24) {
            appendPadded(hours, 2)
            append(':')
            appendPadded(minutes, 2)
            append(':')
            appendPadded(seconds, 2)
            append(decimalSeparator)
            appendPadded(millis, 3)
        }
    }

    private fun StringBuilder.appendPadded(value: Long, width: Int) {
        val digits = value.toString()
        repeat((width - digits.length).coerceAtLeast(0)) { append('0') }
        append(digits)
    }

    /** YouTube timedtext XML（<transcript><text start="1.2" dur="3.4">…</text>）兜底 */
    fun parseTimedTextXml(src: String): List<SubLine> = parseTimedTextXml(src) {}

    internal fun parseTimedTextXml(src: String, checkActive: () -> Unit): List<SubLine> {
        if (!src.contains("<transcript", ignoreCase = true)) return emptyList()
        val out = mutableListOf<SubLine>()
        val re = timedTextCueRegex
        for ((matchIndex, match) in re.findAll(src).withIndex()) {
            if ((matchIndex and 0xff) == 0) checkActive()
            val timing = parseTimedTextTiming(match.groupValues[1]) ?: continue
            val rawText = match.groupValues[2]
            // Plain text needs no Android Html conversion; escaped entities and markup still do.
            val decodedText = SubtitleTextDecoder.decode(
                rawText.replace(ttmlLineBreakRegex, "\n").replace(htmlTagRegex, "")
            )
            val text = decodedText.trim()
            if (text.isNotEmpty()) {
                ensureCueCapacity(out.size)
                out += SubLine(out.size, timing.first, timing.second, text)
            }
        }
        return normalizeTimeline(out, checkActive)
    }

    private val timedTextCueRegex = Regex("""<text\b([^>]*)>(.*?)</text\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val timedTextAttributeRegex = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")

    /** Parse start/dur by attribute name, regardless of XML attribute order or quote style. */
    internal fun parseTimedTextTiming(attributes: String): Pair<Long, Long>? {
        val attrs = timedTextAttributeRegex.findAll(attributes)
            .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
        val startSeconds = attrs["start"]?.toDoubleOrNull() ?: return null
        // Default only when the provider omits duration. A present but malformed value
        // must reject the cue rather than silently inventing a three-second time window.
        val durationSeconds = if (attrs.containsKey("dur")) {
            attrs["dur"]?.toDoubleOrNull() ?: return null
        } else {
            3.0
        }
        if (!startSeconds.isFinite() || !durationSeconds.isFinite() ||
            startSeconds < 0.0 || durationSeconds <= 0.0
        ) return null

        val startMsDouble = startSeconds * 1000.0
        val durationMsDouble = durationSeconds * 1000.0
        if (!startMsDouble.isFinite() || !durationMsDouble.isFinite() ||
            startMsDouble >= Long.MAX_VALUE.toDouble() || durationMsDouble >= Long.MAX_VALUE.toDouble()
        ) return null
        val startMs = startMsDouble.toLong()
        val durationMs = durationMsDouble.toLong()
        if (durationMs <= 0L || startMs > Long.MAX_VALUE - durationMs) return null
        return startMs to (startMs + durationMs)
    }

    /** YouTube/NewPipe TTML：从 p 标签提取 begin/end/dur。 */
    fun parseTtml(src: String): List<SubLine> = parseTtml(src) {}

    internal fun parseTtml(src: String, checkActive: () -> Unit): List<SubLine> {
        if (!ttmlRootRegex.containsMatchIn(src) && !ttmlCueRegex.containsMatchIn(src)) {
            return emptyList()
        }
        val out = mutableListOf<SubLine>()
        val cueRegex = ttmlCueRegex
        val attributeRegex = ttmlAttributeRegex
        for ((matchIndex, match) in cueRegex.findAll(src).withIndex()) {
            if ((matchIndex and 0xff) == 0) checkActive()
            val attrs = attributeRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
            val start = (attrs["begin"] ?: attrs["start"])?.let { parseTtmlTime(it) }
                ?.takeIf { it >= 0L } ?: continue
            // An explicit but malformed end is not equivalent to a missing end. Do not
            // silently replace broken absolute timing with a relative duration and shift the cue.
            val end = if (attrs.containsKey("end")) {
                attrs["end"]?.let { parseTtmlTime(it) } ?: continue
            } else {
                val durationText = attrs["dur"] ?: continue
                val duration = parseTtmlTime(durationText) ?: continue
                if (duration <= 0L || start > Long.MAX_VALUE - duration) continue
                start + duration
            }
            if (end <= start) continue
            val body = match.groupValues[2].replace(ttmlLineBreakRegex, "\n")
            val text = SubtitleTextDecoder.decode(body.replace(htmlTagRegex, ""))
                .replace('\u00a0', ' ').replace(inlineWhitespaceRegex, " ").trim()
            if (text.isNotEmpty()) {
                ensureCueCapacity(out.size)
                out += SubLine(out.size, start, end, text)
            }
        }
        return normalizeTimeline(out, checkActive)
    }

    internal fun parseTtmlTime(value: String): Long? {
        val time = value.trim()
        if (time.isEmpty()) return null

        val milliseconds = when {
            time.endsWith("ms", ignoreCase = true) ->
                time.dropLast(2).trim().toDoubleOrNull() ?: return null
            time.endsWith("s", ignoreCase = true) ->
                (time.dropLast(1).trim().toDoubleOrNull() ?: return null) * 1000.0
            time.contains(":") -> return parseTtmlClockTime(time)
            else -> (time.toDoubleOrNull() ?: return null) * 1000.0
        }
        if (!milliseconds.isFinite() || milliseconds < 0.0 ||
            milliseconds >= Long.MAX_VALUE.toDouble()
        ) return null
        return milliseconds.toLong()
    }

    private fun parseTtmlClockTime(value: String): Long? {
        val parts = value.split(":")
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 && it < 60.0 } ?: return null
        val minuteText = parts[parts.lastIndex - 1]
        val minutes = minuteText.toLongOrNull()
            ?.takeIf { it >= 0L && (parts.size == 2 || it <= 59L) } ?: return null
        val hours = if (parts.size == 3) {
            parts[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        } else {
            0L
        }
        val milliseconds =
            hours.toDouble() * 3_600_000.0 + minutes.toDouble() * 60_000.0 + seconds * 1000.0
        if (!milliseconds.isFinite() || milliseconds >= Long.MAX_VALUE.toDouble()) return null
        return milliseconds.toLong()
    }

    /** 自动识别格式解析（vtt/srt/xml），输出保证按 startMs 单调有序（lineAt 二分前提） */
    fun parseAuto(src: String): List<SubLine> = parseAutoCancellable(src) {}

    /**
     * Cooperative parser variant for large imports and network captions. Format-specific parsers
     * already normalize their output, so do not sort/merge the same long timeline twice.
     */
    internal fun parseAutoCancellable(src: String, checkActive: () -> Unit): List<SubLine> {
        checkActive()
        val t = src.trimStart('\uFEFF').trimStart()
        val parsed = when {
            t.startsWith("WEBVTT") || t.contains("-->") -> parseVtt(t, checkActive)
            t.startsWith("<transcript", ignoreCase = true) -> parseTimedTextXml(t, checkActive)
            assDialogueHeaderRegex.containsMatchIn(t) -> parseAss(t, checkActive)
            t.startsWith("<?xml") || t.startsWith("<tt", ignoreCase = true) ||
                ttmlRootRegex.containsMatchIn(t) ->
                parseTtml(t, checkActive).ifEmpty { parseTimedTextXml(t, checkActive) }
            else -> parseSrt(t, checkActive)
        }
        checkActive()
        return parsed
    }

    /**
     * Recognize UI/progress placeholders that older builds could accidentally persist as translations.
     * Treat them as missing output, never as valid subtitle text.
     */
    fun isPlaceholderTranslation(translated: String?): Boolean {
        val normalized = translated
            ?.let(::normalizeSubtitleWhitespace)
            ?.lowercase(Locale.ROOT)
            ?.replace(placeholderNormalizationRegex, "")
            ?: return false
        if (normalized in placeholderTranslations) return true
        return translationProgressRegex.matches(normalized)
    }

    /**
     * Clean common duplicated translator output such as "你好，你好" or
     * "这是结果。这是结果。" without touching ordinary non-repeated clauses.
     * Also used when reading the existing translation cache so old bad results do not remain visible.
     */
    fun cleanTranslatedText(translated: String?): String? {
        val value = translated
            ?.let(::normalizeSubtitleWhitespace)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (isPlaceholderTranslation(value)) return null

        val chunks = translationChunkRegex.split(value)
        val collapsed = ArrayList<String>(chunks.size)
        var previousKey = ""
        var removedDuplicate = false
        for (chunk in chunks) {
            val key = chunk.lowercase(Locale.ROOT).replace(punctuationSymbolsWhitespaceRegex, "")
            val hasChinese = chineseCharRegex.containsMatchIn(key)
            if (hasChinese && key.length >= 2 && key == previousKey) {
                removedDuplicate = true
                continue
            }
            collapsed += chunk
            if (key.isNotEmpty()) previousKey = key
        }
        var cleaned = if (removedDuplicate) collapsed.joinToString("") else value

        // Some providers repeat a whole Chinese translation without punctuation: "你好你好".
        // Ignore trailing sentence punctuation for the duplicate check, then keep one copy of it.
        val trailingPunctuation = cleaned.takeLastWhile { it in "。！？!?；;，,、:：" }
        val compact = cleaned.dropLast(trailingPunctuation.length).replace(whitespaceRegex, "")
        if (compact.length >= 4 && compact.length % 2 == 0) {
            val half = compact.length / 2
            val first = compact.substring(0, half)
            val second = compact.substring(half)
            if (first == second && chineseCharRegex.containsMatchIn(first) && first.length >= 2) {
                cleaned = first + trailingPunctuation
            }
        }
        return cleaned.trim().takeIf { it.isNotEmpty() }
    }

    /** ASS/SSA parser honors the Events Format declaration while preserving commas in Text. */
    fun parseAss(src: String): List<SubLine> = parseAss(src) {}

    internal fun parseAss(src: String, checkActive: () -> Unit): List<SubLine> {
        val out = mutableListOf<SubLine>()
        var formatFields = defaultAssFormat
        for ((lineIndex, raw) in src.lineSequence().withIndex()) {
            if ((lineIndex and 0xff) == 0) checkActive()
            // Exporters are inconsistent about indentation and keyword casing.
            val entry = raw.trimStart()
            if (entry.startsWith("Format:", ignoreCase = true)) {
                val candidate = entry.substringAfter(':')
                    .split(',')
                    .map { it.trim().lowercase(Locale.ROOT) }
                // ASS/SSA requires Text to be the final event field; keeping that rule lets us
                // preserve every comma that belongs to the subtitle body.
                if (candidate.isNotEmpty() && candidate.lastOrNull() == "text" &&
                    candidate.count { it == "start" } == 1 &&
                    candidate.count { it == "end" } == 1 &&
                    candidate.distinct().size == candidate.size
                ) {
                    formatFields = candidate
                }
                continue
            }
            if (!entry.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = entry.substringAfter(':').trim()
            val p = body.split(',', ignoreCase = false, limit = formatFields.size)
            if (p.size != formatFields.size) continue

            val startIndex = formatFields.indexOf("start")
            val endIndex = formatFields.indexOf("end")
            val textIndex = formatFields.indexOf("text")
            if (startIndex < 0 || endIndex < 0 || textIndex != formatFields.lastIndex) continue
            val times = runCatching {
                parseAssTs(p[startIndex].trim()) to parseAssTs(p[endIndex].trim())
            }.getOrNull() ?: continue
            val (startMs, endMs) = times
            if (startMs < 0L || endMs <= startMs) continue

            val text = SubtitleTextDecoder.decode(
                p[textIndex].replace(assOverrideRegex, "")
                    .replace(assLinebreakRegex, "\n")
                    .replace(assHardSpaceRegex, " ")
            ).trim()
            if (text.isNotEmpty()) {
                ensureCueCapacity(out.size)
                out += SubLine(out.size, startMs, endMs, text)
            }
        }
        return normalizeTimeline(out, checkActive)
    }

    /** Keep every public parser safe for the binary-search playback timeline, not only parseAuto. */
    private fun normalizeTimeline(
        lines: List<SubLine>,
        checkActive: () -> Unit = {},
    ): List<SubLine> {
        checkActive()
        require(lines.size <= MAX_SUBTITLE_CUE_COUNT) {
            "字幕条数超过安全上限（${MAX_SUBTITLE_CUE_COUNT}），已停止处理以保护设备内存"
        }
        val cueOrder = compareBy<SubLine> { it.startMs }
            .thenBy { it.endMs }
            .thenBy { it.index }
        var comparisons = 0
        val sorted = lines.sortedWith { left, right ->
            if ((comparisons++ and 0x3ff) == 0) checkActive()
            cueOrder.compare(left, right)
        }
        checkActive()
        val merged = mergeDuplicate(sorted, checkActive)
        return merged.mapIndexed { index, line ->
            if ((index and 0x3ff) == 0) checkActive()
            line.copy(index = index)
        }
    }

    private fun mergeDuplicate(
        lines: List<SubLine>,
        checkActive: () -> Unit = {},
    ): List<SubLine> {
        // YouTube 自动字幕常见同一句重复多时间片：相同文本且首尾相接 → 合并
        val out = ArrayList<SubLine>(lines.size)
        var previousNormalizedText: String? = null
        for ((index, line) in lines.withIndex()) {
            if ((index and 0x3ff) == 0) checkActive()
            val last = out.lastOrNull()
            // Normalize each cue once. Re-normalizing the previous cue for every next cue
            // needlessly repeated regex/string allocations on subtitles with many short rows.
            val normalizedText = normalizeSubtitleWhitespace(line.text)
            val sameText = last != null && previousNormalizedText == normalizedText
            val gap = if (last != null) line.startMs - last.endMs else Long.MIN_VALUE
            val startDelta = if (last != null) line.startMs - last.startMs else Long.MAX_VALUE
            // Duplicate cues may overlap (same start time) rather than being exactly adjacent.
            val duplicateTiming = gap in 0..400 || (gap < 0 && startDelta in 0..250)
            if (sameText && duplicateTiming && last != null) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, line.endMs))
            } else {
                out += line
                previousNormalizedText = normalizedText
            }
        }
        return out
    }

    private fun stripTags(s: String): String {
        val decoded = SubtitleTextDecoder.decode(
            s.replace(ttmlLineBreakRegex, "\n").replace(htmlTagRegex, "")
        ).replace(inlineWhitespaceRegex, " ")
        // Normalize each line separately so explicit WebVTT <br> tags remain visible as line breaks.
        return decoded.split('\n')
            .joinToString("\n") { normalizeSubtitleWhitespace(it) }
            .trim()
    }

    private fun parseVttTs(value: String): Long? {
        val clean = value.trim().replace(",", ".")
        val bits = clean.split(":")
        if (bits.size !in 2..3) return null
        val hours = if (bits.size == 3) bits[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null else 0L
        val minutes = bits[bits.lastIndex - 1].toLongOrNull()
            ?.takeIf { it in 0L..59L } ?: return null
        val seconds = bits.last().toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 && it < 60.0 } ?: return null
        val millis = hours.toDouble() * 3_600_000.0 + minutes.toDouble() * 60_000.0 + seconds * 1_000.0
        if (!millis.isFinite() || millis >= Long.MAX_VALUE.toDouble()) return null
        return millis.toLong()
    }

    private fun parseAssTs(s: String): Long {
        val bits = s.trim().split(":")
        require(bits.size == 3) { "ASS 时间戳格式错误" }
        val h = bits[0].toLongOrNull()?.takeIf { it >= 0L }
            ?: throw IllegalArgumentException("ASS 小时字段无效")
        val m = bits[1].toLongOrNull()?.takeIf { it in 0L..59L }
            ?: throw IllegalArgumentException("ASS 分钟字段无效")
        val sec = bits[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it < 60.0 }
            ?: throw IllegalArgumentException("ASS 秒字段无效")
        val totalMs = h.toDouble() * 3_600_000.0 + m.toDouble() * 60_000.0 + sec * 1_000.0
        // Long.MAX_VALUE rounds up to 2^63 as a Double. Reject that boundary too,
        // otherwise an out-of-range timestamp can saturate to Long.MAX_VALUE on conversion.
        require(totalMs.isFinite() && totalMs < Long.MAX_VALUE.toDouble()) { "ASS 时间戳超出范围" }
        return totalMs.toLong()
    }
}
