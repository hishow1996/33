package io.translatetube.local.subs

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

    // Hot-path patterns are shared so long subtitle files do not recompile them per cue.
    private val webVttTimestampRegex = Regex("""(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3}""")
    private val cueIndexRegex = Regex("^[0-9]+$")
    private val ttmlCueRegex = Regex(
        "<p\\b([^>]*)>(.*?)</p\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ttmlAttributeRegex = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")
    private val ttmlLineBreakRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ttmlRootRegex = Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE)
    private val translationProgressRegex = Regex("^(正在)?翻译中\\d*/\\d*行?$")
    private val translationChunkRegex = Regex("(?<=[。！？!?；;，,])")
    private val whitespaceRegex = Regex("\\s+")
    private val assOverrideRegex = Regex("""\{[^}]*\}""")
    private val assLinebreakRegex = Regex("""\\N|\\n""")
    private val htmlTagRegex = Regex("<[^>]+>")



    fun fmtTime(ms: Long): String {
        val t = ms / 1000
        return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    /** WebVTT → 行列表（YouTube timedtext 返回 vtt） */
    fun parseVtt(src: String): List<SubLine> {
        val out = mutableListOf<SubLine>()
        var idx = 0
        var pendingStart = -1L
        var pendingEnd = -1L
        val buf = StringBuilder()
        fun flush() {
            val txt = buf.toString().trim()
            if (pendingStart >= 0 && pendingEnd > pendingStart && txt.isNotEmpty()) {
                out += SubLine(idx++, pendingStart, pendingEnd, txt)
            }
            buf.clear(); pendingStart = -1; pendingEnd = -1
        }
        for (raw in src.lines()) {
            val line = raw.trim()
            val m = webVttTimestampRegex.find(line)
            if (m != null) {
                flush()
                val parts = m.value.split("-->")
                pendingStart = parseTs(parts[0].trim())
                pendingEnd = parseTs(parts[1].trim())
            } else if (line.isEmpty()) {
                // 行结束标记交给下一条 cue 前统一 flush
            } else if (pendingStart >= 0 && !line.startsWith("NOTE") && !line.startsWith("WEBVTT") &&
                !line.startsWith("Kind:") && !line.startsWith("Language:") && !cueIndexRegex.matches(line)
            ) {
                buf.appendLine(stripTags(line))
            }
        }
        flush()
        return normalizeTimeline(out)
    }

    /** SRT（1 行格式 00:00:01,000 --> 00:00:04,000） */
    fun parseSrt(src: String): List<SubLine> = parseVtt(src)

    /** 将字幕行导出为标准 WebVTT，统一文件扩展名并保留时间轴。 */
    fun toVtt(lines: List<SubLine>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        lines.forEach { line ->
            appendLine(formatVttTime(line.startMs) + " --> " + formatVttTime(line.endMs))
            line.text.lines().forEach { appendLine(it) }
            appendLine()
        }
    }

    private fun formatVttTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    /** YouTube timedtext XML（<transcript><text start="1.2" dur="3.4">…</text>）兜底 */
    fun parseTimedTextXml(src: String): List<SubLine> {
        if (!src.contains("<transcript")) return emptyList()
        val out = mutableListOf<SubLine>()
        val re = Regex("""<text\b([^>]*)>(.*?)</text\s*>""", RegexOption.DOT_MATCHES_ALL)
        re.findAll(src).forEach { match ->
            val timing = parseTimedTextTiming(match.groupValues[1]) ?: return@forEach
            val rawText = match.groupValues[2]
            // Plain text needs no Android Html conversion; escaped entities and markup still do.
            val decodedText = if (rawText.contains('&') || rawText.contains('<')) {
                android.text.Html.fromHtml(rawText, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                rawText
            }
            val text = decodedText.trim()
            if (text.isNotEmpty()) out += SubLine(out.size, timing.first, timing.second, text)
        }
        return normalizeTimeline(out)
    }

    private val timedTextAttributeRegex = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")

    /** Parse start/dur by attribute name, regardless of XML attribute order or quote style. */
    internal fun parseTimedTextTiming(attributes: String): Pair<Long, Long>? {
        val attrs = timedTextAttributeRegex.findAll(attributes)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val startSeconds = attrs["start"]?.toDoubleOrNull() ?: return null
        val durationSeconds = attrs["dur"]?.toDoubleOrNull() ?: 3.0
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
    fun parseTtml(src: String): List<SubLine> {
        if (!src.contains("<tt", ignoreCase = true) && !src.contains("<p", ignoreCase = true)) {
            return emptyList()
        }
        val out = mutableListOf<SubLine>()
        val cueRegex = ttmlCueRegex
            "<p\\b([^>]*)>(.*?)</p\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val attributeRegex = ttmlAttributeRegex
        cueRegex.findAll(src).forEach { match ->
            val attrs = attributeRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val start = (attrs["begin"] ?: attrs["start"])?.let { parseTtmlTime(it) }
                ?.takeIf { it >= 0L } ?: return@forEach
            val explicitEnd = attrs["end"]?.let { parseTtmlTime(it) }
            val end = explicitEnd ?: attrs["dur"]?.let { durationText ->
                val duration = parseTtmlTime(durationText) ?: return@let null
                if (duration <= 0L || start > Long.MAX_VALUE - duration) null else start + duration
            } ?: return@forEach
            if (end <= start) return@forEach
            val body = match.groupValues[2].replace(ttmlLineBreakRegex, "\n")
                Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"
            )
            val text = android.text.Html.fromHtml(
                body,
                android.text.Html.FROM_HTML_MODE_LEGACY,
            ).toString().replace('\u00a0', ' ').trim()
            if (text.isNotEmpty()) out += SubLine(out.size, start, end, text)
        }
        return normalizeTimeline(out)
    }

    private fun parseTtmlTime(value: String): Long? {
        val time = value.trim()
        return when {
            time.endsWith("ms", ignoreCase = true) ->
                time.dropLast(2).trim().toDoubleOrNull()?.toLong()
            time.endsWith("s", ignoreCase = true) ->
                ((time.dropLast(1).trim().toDoubleOrNull() ?: return null) * 1000.0).toLong()
            time.contains(":") -> runCatching { parseTs(time) }.getOrNull()
            else -> ((time.toDoubleOrNull() ?: return null) * 1000.0).toLong()
        }
    }

    /** 自动识别格式解析（vtt/srt/xml），输出保证按 startMs 单调有序（lineAt 二分前提） */
    fun parseAuto(src: String): List<SubLine> {
        val t = src.removePrefix("\uFEFF").trimStart()
        val parsed = when {
            t.startsWith("WEBVTT") || t.contains("-->") -> parseVtt(t)
            t.startsWith("<transcript", ignoreCase = true) -> parseTimedTextXml(t)
            t.contains("Dialogue:") -> parseAss(t)
            t.startsWith("<?xml") || t.startsWith("<tt", ignoreCase = true) ||
                ttmlRootRegex.containsMatchIn(t) ->
                parseTtml(t).ifEmpty { parseTimedTextXml(t) }
            else -> parseSrt(t)
        }
        return normalizeTimeline(parsed)
    }

    /**
     * Recognize UI/progress placeholders that older builds could accidentally persist as translations.
     * Treat them as missing output, never as valid subtitle text.
     */
    fun isPlaceholderTranslation(translated: String?): Boolean {
        val normalized = translated
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[\\s…。.！!·:：]+"), "")
            ?: return false
        if (normalized in setOf(
                "正在翻译", "正在翻译中", "翻译中", "译文暂不可用", "暂无译文",
                "translating", "translationinprogress", "loadingtranslation",
            )) return true
        return translationProgressRegex.matches(normalized)
    }

    /**
     * Clean common duplicated translator output such as "你好，你好" or
     * "这是结果。这是结果。" without touching ordinary non-repeated clauses.
     * Also used when reading the existing translation cache so old bad results do not remain visible.
     */
    fun cleanTranslatedText(translated: String?): String? {
        val value = translated
            ?.replace('\u00a0', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (isPlaceholderTranslation(value)) return null

        val chunks = translationChunkRegex.split(value)
        val collapsed = ArrayList<String>(chunks.size)
        var previousKey = ""
        var removedDuplicate = false
        for (chunk in chunks) {
            val key = chunk.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]"), "")
            val hasChinese = Regex("[\\u4E00-\\u9FFF]").containsMatchIn(key)
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
        val compact = cleaned.dropLast(trailingPunctuation.length).replace(Regex("\\s+"), "")
        if (compact.length >= 4 && compact.length % 2 == 0) {
            val half = compact.length / 2
            val first = compact.substring(0, half)
            val second = compact.substring(half)
            if (first == second && Regex("[\\u4E00-\\u9FFF]").containsMatchIn(first) && first.length >= 2) {
                cleaned = first + trailingPunctuation
            }
        }
        return cleaned.trim().takeIf { it.isNotEmpty() }
    }

    /** ASS/SSA（Dialogue: 0:00:01.00,0:00:04.00,...） */
    fun parseAss(src: String): List<SubLine> {
        val out = mutableListOf<SubLine>()
        for (raw in src.lines()) {
            if (!raw.startsWith("Dialogue:")) continue
            val body = raw.removePrefix("Dialogue:").trim()
            val p = body.split(",").toMutableList()
            if (p.size < 10) continue
            val times = runCatching {
                parseAssTs(p[1].trim()) to parseAssTs(p[2].trim())
            }.getOrNull() ?: continue
            val (start, end) = times
            if (start < 0L || end <= start) continue
            val text = p.drop(9).joinToString(",").replace(assOverrideRegex, "").replace(assLinebreakRegex, "\n").trim()
            if (text.isNotEmpty()) out += SubLine(out.size, start, end, text)
        }
        return normalizeTimeline(out)
    }

    /** Keep every public parser safe for the binary-search playback timeline, not only parseAuto. */
    private fun normalizeTimeline(lines: List<SubLine>): List<SubLine> =
        mergeDuplicate(lines.sortedBy { it.startMs }).mapIndexed { index, line ->
            line.copy(index = index)
        }

    private fun mergeDuplicate(lines: List<SubLine>): List<SubLine> {
        // YouTube 自动字幕常见同一句重复多时间片：相同文本且首尾相接 → 合并
        val out = mutableListOf<SubLine>()
        for (l in lines) {
            val last = out.lastOrNull()
            val sameText = last != null &&
                last.text.replace(whitespaceRegex, " ").trim() ==
                l.text.replace(whitespaceRegex, " ").trim()
            val gap = if (last != null) l.startMs - last.endMs else Long.MIN_VALUE
            val startDelta = if (last != null) l.startMs - last.startMs else Long.MAX_VALUE
            // Duplicate cues may overlap (same start time) rather than being exactly adjacent.
            val duplicateTiming = gap in 0..400 || (gap < 0 && startDelta in 0..250)
            if (sameText && duplicateTiming && last != null) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, l.endMs))
            } else out += l
        }
        return out
    }

    private fun stripTags(s: String) = s.replace(htmlTagRegex, "").replace("&nbsp;", " ").trim()

    private fun parseTs(s: String): Long {
        val clean = s.replace(",", ".")
        val bits = clean.split(":")
        val h = if (bits.size == 3) bits[0].toLong() else 0
        val m = bits[bits.size - 2].toLong()
        val sec = bits.last().toDouble()
        return ((h * 3600 + m * 60 + sec) * 1000).toLong()
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
        require(totalMs.isFinite() && totalMs <= Long.MAX_VALUE.toDouble()) { "ASS 时间戳超出范围" }
        return totalMs.toLong()
    }
}
