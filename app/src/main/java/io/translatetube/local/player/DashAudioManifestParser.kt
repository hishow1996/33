package io.translatetube.local.player

import java.io.StringReader
import java.net.URI
import java.time.Duration
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Conservative MPEG-DASH VOD parser for publicly accessible audio representations.
 *
 * Supports a single static Period with SegmentTemplate (duration or SegmentTimeline), SegmentList,
 * and an explicit direct audio BaseURL. DRM, SegmentBase/byte ranges, dynamic/live MPDs, negative
 * timeline repeats and multi-period audio are rejected rather than concatenated incorrectly.
 */
internal object DashAudioManifestParser {
    const val MAX_MANIFEST_CHARS = 2 * 1024 * 1024
    const val MAX_SEGMENTS = 20_000
    const val MAX_DURATION_MS = 12L * 60L * 60L * 1_000L

    data class Segment(val index: Int, val url: String, val durationMs: Long)

    data class AudioManifest(
        val manifestUrl: String,
        val initializationUrl: String?,
        val segments: List<Segment>,
        val directAudioUrl: String?,
        val totalDurationMs: Long,
    )

    fun parse(manifestUrl: String, text: String): AudioManifest {
        require(text.length <= MAX_MANIFEST_CHARS) { "DASH 清单超过大小限制" }
        validateHttpUrl(manifestUrl)
        val root = parseXml(text)
        if (!root.tagName.substringAfter(':').equals("MPD", ignoreCase = true)) {
            throw IllegalArgumentException("响应不是有效的 MPEG-DASH MPD 清单")
        }
        if (root.getAttribute("type").equals("dynamic", ignoreCase = true)) {
            throw UnsupportedOperationException("当前版本只处理已完成的 DASH 点播清单，直播清单暂不支持")
        }
        if (hasDescendantNamed(root, "ContentProtection")) {
            throw UnsupportedOperationException("该 DASH 清单声明了加密或 DRM 保护，不能提取其音轨")
        }
        if (hasDescendantNamed(root, "SegmentBase")) {
            throw UnsupportedOperationException("该 DASH 清单使用 SegmentBase/字节范围，当前版本暂不支持安全重组")
        }

        val periods = directChildren(root, "Period")
        if (periods.isEmpty()) throw IllegalArgumentException("DASH 清单没有 Period")
        if (periods.size != 1) {
            throw UnsupportedOperationException("该 DASH 清单包含多个 Period，当前版本不自动拼接不连续音轨")
        }

        val rootDurationMs = durationMs(root.getAttribute("mediaPresentationDuration"))
        val period = periods.single()
        val periodDurationMs = durationMs(period.getAttribute("duration")).takeIf { it > 0L }
            ?: rootDurationMs
        if (periodDurationMs > MAX_DURATION_MS) {
            throw IllegalArgumentException("DASH 音频超过 12 小时限制")
        }

        var rootBase = manifestUrl
        childText(root, "BaseURL")?.let { rootBase = resolveHttpUrl(rootBase, it) }
        var periodBase = rootBase
        childText(period, "BaseURL")?.let { periodBase = resolveHttpUrl(periodBase, it) }

        val adaptations = directChildren(period, "AdaptationSet")
        val audioAdaptations = adaptations.filter { isAudioAdaptation(it) }
        if (audioAdaptations.isEmpty()) throw IllegalArgumentException("DASH 清单中没有声明可用的音频轨")

        val adaptation = audioAdaptations.sortedWith(
            compareByDescending<Element> { isMainRole(it) }
                .thenByDescending { it.getAttribute("lang").equals("en", ignoreCase = true) }
                .thenBy { it.getAttribute("lang") }
        ).first()
        var adaptationBase = periodBase
        childText(adaptation, "BaseURL")?.let { adaptationBase = resolveHttpUrl(adaptationBase, it) }

        val representations = directChildren(adaptation, "Representation")
        if (representations.isEmpty()) {
            return parseAdaptationLevelSegments(
                manifestUrl, text, adaptation, adaptationBase, periodDurationMs
            )
        }

        val representation = chooseRepresentation(representations)
        val representationId = representation.getAttribute("id").ifBlank { "audio" }
        val bandwidth = representation.getAttribute("bandwidth").toLongOrNull() ?: 0L
        var representationBase = adaptationBase
        childText(representation, "BaseURL")?.let { representationBase = resolveHttpUrl(representationBase, it) }

        val representationList = child(representation, "SegmentList")
        val adaptationList = child(adaptation, "SegmentList")
        val segmentList = representationList ?: adaptationList
        if (segmentList != null) {
            val parsed = parseSegmentList(representationBase, segmentList, periodDurationMs)
            return AudioManifest(manifestUrl, parsed.first, parsed.second, null, parsed.third)
        }

        val representationTemplate = child(representation, "SegmentTemplate")
        val adaptationTemplate = child(adaptation, "SegmentTemplate")
        val template = parseTemplateAttributes(adaptationTemplate).apply {
            putAll(parseTemplateAttributes(representationTemplate))
        }
        if (template.isNotEmpty()) {
            val timescale = template["timescale"]?.toLongOrNull() ?: 1L
            if (timescale <= 0L) throw IllegalArgumentException("DASH timescale 无效")
            val startNumber = template["startNumber"]?.toLongOrNull() ?: 1L
            if (startNumber < 0L) throw IllegalArgumentException("DASH startNumber 无效")
            val mediaTemplate = template["media"]?.takeIf { it.isNotBlank() }
                ?: throw UnsupportedOperationException("DASH SegmentTemplate 缺少 media 地址")
            val initTemplate = template["initialization"]?.takeIf { it.isNotBlank() }
            val initUrl = initTemplate?.let {
                resolveHttpUrl(representationBase, expandTemplate(it, representationId, bandwidth, startNumber, 0L))
            }
            val timeline = child(representationTemplate, "SegmentTimeline")
                ?: child(adaptationTemplate, "SegmentTimeline")
            val segments = if (timeline != null) {
                parseTimeline(
                    representationBase, mediaTemplate, representationId, bandwidth,
                    startNumber, timescale, timeline,
                )
            } else {
                val duration = template["duration"]?.toLongOrNull()
                    ?: throw UnsupportedOperationException("DASH SegmentTemplate 既没有 duration 也没有 SegmentTimeline")
                if (duration <= 0L || periodDurationMs <= 0L) {
                    throw UnsupportedOperationException("DASH 分段时长或 Period 时长未知，无法安全生成分段列表")
                }
                val segmentDurationMs = durationUnitsToMillis(duration, timescale)
                val count = ceilDiv(periodDurationMs, segmentDurationMs)
                if (count !in 1L..MAX_SEGMENTS.toLong()) {
                    throw IllegalArgumentException("DASH 分段数量超过支持限制")
                }
                if (count > 1L && duration > Long.MAX_VALUE / (count - 1L)) {
                    throw IllegalArgumentException("DASH 分段时间戳超出可处理范围")
                }
                (0 until count.toInt()).map { index ->
                    val number = startNumber + index
                    val time = index.toLong() * duration
                    val segmentStartMs = index.toLong() * segmentDurationMs
                    val remainingMs = (periodDurationMs - segmentStartMs).coerceAtLeast(1L)
                    val segmentMs = minOf(segmentDurationMs, remainingMs)
                    val url = expandTemplate(mediaTemplate, representationId, bandwidth, number, time)
                    Segment(index, resolveHttpUrl(representationBase, url), segmentMs)
                }
            }
            if (segments.isEmpty()) throw IllegalArgumentException("DASH 音轨没有可用分段")
            val totalMs = segments.sumOf { it.durationMs }
            if (totalMs > MAX_DURATION_MS) throw IllegalArgumentException("DASH 音频超过 12 小时限制")
            return AudioManifest(manifestUrl, initUrl, segments, null, totalMs)
        }

        val directUrl = representationBase
        if (looksLikeDirectAudioUrl(directUrl) || representation.getAttribute("mimeType")
                .startsWith("audio/", ignoreCase = true)
        ) {
            return AudioManifest(
                manifestUrl = manifestUrl,
                initializationUrl = null,
                segments = emptyList(),
                directAudioUrl = directUrl,
                totalDurationMs = periodDurationMs,
            )
        }
        throw UnsupportedOperationException(
            "该 DASH 音轨使用未支持的分段模式（需要 SegmentTemplate 或 SegmentList）"
        )
    }

