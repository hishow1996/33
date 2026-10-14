package io.translatetube.local.subs

/** Android-free extraction of timed-text XML cue attributes, for deterministic timestamp tests. */
internal object TimedTextCueXmlParser {
    data class Cue(
        val startMs: Long,
        val endMs: Long,
        val rawText: String,
    )

    private val cueRegex = Regex(
        "<text\\b([^>]*)>(.*?)</text\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val attributeRegex = Regex("([A-Za-z_:][\\w:.-]*)\\s*=\\s*[\"']([^\"']*)[\"']")

    fun parse(source: String): List<Cue> {
        if (!source.contains("<transcript", ignoreCase = true)) return emptyList()
        val out = mutableListOf<Cue>()
        cueRegex.findAll(source).forEach { match ->
            val attrs = attributeRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase(java.util.Locale.ROOT) to it.groupValues[2] }
            val timing = TimedTextCueTimingPolicy.parse(attrs["start"], attrs["dur"])
                ?: return@forEach
            val body = match.groupValues[2].trim()
            if (body.isNotEmpty()) {
                out += Cue(timing.startMs, timing.endMs, body)
            }
        }
        return out
    }
}
