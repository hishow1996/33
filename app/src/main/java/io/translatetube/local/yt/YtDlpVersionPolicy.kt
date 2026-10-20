package io.translatetube.local.yt

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * Parses stable yt-dlp release tags separately from installed builds. PyPI can publish dev/nightly
 * builds between stable releases, so the updater only offers stable releases while local detection
 * is allowed to recognize the full installed build string.
 */
internal object YtDlpVersionPolicy {
    private val stableTagPattern = Regex("^\\d{4}\\.\\d{1,2}\\.\\d{1,2}$")

    // Stable, nightly/master (YYYY.MM.DD.HHMMSS), and PyPI dev versions.
    private val installedBuildPattern =
        Regex("^\\d{4}\\.\\d{1,2}\\.\\d{1,2}(?:\\.\\d{1,8})?(?:\\.dev\\d*)?$")

    private val zipLocalHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private const val MAX_LEADING_BYTES = 8192
    private const val MAX_ZIP_ENTRIES = 20_000
    private const val VERSION_MODULE = "yt_dlp/version.py"
    private val versionAssignment = Regex("""__version__\\s*=\\s*['"]([^'"]+)['"]""")

    fun isStableTag(value: String): Boolean = stableTagPattern.matches(value.trim())

    /** Any recognizable installed yt-dlp build (stable, nightly/master, or dev). */
    fun isInstalledBuild(value: String): Boolean = installedBuildPattern.matches(value.trim())

    /**
     * Preserve the legacy stable-only API for callers/tests that specifically need release tags.
     * Unlike YoutubeDL.version(), --version output reflects the installed script.
     */
    fun installedVersionFromOutput(output: String): String? =
        parseInstalledOutput(output) { isStableTag(it) }

    /** Accept stable, nightly/master and dev versions in command output. */
    fun installedBuildFromOutput(output: String): String? =
        parseInstalledOutput(output) { isInstalledBuild(it) }

    private fun parseInstalledOutput(output: String, accepts: (String) -> Boolean): String? =
        output.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                when {
                    accepts(line) -> line
                    line.startsWith("yt-dlp version ", ignoreCase = true) ->
                        line.substring("yt-dlp version ".length).trim()
                    line.startsWith("yt-dlp ", ignoreCase = true) ->
                        line.substring("yt-dlp ".length).trim()
                    else -> null
                }
            }
            .firstOrNull { accepts(it) }

    /**
     * yt-dlp is a Python zipapp: a shebang followed by a normal ZIP. Read yt_dlp/version.py
     * directly, so version detection doesn't have to launch the embedded Python interpreter.
     */
    fun versionFromZipapp(input: InputStream): String? {
        val stream = BufferedInputStream(input)
        var matched = 0
        var scanned = 0
        // Skip the shebang: find the first ZIP local-file header signature PK\u0003\u0004.
        while (matched < zipLocalHeader.size) {
            val next = stream.read()
            scanned++
            if (next < 0 || scanned > MAX_LEADING_BYTES) return null
            matched = when {
                next == (zipLocalHeader[matched].toInt() and 0xff) -> matched + 1
                next == (zipLocalHeader[0].toInt() and 0xff) -> 1
                else -> 0
            }
        }
        return ZipInputStream(SequenceInputStream(ByteArrayInputStream(zipLocalHeader), stream)).use { archive ->
            var found: String? = null
            var entries = 0
            while (found == null && entries++ < MAX_ZIP_ENTRIES) {
                val entry = archive.nextEntry ?: break
                if (entry.name == VERSION_MODULE) {
                    val source = String(archive.readBytes(), Charsets.UTF_8)
                    found = versionAssignment.find(source)
                        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    break
                }
            }
            found
        }
    }

    /** A nightly build on the same/newer date as the latest stable release is not outdated. */
    fun isUpdateAvailable(installed: String?, latestStable: String?): Boolean {
        val installedDate = releaseDate(installed) ?: return false
        val latestDate = releaseDate(latestStable) ?: return false
        for (index in 0 until 3) {
            val compared = installedDate[index].compareTo(latestDate[index])
            if (compared != 0) return compared < 0
        }
        return false
    }

    private fun releaseDate(value: String?): List<Int>? {
        val trimmed = value?.trim().orEmpty()
        if (!isInstalledBuild(trimmed)) return null
        val parts = trimmed.split('.').take(3).map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.size == 3 }
    }

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
