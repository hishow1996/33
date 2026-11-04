package io.translatetube.local.asr

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileInstallerTest {
    @Test
    fun successfulInstallReplacesOldFileAndRemovesBackup() {
        val directory = createTempDir("atomic-install")
        try {
            val destination = File(directory, "model.onnx").apply { writeText("old-model") }
            val temp = File(directory, "model.onnx.part").apply { writeText("new-model") }

            AtomicFileInstaller.install(temp, destination)

            assertEquals("new-model", destination.readText())
            assertFalse(temp.exists())
            assertFalse(File(directory, "model.onnx.backup").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedNewFileRenameRestoresPreviousDestination() {
        val directory = createTempDir("atomic-install-rollback")
        try {
            val destination = File(directory, "model.onnx").apply { writeText("old-model") }
            val temp = File(directory, "model.onnx.part").apply { writeText("new-model") }
            var failedInstallRename = false

            try {
                AtomicFileInstaller.install(temp, destination) { source, target ->
                    if (source == temp && target == destination && !failedInstallRename) {
                        failedInstallRename = true
                        false
                    } else {
                        source.renameTo(target)
                    }
                }
                throw AssertionError("Expected the new-file rename to fail")
            } catch (expected: IOException) {
                assertTrue(failedInstallRename)
            }

            assertEquals("old-model", destination.readText())
            assertTrue(temp.isFile)
            assertFalse(File(directory, "model.onnx.backup").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoversBackupLeftByInterruptedReplacementBeforeInstallingNewFile() {
        val directory = createTempDir("atomic-install-recovery")
        try {
            val destination = File(directory, "model.onnx")
            val backup = File(directory, "model.onnx.backup").apply { writeText("old-model") }
            val temp = File(directory, "model.onnx.part").apply { writeText("new-model") }
            assertTrue(backup.isFile)

            AtomicFileInstaller.install(temp, destination)

            assertEquals("new-model", destination.readText())
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidTempDoesNotTouchPreviouslyInstalledFile() {
        val directory = createTempDir("atomic-install-invalid")
        try {
            val destination = File(directory, "model.onnx").apply { writeText("old-model") }
            val missingTemp = File(directory, "missing.part")

            try {
                AtomicFileInstaller.install(missingTemp, destination)
                throw AssertionError("Expected invalid temp to be rejected")
            } catch (expected: IOException) {
                assertEquals("old-model", destination.readText())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
