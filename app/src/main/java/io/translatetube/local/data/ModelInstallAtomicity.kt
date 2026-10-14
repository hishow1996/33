package io.translatetube.local.data

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Replaces a verified model asset without destroying the previous usable copy first.
 * Temporary and destination entries must share a parent directory so rename stays on one volume.
 */
internal object ModelInstallAtomicity {
    fun replaceFile(stagedFile: File, destination: File) =
        replace(stagedFile, destination, requireDirectory = false)

    fun replaceDirectory(stagedDirectory: File, destination: File) =
        replace(stagedDirectory, destination, requireDirectory = true)

    private fun replace(staged: File, destination: File, requireDirectory: Boolean) {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("模型目标路径无效")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("无法创建模型目标目录")
        }
        val canonicalParent = parent.canonicalFile
        if (staged.absoluteFile.parentFile?.canonicalFile != canonicalParent) {
            throw IOException("模型临时文件与目标不在同一目录，拒绝非原子替换")
        }
        if (!staged.exists() || staged.isDirectory != requireDirectory) {
            throw IOException(if (requireDirectory) "待安装模型目录不存在或类型错误" else "待安装模型文件不存在或类型错误")
        }
        if (destination.exists() && destination.isDirectory != requireDirectory) {
            throw IOException("现有模型文件类型与目标不匹配")
        }

        val backup = File(
            canonicalParent,
            "${destination.name}.backup-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
        )
        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(backup)) {
            throw IOException("无法安全备份现有模型：${destination.name}")
        }

        try {
            if (!staged.renameTo(destination)) {
                throw IOException("无法安装新的模型资产：${destination.name}")
            }
        } catch (installError: Exception) {
            if (hadPrevious && backup.exists() && !backup.renameTo(destination)) {
                // Do not delete the only remaining previous copy. Keep its path in the error so
                // diagnostics can recover it manually instead of reporting only a generic failure.
                throw IOException(
                    "模型替换失败，旧版本仍保留在 ${backup.absolutePath}，但自动恢复失败",
                    installError,
                )
            }
            throw installError
        }

        // The new file is installed and usable. A backup cleanup failure should not mark install
        // as failed, but it must never delete or roll back the new asset.
        if (backup.exists()) {
            if (backup.isDirectory) backup.deleteRecursively() else backup.delete()
        }
    }
}
