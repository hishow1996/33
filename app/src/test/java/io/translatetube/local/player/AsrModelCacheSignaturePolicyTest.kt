package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AsrModelCacheSignaturePolicyTest {
    private val whisperFiles = mapOf(
        "whisper/base-encoder.int8.onnx" to "900:100",
        "whisper/base-decoder.int8.onnx" to "800:100",
        "whisper/base-tokens.txt" to "700:100",
        "whisper/silero_vad.onnx" to "600:100",
    )

    @Test
    fun identicalModelFileMetadataProducesStableSignature() {
        assertEquals(
            AsrModelCacheSignaturePolicy.fingerprint("whisper", whisperFiles),
            AsrModelCacheSignaturePolicy.fingerprint("whisper", whisperFiles.toMap()),
        )
    }

    @Test
    fun replacingWeightsTokensOrVadInvalidatesTheModelSignature() {
        val original = AsrModelCacheSignaturePolicy.fingerprint("whisper", whisperFiles)
        assertNotEquals(
            original,
            AsrModelCacheSignaturePolicy.fingerprint(
                "whisper", whisperFiles + ("whisper/base-encoder.int8.onnx" to "901:101"),
            ),
        )
        assertNotEquals(
            original,
            AsrModelCacheSignaturePolicy.fingerprint(
                "whisper", whisperFiles + ("whisper/silero_vad.onnx" to "600:102"),
            ),
        )
        assertNotEquals(
            original,
            AsrModelCacheSignaturePolicy.fingerprint("sensevoice", whisperFiles),
        )
    }

    @Test
    fun missingModelFilesRemainDistinctFromInstalledFiles() {
        assertNotEquals(
            AsrModelCacheSignaturePolicy.fingerprint("whisper", whisperFiles),
            AsrModelCacheSignaturePolicy.fingerprint(
                "whisper", whisperFiles + ("whisper/base-decoder.int8.onnx" to "missing"),
            ),
        )
    }
}
