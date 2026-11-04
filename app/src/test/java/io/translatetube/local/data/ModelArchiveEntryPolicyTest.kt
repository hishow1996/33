package io.translatetube.local.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelArchiveEntryPolicyTest {
    @Test
    fun stripsOnlyOneExpectedTopLevelDirectory() {
        assertEquals(
            ModelArchiveEntryPolicy.SafePath("whisper-root", "base-encoder.int8.onnx"),
            ModelArchiveEntryPolicy.normalize("whisper-root/base-encoder.int8.onnx"),
        )
        assertEquals(
            ModelArchiveEntryPolicy.SafePath("whisper-root", ""),
            ModelArchiveEntryPolicy.normalize("whisper-root/"),
        )
    }

    @Test
    fun rejectsUnixAbsoluteWindowsAbsoluteAndDriveQualifiedPaths() {
        assertNull(ModelArchiveEntryPolicy.normalize("/whisper-root/model.onnx"))
        assertNull(ModelArchiveEntryPolicy.normalize("\\whisper-root\\model.onnx"))
        assertNull(ModelArchiveEntryPolicy.normalize("C:\\whisper-root\\model.onnx"))
        assertNull(ModelArchiveEntryPolicy.normalize("whisper-root/C:/outside.onnx"))
    }

    @Test
    fun rejectsParentAndCurrentDirectoryTraversal() {
        assertNull(ModelArchiveEntryPolicy.normalize("whisper-root/../outside.onnx"))
        assertNull(ModelArchiveEntryPolicy.normalize("whisper-root/./outside.onnx"))
        assertNull(ModelArchiveEntryPolicy.normalize("./whisper-root/model.onnx"))
        assertNull(ModelArchiveEntryPolicy.normalize("whisper-root/sub/../../outside.onnx"))
    }

    @Test
    fun rejectsDifferentTopLevelDirectoriesWithinOneArchive() {
        assertNotNull(ModelArchiveEntryPolicy.normalize("whisper-root/base-encoder.int8.onnx"))
        assertNull(
            ModelArchiveEntryPolicy.normalize(
                "unexpected-root/base-decoder.int8.onnx",
                expectedRootName = "whisper-root",
            )
        )
    }

    @Test
    fun rejectsBlankAndNulPaths() {
        assertNull(ModelArchiveEntryPolicy.normalize(""))
        assertNull(ModelArchiveEntryPolicy.normalize("\u0000whisper-root/model.onnx"))
    }
}
