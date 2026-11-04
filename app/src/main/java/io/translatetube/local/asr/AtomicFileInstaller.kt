package io.translatetube.local.asr

import java.io.File
import java.io.IOException

/**
 * Replaces a model file without deleting the last usable version first.
 *
 * The backup also makes replacement recoverable after process death between the two renames:
 * the next installer call restores a missing destination before attempting another replacement.
 */
internal object AtomicFileInstaller {
    fun install(
        temp: File,
        destination: File,
        rename: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    ) {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("目标文件目录无效")
        if (temp.absoluteFile.parentFile != parent || temp.absoluteFile == destination.absoluteFile) {
            throw IOException("临时文件必须与目标文件位于同一目录")
        }
        if (!temp.isFile || temp.length() <= 0L) {
            throw IOException("临时文件不存在或为空")
        }
        if (destination.exists() && !destination.isFile) {
            throw IOException("目标路径不是普通文件")
        }

        val backup = File(parent, destination.name + ".backup")
        if (backup.exists()) {
            if (!backup.isFile) throw IOException("旧文件备份路径无效")
            if (destination.exists()) {
                // A destination beside a backup means a prior atomic install completed, but
                // the process stopped before deleting its backup.
                if (!backup.delete()) throw IOException("无法清理旧文件备份")
            } else if (!rename(backup, destination)) {
                throw IOException("无法恢复上一次替换保留的旧文件")
            }
        }

        val hadDestination = destination.exists()
        if (hadDestination && !rename(destination, backup)) {
            throw IOException("无法备份现有文件，旧文件仍保留")
        }

        try {
            if (!rename(temp, destination)) {
                throw IOException("无法安装新文件")
            }
        } catch (error: Exception) {
            if (!destination.exists() && backup.isFile && !rename(backup, destination)) {
                error.addSuppressed(IOException("新文件安装失败，旧文件仍保留在备份路径"))
            }
            throw error
        }

        // Installation already succeeded. A stale backup can be safely removed by the next
        // call if the filesystem temporarily refuses deletion; do not report a false install
        // failure after the new destination has been published.
        if (backup.exists()) runCatching { backup.delete() }
    }
}