    private fun parseAdaptationLevelSegments(
        manifestUrl: String,
        text: String,
        adaptation: Element,
        adaptationBase: String,
        periodDurationMs: Long,
    ): AudioManifest {
        val segmentList = child(adaptation, "SegmentList")
        if (segmentList != null) {
            val parsed = parseSegmentList(adaptationBase, segmentList, periodDurationMs)
            return AudioManifest(manifestUrl, parsed.first, parsed.second, null, parsed.third)
        }
        throw UnsupportedOperationException("DASH 音频 AdaptationSet 没有 Representation 或可用分段信息")
    }

    private fun parseSegmentList(
        baseUrl: String,
        list: Element,
        periodDurationMs: Long,
    ): Triple<String?, List<Segment>, Long> {
        if (directChildren(list, "SegmentURL").isEmpty()) {
            throw IllegalArgumentException("DASH SegmentList 没有 SegmentURL")
        }
        if (directChildren(list, "SegmentURL").any { it.hasAttribute("mediaRange") }) {
            throw UnsupportedOperationException("DASH SegmentList 使用字节范围分片，当前版本暂不支持")
        }
        val initElement = child(list, "Initialization")
        if (initElement?.hasAttribute("range") == true) {
            throw UnsupportedOperationException("DASH 初始化片段使用字节范围，当前版本暂不支持")
        }
        val initUrl = initElement?.getAttribute("sourceURL")?.takeIf { it.isNotBlank() }
            ?.let { resolveHttpUrl(baseUrl, it) }
        val urls = directChildren(list, "SegmentURL").map { element ->
            val value = element.getAttribute("media").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("DASH SegmentURL 缺少 media")
            resolveHttpUrl(baseUrl, value)
        }
        if (urls.size > MAX_SEGMENTS) throw IllegalArgumentException("DASH 分段数量超过支持限制")
        val duration = if (urls.isNotEmpty() && periodDurationMs > 0L) {
            periodDurationMs / urls.size
        } else 0L
        return Triple(
            initUrl,
            urls.mapIndexed { index, url -> Segment(index, url, duration) },
            periodDurationMs,
        )
    }

