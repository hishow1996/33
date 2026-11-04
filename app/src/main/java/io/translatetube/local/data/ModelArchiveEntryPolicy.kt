package io.translatetube.local.data

/** Validates the single-root layout used by the official Kokoro and Whisper model archives. */
internal object ModelArchiveEntryPolicy {
    data class SafePath(val rootName: String, val relativePath: String)

    fun normalize(entryName: String, expectedRootName: String? = null): SafePath? {
        if (entryName.isEmpty() || '\u0000' in entryName) return null
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith('/')) return null

        val parts = normalized.split('/')
        if (parts.any { it == "." || it == ".." || ':' in it }) return null
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null

        val root = nonEmpty.first()
        if (root == "." || root == ".." || ':' in root) return null
        if (expectedRootName != null && root != expectedRootName) return null

        return SafePath(
            rootName = root,
            relativePath = nonEmpty.drop(1).joinToString("/"),
        )
    }
}
