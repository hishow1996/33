package io.translatetube.local.subs

/** 一条字幕行：用于 UI 显示 / 翻译 / 配音对轴 */
data class SubLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,          // 原文
    var zh: String? = null,    // 中文译文
    val speakerId: Int = -1,   // -1=使用当前默认音色；非负数=Kokoro 音色 ID
)

object SubtitleParsers {

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
            if (pendingStart >= 0 && txt.isNotEmpty()) {
                out += SubLine(idx++, pendingStart, pendingEnd, txt)
            }
            buf.clear(); pendingStart = -1; pendingEnd = -1
        }
        for (raw in src.lines()) {
            val line = raw.trim()
            val m = Regex("""(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3}""").find(line)
            if (m != null) {
                flush()
                val parts = m.value.split("-->")
                pendingStart = parseTs(parts[0].trim())
                pendingEnd = parseTs(parts[1].trim())
            } else if (line.isEmpty()) {
                // 行结束标记交给下一条 cue 前统一 flush
            } else if (pendingStart >= 0 && !line.startsWith("NOTE") && !line.startsWith("WEBVTT") &&
                !line.startsWith("Kind:") && !line.startsWith("Language:") && !Regex("^[0-9]+$").matches(line)
            ) {
                buf.appendLine(stripTags(line))
            }
        }
        flush()
        return mergeDuplicate(out)
    }

    /** SRT（1 行格式 00:00:01,000 --> 00:00:04,000） */
    fun parseSrt(src: String): List<SubLine> = parseVtt(src)

    /** YouTube timedtext XML（<transcript><text start="1.2" dur="3.4">…</text>）兜底 */
    fun parseTimedTextXml(src: String): List<SubLine> {
        if (!src.contains("<transcript")) return emptyList()
        val out = mutableListOf<SubLine>()
        val re = Regex("""<text[^>]*start="([\d.]+)"[^>]*(?:dur="([\d.]+)")?[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        re.findAll(src).forEachIndexed { i, m ->
            val start = ((m.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
            val dur = ((m.groupValues[2].toDoubleOrNull() ?: 3.0) * 1000.0).toLong()
            val text = android.text.Html.fromHtml(m.groupValues[3], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isNotEmpty()) out += SubLine(i, start, start + dur, text)
        }
        return out
    }

    /** YouTube/NewPipe TTML：从 p 标签提取 begin/end/dur。 */
    fun parseTtml(src: String): List<SubLine> {
        if (!src.contains("<tt", ignoreCase = true) && !src.contains("<p", ignoreCase = true)) {
            return emptyList()
        }
        val out = mutableListOf<SubLine>()
        val cueRegex = Regex(
            "<p\\b([^>]*)>(.*?)</p\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val attributeRegex = Regex("([A-Za-z_:][\\w:.-]*)\\s*=\\s*[\"']([^\"']*)[\"']")
        cueRegex.findAll(src).forEach { match ->
            val attrs = attributeRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val start = (attrs["begin"] ?: attrs["start"])?.let { parseTtmlTime(it) } ?: return@forEach
            val end = attrs["end"]?.let { parseTtmlTime(it) }
                ?: attrs["dur"]?.let { parseTtmlTime(it)?.let(start::plus) }
                ?: return@forEach
            if (end <= start) return@forEach
            val body = match.groupValues[2].replace(
                Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"
            )
            val text = android.text.Html.fromHtml(
                body,
                android.text.Html.FROM_HTML_MODE_LEGACY,
            ).toString().replace('\u00a0', ' ').trim()
            if (text.isNotEmpty()) out += SubLine(out.size, start, end, text)
        }
        return mergeDuplicate(out)
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
            t.startsWith("<?xml") || t.startsWith("<tt", ignoreCase = true) ||
                Regex("<tt[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                parseTtml(t).ifEmpty { parseTimedTextXml(t) }
            else -> parseSrt(t)
        }
        return parsed.sortedBy { it.startMs }.mapIndexed { i, l -> l.copy(index = i) }
    }

    /** ASS/SSA（Dialogue: 0:00:01.00,0:00:04.00,...） */
    fun parseAss(src: String): List<SubLine> {
        val out = mutableListOf<SubLine>()
        for (raw in src.lines()) {
            if (!raw.startsWith("Dialogue:")) continue
            val body = raw.removePrefix("Dialogue:").trim()
            val p = body.split(",").toMutableList()
            if (p.size < 10) continue
            val start = parseAssTs(p[1].trim())
            val end = parseAssTs(p[2].trim())
            val text = p.drop(9).joinToString(",").replace(Regex("\\{[^}]*\\}"), "").replace(Regex("\\\\N|\\\\n"), "\n").trim()
            if (text.isNotEmpty()) out += SubLine(out.size, start, end, text)
        }
        return mergeDuplicate(out)
    }

    private fun mergeDuplicate(lines: List<SubLine>): List<SubLine> {
        // YouTube 自动字幕常见同一句重复多时间片：相同文本且首尾相接 → 合并
        val out = mutableListOf<SubLine>()
        for (l in lines) {
            val last = out.lastOrNull()
            if (last != null && last.text == l.text && (l.startMs - last.endMs) in 0..400) {
                out[out.size - 1] = last.copy(endMs = l.endMs)
            } else out += l
        }
        return out
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

    private fun parseTs(s: String): Long {
        val clean = s.replace(",", ".")
        val bits = clean.split(":")
        val h = if (bits.size == 3) bits[0].toLong() else 0
        val m = bits[bits.size - 2].toLong()
        val sec = bits.last().toDouble()
        return ((h * 3600 + m * 60 + sec) * 1000).toLong()
    }

    private fun parseAssTs(s: String): Long {
        val bits = s.split(":")
        val h = bits[0].toLong()
        val m = bits[1].toLong()
        val sec = bits[2].toDouble()
        return ((h * 3600 + m * 60 + sec) * 1000).toLong()
    }
}
