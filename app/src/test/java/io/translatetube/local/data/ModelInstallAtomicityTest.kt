package io.translatetube.local.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallAtomicityTest {
    @Test
    fun replacesVerifiedFileAndRemovesBackupAfterSuccess() {
        val root = createTempDir(prefix = "model-file-swap-")
        try {
            val target = File(root, "silero_vad.onnx").apply { writeText("old-valid-model") }
            val staged = File(root, "silero_vad.onnx.download").apply { writeText("new-valid-model") }

            ModelInstallAtomicity.replaceFile(staged, target)

            assertEquals("new-valid-model", target.readText())
            assertFalse(staged.exists())
            assertEquals(listOf("silero_vad.onnx"), root.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedFileRenameRestoresPreviousUsableAsset() {
        val root = createTempDir(prefix = "model-file-restore-")
        try {
            val target = File(root, "silero_vad.onnx").apply { writeText("old-valid-model") }
            val missingStaged = File(root, "missing.download")

            try {
                ModelInstallAtomicity.replaceFile(missingStaged, target)
                throw AssertionError("Expected replacement to fail")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("不存在"))
            }

            assertTrue(target.isFile)
            assertEquals("old-valid-model", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replacesModelDirectoryOnlyAfterStagingExists() {
        val root = createTempDir(prefix = "model-dir-swap-")
        try {
            val target = File(root, "tts").apply {
                mkdirs()
                File(this, "old.bin").writeText("old")
            }
            val staged = File(root, "tts.extract").apply {
                mkdirs()
                File(this, "new.bin").writeText("new")
            }

            ModelInstallAtomicity.replaceDirectory(staged, target)

            assertFalse(File(target, "old.bin").exists())
            assertEquals("new", File(target, "new.bin").readText())
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedDirectorySwapRestoresPreviousModelDirectory() {
        val root = createTempDir(prefix = "model-dir-restore-")
        try {
            val target = File(root, "tts").apply {
                mkdirs()
                File(this, "model.int8.onnx").writeText("old-model")
            }

            try {
                ModelInstallAtomicity.replaceDirectory(File(root, "missing.extract"), target)
                throw AssertionError("Expected replacement to fail")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("不存在"))
            }

            assertTrue(File(target, "model.int8.onnx").isFile)
            assertEquals("old-model", File(target, "model.int8.onnx").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
