package io.translatetube.local.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetReadinessTest {
    @Test
    fun rejectsMissingSmallAssetsEvenWhenLargeOnnxFilesExist() {
        val dir = createTempDir(prefix = "kokoro-ready-")
        try {
            createFile(dir, "model.int8.onnx", 1_100_000)
            createFile(dir, "voices.bin", 1_100_000)
            createFile(dir, "tokens.txt", 20)
            assertFalse(ModelAssetReadiness.isTtsReady(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun acceptsACompleteKokoroAssetSet() {
        val dir = createTempDir(prefix = "kokoro-ready-")
        try {
            createFile(dir, "model.int8.onnx", 1_100_000)
            createFile(dir, "voices.bin", 1_100_000)
            listOf("tokens.txt", "lexicon-zh.txt", "phone-zh.fst", "date-zh.fst", "number-zh.fst")
                .forEach { createFile(dir, it, 20) }
            createFile(File(dir, "espeak-ng-data").apply { mkdirs() }, "phondata", 20)
            createFile(File(dir, "dict").apply { mkdirs() }, "zh.dict", 20)

            assertTrue(ModelAssetReadiness.isTtsReady(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyDirectoriesAndTruncatedLargeAssets() {
        val dir = createTempDir(prefix = "kokoro-ready-")
        try {
            createFile(dir, "model.int8.onnx", 100)
            createFile(dir, "voices.bin", 1_100_000)
            listOf("tokens.txt", "lexicon-zh.txt", "phone-zh.fst", "date-zh.fst", "number-zh.fst")
                .forEach { createFile(dir, it, 20) }
            File(dir, "espeak-ng-data").mkdirs()
            createFile(File(dir, "dict").apply { mkdirs() }, "zh.dict", 20)

            assertFalse(ModelAssetReadiness.isTtsReady(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createFile(parent: File, name: String, bytes: Int): File {
        if (!parent.exists()) parent.mkdirs()
        return File(parent, name).apply { writeBytes(ByteArray(bytes) { 1 }) }
    }
    @Test
    fun acceptsAssetsNestedInsideModelDataDirectories() {
        val dir = createTempDir(prefix = "kokoro-ready-nested-")
        try {
            createFile(dir, "model.int8.onnx", 1_100_000)
            createFile(dir, "voices.bin", 1_100_000)
            listOf("tokens.txt", "lexicon-zh.txt", "phone-zh.fst", "date-zh.fst", "number-zh.fst")
                .forEach { createFile(dir, it, 20) }
            createFile(File(dir, "espeak-ng-data/voices/zh").apply { mkdirs() }, "zh", 20)
            createFile(File(dir, "dict/zh").apply { mkdirs() }, "words.dict", 20)

            assertTrue(ModelAssetReadiness.isTtsReady(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

}
