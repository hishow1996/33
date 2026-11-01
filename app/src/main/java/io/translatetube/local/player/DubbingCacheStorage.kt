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
                // A matching filename and length are not enough: interrupted/bit-flipped PCM can
                // have the same size. Remove the legacy copy only after an exact byte comparison.
                val identical = try {
                    filesEqual(source, destination, checkActive)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (identical) source.delete()
                continue
            }

            val sourceLength = source.length()
            val sourceModified = source.lastModified()
            val temp = File(durableDirectory, "${source.name}.migration.tmp")
            runCatching { temp.delete() }
            val copied = try {
                copyFile(source, temp, checkActive)
                temp.length() == sourceLength &&
                    source.length() == sourceLength &&
                    source.lastModified() == sourceModified &&
                    filesEqual(source, temp, checkActive)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                runCatching { temp.delete() }
                throw cancelled
            } catch (_: Exception) {
                false
            }
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
            if (!published) {
                // Another migration may have published the destination after our existence check.
                // Never delete that other writer's file when our rename itself failed.
                runCatching { temp.delete() }
                continue
            }
            val publishedMatchesSource = try {
                destination.length() == sourceLength &&
                    source.lastModified() == sourceModified &&
                    filesEqual(source, destination, checkActive)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!publishedMatchesSource) {
                // This call successfully published the path, so it owns the destination it removes.
                runCatching { destination.delete() }
                runCatching { temp.delete() }
                continue
            }
            runCatching { destination.setLastModified(sourceModified) }
            if (source.delete()) migratedCount++
        }

        if (legacyDirectory.listFiles()?.isEmpty() == true) {
            runCatching { legacyDirectory.delete() }
        }
        return migratedCount
    }

    /**
     * Compare finalized cache files byte-for-byte. Length-only checks can mistake two different
     * PCM payloads for the same cache and delete the only recoverable copy.
     */
    private fun filesEqual(
        first: File,
        second: File,
        checkActive: () -> Unit,
    ): Boolean {
        if (!first.isFile || !second.isFile) return false
        val firstLength = first.length()
        val secondLength = second.length()
        val firstModified = first.lastModified()
        val secondModified = second.lastModified()
        if (firstLength != secondLength) return false

        val equal = BufferedInputStream(FileInputStream(first)).use { firstInput ->
            BufferedInputStream(FileInputStream(second)).use { secondInput ->
                val firstBuffer = ByteArray(COPY_BUFFER_BYTES)
                val secondBuffer = ByteArray(COPY_BUFFER_BYTES)
                var sameBytes = true
                var reachedEnd = false
                while (sameBytes && !reachedEnd) {
                    checkActive()
                    val firstCount = firstInput.read(firstBuffer)
                    val secondCount = secondInput.read(secondBuffer)
                    if (firstCount != secondCount) {
                        sameBytes = false
                    } else if (firstCount < 0) {
                        reachedEnd = true
                    } else {
                        for (index in 0 until firstCount) {
                            if (firstBuffer[index] != secondBuffer[index]) {
                                sameBytes = false
                                break
                            }
                        }
                    }
                }
                sameBytes && reachedEnd
            }
        }
        return equal &&
            first.isFile && second.isFile &&
            first.length() == firstLength && second.length() == secondLength &&
            first.lastModified() == firstModified && second.lastModified() == secondModified
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