    private fun parseTimeline(
        baseUrl: String,
        mediaTemplate: String,
        representationId: String,
        bandwidth: Long,
        startNumber: Long,
        timescale: Long,
        timeline: Element,
    ): List<Segment> {
        val entries = directChildren(timeline, "S")
        if (entries.isEmpty()) throw IllegalArgumentException("DASH SegmentTimeline 为空")
        val result = ArrayList<Segment>()
        var nextTime = 0L
        var number = startNumber
        var totalDurationMs = 0L
        for (entry in entries) {
            val duration = entry.getAttribute("d").toLongOrNull()
                ?: throw IllegalArgumentException("DASH SegmentTimeline 缺少 d")
            if (duration <= 0L) throw IllegalArgumentException("DASH 分段时长必须大于 0")
            if (entry.hasAttribute("t")) {
                nextTime = entry.getAttribute("t").toLongOrNull()
                    ?: throw IllegalArgumentException("DASH SegmentTimeline t 无效")
                if (nextTime < 0L) throw IllegalArgumentException("DASH SegmentTimeline t 不能为负数")
            }
            val repeatCount = entry.getAttribute("r").takeIf { it.isNotBlank() }?.toIntOrNull()
                ?: if (entry.getAttribute("r").isBlank()) 0 else {
                    throw IllegalArgumentException("DASH SegmentTimeline r 无效")
                }
            if (repeatCount < 0) {
                throw UnsupportedOperationException("DASH SegmentTimeline 使用负 r 重复值，当前版本暂不支持")
            }
            val copies = repeatCount.toLong() + 1L
            if (result.size.toLong() + copies > MAX_SEGMENTS) {
                throw IllegalArgumentException("DASH 分段数量超过支持限制")
            }
            if (duration > Long.MAX_VALUE / copies ||
                nextTime > Long.MAX_VALUE - duration * copies
            ) {
                throw IllegalArgumentException("DASH 分段时间戳超出可处理范围")
            }
            val segmentDurationMs = durationUnitsToMillis(duration, timescale)
            if (segmentDurationMs > MAX_DURATION_MS ||
                totalDurationMs > MAX_DURATION_MS - segmentDurationMs * copies
            ) {
                throw IllegalArgumentException("DASH 音频超过 12 小时限制")
            }
            repeat(copies.toInt()) {
                val expanded = expandTemplate(mediaTemplate, representationId, bandwidth, number, nextTime)
                result += Segment(
                    index = result.size,
                    url = resolveHttpUrl(baseUrl, expanded),
                    durationMs = segmentDurationMs,
                )
                totalDurationMs += segmentDurationMs
                nextTime += duration
                number++
            }
        }
        return result
    }

