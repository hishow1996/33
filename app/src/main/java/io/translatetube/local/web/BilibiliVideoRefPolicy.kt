package io.translatetube.local.web

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Pure helpers for Bilibili's native resolver: which video (BV/av) and which part (`?p=N`)
 * a link points at, and which part of the API's `pages` list to use.
 *
 * Two defects this fixes:
 * - BV ids are case-sensitive. The old code upper-cased the whole id, which turned a valid
 *   `BV1GJ411x7h7` into the non-existent `BV1GJ411X7H7`, so the native resolver almost always
 *   failed and every link silently fell back to the much slower yt-dlp path.
 * - `?p=N` (multi-part videos) was dropped, so a link to part 3 was parsed as part 1.
 */
internal object BilibiliVideoRefPolicy {
    data class Ref(val bvid: String?, val aid: Long?, val page: Int?)

    data class PageInfo(val cid: Long, val page: Int, val part: String, val durationSec: Long)

    /** Short-link hosts that only redirect to the real video page. */
    private val shortLinkHosts = setOf("b23.tv", "bili2233.cn")

    private val videoIdPattern = Regex(
        """(?:^|/)video/(BV[A-Za-z0-9]{10}|av[0-9]+)(?:/|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun isShortLinkHost(host: String?): Boolean =
        host?.lowercase(Locale.ROOT)?.trimEnd('.') in shortLinkHosts

    /** Only the `BV` prefix is case-insensitive; the ten id characters must be kept as typed. */
    fun normalizeBvid(raw: String): String = "BV" + raw.substring(2)

    /** Extract BV/av id and optional part number from an already expanded web video URL. */
    fun parse(webUrl: String): Ref? {
        val uri = runCatching { URI(webUrl.trim()) }.getOrNull()
        val path = uri?.rawPath ?: webUrl.substringBefore('?').substringBefore('#')
        val raw = videoIdPattern.find(path)?.groupValues?.getOrNull(1) ?: return null
        val page = pageFromQuery(uri?.rawQuery ?: webUrl.substringAfter('?', "").substringBefore('#'))
        return if (raw.startsWith("BV", ignoreCase = true)) {
            Ref(bvid = normalizeBvid(raw), aid = null, page = page)
        } else {
            val aid = raw.substring(2).toLongOrNull()?.takeIf { it > 0 } ?: return null
            Ref(bvid = null, aid = aid, page = page)
        }
    }

    private fun pageFromQuery(rawQuery: String?): Int? {
        if (rawQuery.isNullOrBlank()) return null
        for (pair in rawQuery.split('&')) {
            val name = pair.substringBefore('=')
            if (name != "p") continue
            val value = runCatching {
                URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
            }.getOrNull()
            return value?.trim()?.toIntOrNull()?.takeIf { it in 1..99999 }
        }
        return null
    }

    /**
     * Choose the requested part. A missing or out-of-range part number falls back to the first
     * part, matching what the Bilibili site itself plays.
     */
    fun pickPage(pages: List<PageInfo>, requestedPage: Int?): PageInfo? {
        val valid = pages.filter { it.cid > 0 }
        if (valid.isEmpty()) return null
        if (requestedPage != null) {
            valid.firstOrNull { it.page == requestedPage }?.let { return it }
        }
        return valid.firstOrNull { it.page == 1 } ?: valid.first()
    }

    /** Title shown in history: add the part marker only for later parts of a multi-part video. */
    fun displayTitle(baseTitle: String, pages: List<PageInfo>, picked: PageInfo): String {
        if (pages.size <= 1 || picked.page <= 1) return baseTitle
        val part = picked.part.trim()
        return if (part.isEmpty()) "$baseTitle · P${picked.page}" else "$baseTitle · P${picked.page} $part"
    }
}
