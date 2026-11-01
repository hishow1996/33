package io.translatetube.local.player

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Stores generated dubbing PCM outside cacheDir so Android cannot opportunistically evict
 * completed speech between video visits. Legacy cacheDir entries are migrated when their video
 * is next opened; migration runs on the dubbing cache's IO dispatcher.
 */
internal object DubbingCacheStorage {
    private const val DIRECTORY_NAME = "dub"
    private const val COPY_BUFFER_BYTES = 64 * 1024

    fun rootDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun videoDirectory(context: Context, videoKey: String): File =
        File(rootDirectory(context), DubbingCachePath.directoryName(videoKey)).apply { mkdirs() }

    fun legacyVideoDirectory(context: Context, videoKey: String): File =
        File(
            File(context.applicationContext.cacheDir, DIRECTORY_NAME),
            DubbingCachePath.directoryName(videoKey),
        )

    /**
     * Move only finalized PCM files. A failed/interrupted copy never removes the legacy source,
     * and each cache is published with a rename so readers cannot observe a partial destination.
     */
    internal fun migrateLegacyVideoDirectory(
        legacyDirectory: File,
        durableDirectory: File,
        checkActive: () -> Unit = {},
    ): Int {
        if (!legacyDirectory.isDirectory) return 0
        if (!durableDirectory.isDirectory && !durableDirectory.mkdirs() && !durableDirectory.isDirectory) {
            return 0
        }

        var migratedCount = 0
        val legacyFiles = legacyDirectory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pcm", ignoreCase = true) }
            .orEmpty()
        for (source in legacyFiles) {
            checkActive()
            if (!source.isFile) continue
            val destination = File(durableDirectory, source.name)
            if (destination.isFile) {
                // The filename is already the full engine/text/voice identity. A same-sized
                // durable copy wins; preserve the legacy file if the existing copy differs.
                if (destination.length() == source.length()) {
                    source.delete()
                }
                continue
            }

            val sourceLength = source.length()
            val temp = File(durableDirectory, "${source.name}.migration.tmp")
            runCatching { temp.delete() }
            val copied = runCatching {
                copyFile(source, temp, checkActive)
                temp.length() == sourceLength && source.length() == sourceLength
            }.getOrDefault(false)
            if (!copied) {
                runCatching { temp.delete() }
                continue
            }

            checkActive()
            if (destination.exists()) {
                runCatching { temp.delete() }
                continue
            }
            val published = runCatching { temp.renameTo(destination) }.getOrDefault(false)
            if (!published || destination.length() != sourceLength) {
                runCatching { temp.delete() }
                continue
            }
            runCatching { destination.setLastModified(source.lastModified()) }
            if (source.delete()) migratedCount++
        }

        if (legacyDirectory.listFiles()?.isEmpty() == true) {
            runCatching { legacyDirectory.delete() }
        }
        return migratedCount
    }

    private fun copyFile(source: File, destination: File, checkActive: () -> Unit) {
        BufferedInputStream(FileInputStream(source)).use { input ->
            BufferedOutputStream(FileOutputStream(destination, false)).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
    }
}
