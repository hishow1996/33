package io.translatetube.local.yt

import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses only yt-dlp stable release tags (YYYY.M.D). PyPI can publish dev builds between
 * stable releases, so selecting info.version directly can advertise an update that STABLE
 * channel will never install.
 */
internal object YtDlpVersionPolicy {
    private val stableTagPattern = Regex("^\\d{4}\\.\\d{1,2}\\.\\d{1,2}$")

    fun isStableTag(value: String): Boolean = stableTagPattern.matches(value.trim())

    /**
     * yt-dlp's actual `--version` command prints a bare stable release tag.
     * Unlike YoutubeDL.version(), this reflects the bundled/installed binary and does not
     * depend on the updater having previously saved its private SharedPreferences key.
     */
    fun installedVersionFromOutput(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .firstOrNull { isStableTag(it) }

    fun latestStableFromPypiJson(json: String): String? = runCatching {
        val root = JSONObject(json)
        val releases = root.optJSONObject("releases")
        val candidates = mutableListOf<String>()
        if (releases != null) {
            val keys = releases.keys()
            while (keys.hasNext()) {
                val tag = keys.next().trim()
                if (isStableTag(tag)) candidates += tag
            }
        }
        val stableFromReleases = candidates.maxWithOrNull(Comparator(::compareStableTags))
        if (stableFromReleases != null) return@runCatching stableFromReleases

        // Some mirror responses omit the full releases map; only accept info.version if stable.
        val infoVersion = root.optJSONObject("info")?.optString("version").orEmpty().trim()
        infoVersion.takeIf { isStableTag(it) }
    }.getOrNull()

    fun stableGitHubTag(json: String): String? = runCatching {
        JSONObject(json).optString("tag_name").trim().takeIf { isStableTag(it) }
    }.getOrNull()

    fun stableTagFromReleasePath(encodedPath: String): String? = runCatching {
        val marker = "/releases/tag/"
        val encodedTag = encodedPath.substringAfter(marker, "").substringBefore("/")
        if (encodedTag.isBlank()) return@runCatching null
        val tag = URLDecoder.decode(encodedTag, "UTF-8").trim()
        tag.takeIf { isStableTag(it) }
    }.getOrNull()

    private fun compareStableTags(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until minOf(a.size, b.size)) {
            val compared = a[index].compareTo(b[index])
            if (compared != 0) return compared
        }
        return a.size.compareTo(b.size)
    }
}
