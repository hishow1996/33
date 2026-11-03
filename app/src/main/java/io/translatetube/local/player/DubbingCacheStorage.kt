package io.translatetube.local.player

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
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
                // First prefer any already-validated durable copy. A different payload with the
                // same cache identity is acceptable when both files are structurally/audibly valid;
                // the cache is derived data and either completed synthesis can be reused.
                val identical = try {
                    filesEqual(source, destination, checkActive)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (identical) {
                    source.delete()
                    continue
                }

                val destinationValid = isValidPcmCache(destination, checkActive)
                val sourceValid = isValidPcmCache(source, checkActive)
                if (destinationValid) {
                    // The durable payload is already usable; discard only the redundant legacy
                    // copy. Never let an older cache overwrite a good durable synthesis.
                    source.delete()
                    continue
                }
                if (!sourceValid) {
                    // Neither copy is usable. Preserve both until the normal cache reader can
                    // reject them and the active engine can regenerate the cue safely.
                    continue
                }

                val replaced = replaceInvalidDestination(
                    source = source,
                    destination = destination,
                    checkActive = checkActive,
                )
                if (replaced && isValidPcmCache(destination, checkActive)) {
                    runCatching { destination.setLastModified(source.lastModified()) }
                    if (source.delete()) migratedCount++
                }
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
     * Verify a complete persisted PCM payload, not just its header. Cache migration can encounter
     * a same-name/same-size destination whose float payload was damaged by an interrupted write.
     */
    private fun isValidPcmCache(file: File, checkActive: () -> Unit): Boolean {
        if (!file.isFile) return false
        val expectedLength = file.length()
        val expectedModified = file.lastModified()
        if (expectedLength !in 8L..(8L + DubbingPcmValidation.MAX_SAMPLE_COUNT.toLong() * 4L)) {
            return false
        }

        val valid = try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val sampleRate = input.readInt()
                val sampleCount = input.readInt()
                if (!DubbingPcmValidation.isValidCacheHeader(sampleRate, sampleCount, expectedLength)) {
                    return false
                }
                var audible = sampleCount == 0
                for (index in 0 until sampleCount) {
                    if ((index and 0x1fff) == 0) checkActive()
                    val sample = input.readFloat()
                    if (!sample.isFinite()) return false
                    if (!audible && DubbingPcmValidation.isAudibleSample(sample)) audible = true
                }
                audible && input.read() == -1
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

        return valid &&
            file.isFile &&
            file.length() == expectedLength &&
            file.lastModified() == expectedModified
    }

    /**
     * Replace a corrupt durable entry through a same-directory backup/rename sequence. The legacy
     * source is never touched here, so every failed step retains at least one recovery candidate.
     */
    private fun replaceInvalidDestination(
        source: File,
        destination: File,
        checkActive: () -> Unit,
    ): Boolean {
        val sourceLength = source.length()
        val sourceModified = source.lastModified()
        val temp = File(destination.parentFile, "${source.name}.${System.nanoTime()}.migration.tmp")
        val backup = File(destination.parentFile, "${destination.name}.${System.nanoTime()}.migration.bak")
        runCatching { temp.delete() }
        runCatching { backup.delete() }

        val copiedAndVerified = try {
            copyFile(source, temp, checkActive)
            temp.length() == sourceLength &&
                source.isFile &&
                source.length() == sourceLength &&
                source.lastModified() == sourceModified &&
                filesEqual(source, temp, checkActive) &&
                isValidPcmCache(temp, checkActive)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching { temp.delete() }
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!copiedAndVerified) {
            runCatching { temp.delete() }
            return false
        }

        checkActive()
        if (!destination.isFile || !destination.renameTo(backup)) {
            runCatching { temp.delete() }
            return false
        }
        val published = runCatching { temp.renameTo(destination) }.getOrDefault(false)
        if (!published) {
            runCatching { backup.renameTo(destination) }
            runCatching { temp.delete() }
            return false
        }

        val verified = isValidPcmCache(destination, checkActive) &&
            destination.length() == sourceLength &&
            filesEqual(source, destination, checkActive)
        if (!verified) {
            runCatching { destination.delete() }
            runCatching { backup.renameTo(destination) }
            runCatching { temp.delete() }
            return false
        }

        runCatching { backup.delete() }
        return true
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
