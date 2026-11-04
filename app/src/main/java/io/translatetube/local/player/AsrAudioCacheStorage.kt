package io.translatetube.local.player

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Durable, app-private storage for downloaded source audio used by ASR retries and speaker analysis.
 * The previous cacheDir location is migrated once data is encountered; file names remain unchanged
 * so VideoHistoryCacheIndex entries continue to resolve after the move.
 */
internal object AsrAudioCacheStorage {
    private const val DIRECTORY_NAME = "asr_audio_retry"
    private val migrationLock = Any()
    private val migratedRoots = HashSet<String>()

    fun directory(context: Context): File {
        val destination = File(context.noBackupFilesDir, DIRECTORY_NAME)
        migrateLegacyDirectory(File(context.cacheDir, DIRECTORY_NAME), destination)
        if (!destination.exists()) destination.mkdirs()
        return destination
    }

    /** Pure file migration entry point, also exercised by JVM tests. */
    internal fun migrateLegacyDirectory(legacy: File, destination: File): Boolean =
        synchronized(migrationLock) {
            val migrationKey = legacy.absolutePath + "->" + destination.absolutePath
            if (migrationKey in migratedRoots) return@synchronized true
            if (!legacy.exists()) {
                migratedRoots += migrationKey
                return@synchronized true
            }
            if (!destination.exists() && legacy.renameTo(destination)) {
                migratedRoots += migrationKey
                return@synchronized true
            }
            if (!destination.exists() && !destination.mkdirs() && !destination.isDirectory) {
                return@synchronized false
            }

            val succeeded = mergeDirectoryContents(legacy, destination)
            if (succeeded && (legacy.delete() || !legacy.exists())) {
                migratedRoots += migrationKey
                true
            } else {
                false
            }
        }

    private fun mergeDirectoryContents(source: File, destination: File): Boolean {
        if (!source.isDirectory || !destination.isDirectory) return false
        var succeeded = true
        val children = source.listFiles() ?: return false
        for (child in children) {
            val target = File(destination, child.name)
            if (!target.exists()) {
                if (!child.renameTo(target)) {
                    val copied = copyEntryAtomically(child, target)
                    if (!copied) succeeded = false
                }
            } else if (child.isDirectory && target.isDirectory) {
                if (!mergeDirectoryContents(child, target)) succeeded = false
                if (child.exists() && !child.delete() && child.exists()) succeeded = false
            } else if (child.isFile && target.isFile) {
                if (target.length() == 0L && child.length() > 0L) {
                    if (!target.delete() || !child.renameTo(target)) {
                        if (!copyEntryAtomically(child, target)) succeeded = false
                    }
                } else if (!child.delete() && child.exists()) {
                    // The persistent destination already has a usable file with this name.
                    succeeded = false
                }
            } else {
                // A name collision with a different entry type is not safe to overwrite or delete.
                // Keep the legacy entry in place so a later migration attempt can recover it.
                succeeded = false
            }
        }
        return succeeded
    }

    private fun copyEntryAtomically(source: File, destination: File): Boolean {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs() && !destination.isDirectory) return false
            val merged = mergeDirectoryContents(source, destination)
            val removed = !source.exists() || source.delete()
            return merged && removed
        }
        val temporary = File(destination.parentFile, destination.name + ".migrating")
        return runCatching {
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(temporary, false).buffered().use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.flush()
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            if (destination.exists()) return@runCatching false
            if (!temporary.renameTo(destination)) return@runCatching false
            source.delete()
        }.getOrDefault(false).also {
            if (!it) temporary.delete()
        }
    }
}
