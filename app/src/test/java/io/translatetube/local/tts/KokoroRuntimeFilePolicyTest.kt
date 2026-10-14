package io.translatetube.local.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroRuntimeFilePolicyTest {
    @Test
    fun reportsMissingDependenciesEvenWhenCoreModelExists() = withTempDir { dir ->
        createSizedFile(dir, "model.int8.onnx", 1L * 1024 * 1024)
        createSizedFile(dir, "voices.bin", 1L * 1024 * 1024)
        val missing = KokoroRuntimeFilePolicy.missingRuntimeFiles(dir)
        assertFalse(missing.contains("model.int8.onnx"))
        assertFalse(missing.contains("voices.bin"))
        assertTrue(missing.contains("tokens.txt"))
        assertTrue(missing.contains("lexicon-zh.txt"))
        assertTrue(missing.contains("espeak-ng-data/"))
    }

    @Test
    fun dictDirectoryIsOptionalWhenAllDocumentedRuntimeFilesExist() = withTempDir { dir ->
        createCompleteRuntime(dir)
        File(dir, "dict").deleteRecursively()
        assertEquals(emptyList<String>(), KokoroRuntimeFilePolicy.missingRuntimeFiles(dir))
    }

    @Test
    fun cacheSignatureChangesWhenRuntimeMetadataChanges() = withTempDir { dir ->
        createCompleteRuntime(dir)
        val file = File(dir, "tokens.txt")
        val first = KokoroRuntimeFilePolicy.cacheSignature(dir)
        file.setLastModified(file.lastModified() + 10_000L)
        val second = KokoroRuntimeFilePolicy.cacheSignature(dir)
        assertFalse(first == second)
    }

    private fun createCompleteRuntime(dir: File) {
        createSizedFile(dir, "model.int8.onnx", 1L * 1024 * 1024)
        createSizedFile(dir, "voices.bin", 1L * 1024 * 1024)
        createSizedFile(dir, "tokens.txt", 512L)
        createSizedFile(dir, "lexicon-zh.txt", 1L * 1024 * 1024)
        createSizedFile(dir, "phone-zh.fst", 32L * 1024)
        createSizedFile(dir, "date-zh.fst", 20L * 1024)
        createSizedFile(dir, "number-zh.fst", 32L * 1024)
        createSizedFile(File(dir, "espeak-ng-data"), "phontab", 16L)
    }

    private fun createSizedFile(dir: File, name: String, size: Long) {
        createSizedFile(File(dir, name), size)
    }

    private fun createSizedFile(file: File, size: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kokoro-runtime-policy").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