    private fun parseTemplateAttributes(element: Element?): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (element == null) return result
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attr = attributes.item(index)
            result[attr.nodeName] = attr.nodeValue
        }
        return result
    }

    private fun expandTemplate(
        input: String,
        representationId: String,
        bandwidth: Long,
        number: Long,
        time: Long,
    ): String {
        val escapeMarker = "\u0001"
        val dollar = '$'.toString()
        val protected = input.replace(dollar + dollar, escapeMarker)
        val pattern = Regex("""\$(RepresentationID|Bandwidth|Number|Time)(?:%0(\d+)d)?\$""")
        val expanded = pattern.replace(protected) { match ->
            val value = when (match.groupValues[1]) {
                "RepresentationID" -> representationId
                "Bandwidth" -> bandwidth.toString()
                "Number" -> number.toString()
                else -> time.toString()
            }
            val width = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (width in 1..12 && value.length < width) value.padStart(width, '0') else value
        }
        return expanded.replace(escapeMarker, dollar)
    }

    private fun isAudioAdaptation(element: Element): Boolean {
        if (hasAudioMimeOrCodec(element)) return true
        // Some valid MPDs omit AdaptationSet's mimeType/contentType and declare it only on each
        // Representation, so inspect children before incorrectly reporting "no audio track".
        return directChildren(element, "Representation").any(::hasAudioMimeOrCodec)
    }

    private fun hasAudioMimeOrCodec(element: Element): Boolean {
        val mime = element.getAttribute("mimeType")
        val contentType = element.getAttribute("contentType")
        if (contentType.equals("audio", ignoreCase = true) ||
            mime.startsWith("audio/", ignoreCase = true)
        ) return true
        val codecs = element.getAttribute("codecs").lowercase(Locale.ROOT)
        val audioCodecs = listOf("mp4a", "ac-3", "ec-3", "opus", "vorbis", "flac", "alac")
        val videoCodecs = listOf("avc", "hev", "hvc", "vp9", "vp09", "av01", "theora")
        return audioCodecs.any(codecs::contains) && videoCodecs.none(codecs::contains)
    }

    private fun hasDescendantNamed(root: Element, expected: String): Boolean {
        val nodes = root.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.nodeName.substringAfter(':')
            if (name.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun chooseRepresentation(representations: List<Element>): Element {
        val entries = representations.mapNotNull { element ->
            val bandwidth = element.getAttribute("bandwidth").toLongOrNull()
            if (bandwidth == null || bandwidth <= 0L) null else element to bandwidth
        }
        if (entries.isEmpty()) return representations.first()
        val withinPreferredLimit = entries.filter { it.second <= PREFERRED_MAX_AUDIO_BANDWIDTH }
        return (withinPreferredLimit.maxByOrNull { it.second } ?: entries.minByOrNull { it.second })!!.first
    }

    private fun isMainRole(adaptation: Element): Boolean =
        directChildren(adaptation, "Role").any {
            it.getAttribute("value").equals("main", ignoreCase = true) ||
                it.getAttribute("value").equals("default", ignoreCase = true)
        }

    private fun durationUnitsToMillis(units: Long, timescale: Long): Long {
        if (units <= 0L || timescale <= 0L) {
            throw IllegalArgumentException("DASH 分段时长或 timescale 无效")
        }
        val millis = units.toDouble() * 1_000.0 / timescale.toDouble()
        if (!millis.isFinite() || millis < 1.0 || millis > MAX_DURATION_MS.toDouble()) {
            throw IllegalArgumentException("DASH 分段时长超出支持范围")
        }
        return millis.toLong().coerceAtLeast(1L)
    }

    private fun durationMs(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            val parsed = Duration.parse(value)
            parsed.seconds * 1_000L + parsed.nano / 1_000_000L
        }.getOrDefault(0L)
    }

    private fun parseXml(text: String): Element {
        require(text.isNotBlank()) { "DASH 清单为空" }
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
        return document.documentElement
    }

    private fun child(parent: Element?, tag: String): Element? =
        if (parent == null) null else directChildren(parent, tag).firstOrNull()

    private fun childText(parent: Element, tag: String): String? =
        child(parent, tag)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun directChildren(parent: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node is Element && node.tagName.substringAfter(':').equals(tag, ignoreCase = true)) {
                result += node
            }
        }
        return result
    }

    private fun resolveHttpUrl(baseUrl: String, path: String): String {
        val resolved = runCatching { URI(baseUrl).resolve(URI(path)).normalize() }
            .getOrElse { throw IllegalArgumentException("DASH 媒体 URL 无效", it) }
        validateHttpUrl(resolved.toASCIIString())
        return resolved.toASCIIString()
    }

    private fun validateHttpUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null ||
            (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) throw IllegalArgumentException("DASH 只允许可访问的 HTTP/HTTPS 媒体地址")
    }

    private fun looksLikeDirectAudioUrl(value: String): Boolean {
        val path = runCatching { URI(value).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return listOf(".m4a", ".mp3", ".aac", ".ogg", ".opus", ".flac", ".wav").any(path::endsWith)
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long {
        if (numerator <= 0L || denominator <= 0L) return 0L
        return numerator / denominator + if (numerator % denominator == 0L) 0L else 1L
    }

    private const val PREFERRED_MAX_AUDIO_BANDWIDTH = 320_000L
}
.toString()
        val protected = input.replace(dollar + dollar, escapeMarker)
        val pattern = Regex("""\$(RepresentationID|Bandwidth|Number|Time)(?:%0(\d+)d)?\$""")
        val expanded = pattern.replace(protected) { match ->
            val value = when (match.groupValues[1]) {
                "RepresentationID" -> representationId
                "Bandwidth" -> bandwidth.toString()
                "Number" -> number.toString()
                else -> time.toString()
            }
            val width = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (width in 1..12 && value.length < width) value.padStart(width, '0') else value
        }
        return expanded.replace(escapeMarker, '$'.toString())
    }

    private fun isAudioAdaptation(element: Element): Boolean {
        val mime = element.getAttribute("mimeType")
        val contentType = element.getAttribute("contentType")
        if (contentType.equals("audio", ignoreCase = true) ||
            mime.startsWith("audio/", ignoreCase = true)
        ) return true
        val codecs = element.getAttribute("codecs").lowercase(Locale.ROOT)
        val hasAudio = listOf("mp4a", "ac-3", "ec-3", "opus", "vorbis", "flac", "alac")
            .any(codecs::contains)
        val hasVideo = listOf("avc", "hev", "hvc", "vp9", "vp09", "av01", "theora")
            .any(codecs::contains)
        return hasAudio && !hasVideo
    }

    private fun chooseRepresentation(representations: List<Element>): Element {
        val entries = representations.mapNotNull { element ->
            val bandwidth = element.getAttribute("bandwidth").toLongOrNull()
            if (bandwidth == null || bandwidth <= 0L) null else element to bandwidth
        }
        if (entries.isEmpty()) return representations.first()
        val withinPreferredLimit = entries.filter { it.second <= PREFERRED_MAX_AUDIO_BANDWIDTH }
        return (withinPreferredLimit.maxByOrNull { it.second } ?: entries.minByOrNull { it.second })!!.first
    }

    private fun isMainRole(adaptation: Element): Boolean =
        directChildren(adaptation, "Role").any {
            it.getAttribute("value").equals("main", ignoreCase = true) ||
                it.getAttribute("value").equals("default", ignoreCase = true)
        }

    private fun durationMs(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            val parsed = Duration.parse(value)
            parsed.seconds * 1_000L + parsed.nano / 1_000_000L
        }.getOrDefault(0L)
    }

    private fun parseXml(text: String): Element {
        require(text.isNotBlank()) { "DASH 清单为空" }
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
        return document.documentElement
    }

    private fun child(parent: Element?, tag: String): Element? =
        if (parent == null) null else directChildren(parent, tag).firstOrNull()

    private fun childText(parent: Element, tag: String): String? =
        child(parent, tag)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun directChildren(parent: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node is Element && node.tagName.substringAfter(':').equals(tag, ignoreCase = true)) {
                result += node
            }
        }
        return result
    }

    private fun resolveHttpUrl(baseUrl: String, path: String): String {
        val resolved = runCatching { URI(baseUrl).resolve(URI(path)).normalize() }
            .getOrElse { throw IllegalArgumentException("DASH 媒体 URL 无效", it) }
        validateHttpUrl(resolved.toASCIIString())
        return resolved.toASCIIString()
    }

    private fun validateHttpUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null ||
            (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) throw IllegalArgumentException("DASH 只允许可访问的 HTTP/HTTPS 媒体地址")
    }

    private fun looksLikeDirectAudioUrl(value: String): Boolean {
        val path = runCatching { URI(value).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return listOf(".m4a", ".mp3", ".aac", ".ogg", ".opus", ".flac", ".wav").any(path::endsWith)
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long {
        if (numerator <= 0L || denominator <= 0L) return 0L
        return numerator / denominator + if (numerator % denominator == 0L) 0L else 1L
    }

    private const val PREFERRED_MAX_AUDIO_BANDWIDTH = 320_000L
}
