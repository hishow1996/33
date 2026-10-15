package io.translatetube.local.asr

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranscriptionTest {
    @Test
    fun cartesiaLanguageKeepsBothDocumentedNorwegianCodesAndOmitsAuto() {
        assertEquals("en", CloudTranscription.cartesiaLanguageCode(" en "))
        assertEquals("zh", CloudTranscription.cartesiaLanguageCode("ZH"))
        assertEquals("no", CloudTranscription.cartesiaLanguageCode("no"))
        assertEquals("nn", CloudTranscription.cartesiaLanguageCode("nn"))
        assertEquals(null, CloudTranscription.cartesiaLanguageCode("auto"))
        assertEquals(null, CloudTranscription.cartesiaLanguageCode(" AUTO "))
        assertEquals(null, CloudTranscription.cartesiaLanguageCode("  "))
    }

    @Test
    fun retriesTransientKawaStatusErrorsButNotAuthOrCancellation() {
        assertTrue(CloudTranscription.shouldRetryKawaPoll(IOException("Kawa 查询转写状态失败（HTTP 429）")))
        assertTrue(CloudTranscription.shouldRetryKawaPoll(IOException("Kawa 查询转写状态失败（HTTP 503）")))
        assertTrue(CloudTranscription.shouldRetryKawaPoll(IOException("unexpected EOF")))
        assertEquals(false, CloudTranscription.shouldRetryKawaPoll(IOException("Kawa 查询转写状态失败（HTTP 401）")))
        assertEquals(
            false,
            CloudTranscription.shouldRetryKawaPoll(
                IOException("request cancelled", java.util.concurrent.CancellationException("cancelled"))
            ),
        )
    }

    @Test
    fun kawaLanguageTreatsAutoCaseInsensitivelyAsLanguageDetection() {
        assertEquals("zh", CloudTranscription.kawaLanguageCode(" zh "))
        assertEquals(null, CloudTranscription.kawaLanguageCode("auto"))
        assertEquals(null, CloudTranscription.kawaLanguageCode("AUTO"))
        assertEquals(null, CloudTranscription.kawaLanguageCode(" "))
    }
    @Test
    fun boundedProviderResponseReaderAcceptsWithinLimitAndRejectsOversizedBodies() {
        val text = "字幕响应"
        assertEquals(
            text,
            CloudTranscription.readBoundedUtf8(
                ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                maxBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        )

        val error = runCatching {
            CloudTranscription.readBoundedUtf8(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                maxBytes = 2L,
            )
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun boundedProviderResponseReaderRecoversFromZeroByteBulkReads() {
        val backing = ByteArrayInputStream("ok".toByteArray(Charsets.UTF_8))
        val unusual = object : java.io.InputStream() {
            private var returnZero = true
            override fun read(): Int = backing.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return backing.read(buffer, offset, length)
            }
        }
        assertEquals("ok", CloudTranscription.readBoundedUtf8(unusual, maxBytes = 2L))
    }

    @Test
    fun boundedProviderResponseReaderChecksCancellationBeforeReading() {
        val error = runCatching {
            CloudTranscription.readBoundedUtf8(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                maxBytes = 4L,
                checkActive = { throw java.util.concurrent.CancellationException("cancelled") },
            )
        }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.CancellationException)
    }

    @Test
    fun staleCloudWavCleanupOnlyDeletesOldOwnedStageFiles() {
        val directory = createTempDir(prefix = "cloud-wav-stages")
        try {
            val now = 10_000_000_000L
            val stale = File(directory, "cloud-wav-old.part").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - CloudTranscription.STAGED_WAV_MAX_AGE_MS - 1L)
            }
            val recent = File(directory, "cloud-wav-active.part").apply {
                writeBytes(byteArrayOf(2))
                setLastModified(now - 1_000L)
            }
            val staleRequest = File(directory, "asr-cloud-old.wav").apply {
                writeBytes(byteArrayOf(3))
                setLastModified(now - CloudTranscription.STAGED_WAV_MAX_AGE_MS - 1L)
            }
            val unrelated = File(directory, "other.part").apply {
                writeBytes(byteArrayOf(4))
                setLastModified(now - CloudTranscription.STAGED_WAV_MAX_AGE_MS - 1L)
            }
            val future = File(directory, "cloud-wav-future.part").apply {
                writeBytes(byteArrayOf(5))
                setLastModified(now + 1_000L)
            }

            assertEquals(2, CloudTranscription.cleanupStaleWavStages(directory, now))
            assertTrue(!stale.exists())
            assertTrue(!staleRequest.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
            assertTrue(future.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun wavExportDoesNotPublishPartialFileWhenCancelled() {
        val directory = createTempDir(prefix = "cloud-wav-cancel")
        try {
            val pcm = File(directory, "source.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val wav = File(directory, "output.wav")
            val error = runCatching {
                CloudTranscription.writeWavFile(
                    AudioExtract.PcmFile(pcm, sampleCount = 2L),
                    wav,
                    checkActive = { throw java.util.concurrent.CancellationException("cancelled") },
                )
            }.exceptionOrNull()

            assertTrue(error is java.util.concurrent.CancellationException)
            assertTrue(!wav.exists())
            assertEquals(listOf("source.pcm"), directory.listFiles()!!.map { it.name }.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun wavExportPreservesPreviousOutputWhenNewCopyFails() {
        val directory = createTempDir(prefix = "cloud-wav-preserve")
        try {
            val pcm = File(directory, "source.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val wav = File(directory, "output.wav").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val previous = wav.readBytes()
            val error = runCatching {
                CloudTranscription.writeWavFile(
                    AudioExtract.PcmFile(pcm, sampleCount = 2L),
                    wav,
                    checkActive = { throw java.util.concurrent.CancellationException("cancelled") },
                )
            }.exceptionOrNull()

            assertTrue(error is java.util.concurrent.CancellationException)
            assertEquals(previous.toList(), wav.readBytes().toList())
            assertEquals(listOf("output.wav", "source.pcm"), directory.listFiles()!!.map { it.name }.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun wavExportRejectsPcmFilesWhosePhysicalSizeDiffersFromDeclaredSamples() {
        val directory = createTempDir(prefix = "cloud-wav-length")
        try {
            val pcm = File(directory, "source.pcm").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val wav = File(directory, "output.wav")
            val error = runCatching {
                CloudTranscription.writeWavFile(AudioExtract.PcmFile(pcm, sampleCount = 1L), wav)
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(!wav.exists())

            CloudTranscription.writeWavFile(AudioExtract.PcmFile(pcm, sampleCount = 2L), wav)
            assertEquals(48L, wav.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun groupsOutOfOrderProviderWordsByTimestamp() {
        val cues = CloudTranscription.groupWords(
            listOf(
                CloudTranscription.TimedWord("world", 900L, 1_300L),
                CloudTranscription.TimedWord("Hello", 100L, 500L),
            ),
            durationMs = 2_000L,
        )

        assertEquals(1, cues.size)
        assertEquals(100L, cues.single().first)
        assertEquals(1_300L, cues.single().second)
        assertEquals("Hello world", cues.single().third)
    }

    @Test
    fun longPausesSplitWordsIntoSeparateCaptions() {
        val cues = CloudTranscription.groupWords(
            listOf(
                CloudTranscription.TimedWord("Good", 100L, 300L),
                CloudTranscription.TimedWord("morning.", 500L, 800L),
                CloudTranscription.TimedWord("Everyone", 2_000L, 2_400L),
            ),
            durationMs = 5_000L,
        )

        assertEquals(2, cues.size)
        assertEquals(100L to 800L, cues[0].first to cues[0].second)
        assertEquals("Good morning.", cues[0].third)
        assertEquals(2_000L to 2_400L, cues[1].first to cues[1].second)
        assertEquals("Everyone", cues[1].third)
    }

    @Test
    fun groupWordsIgnoresInvalidWordsAndClipsOnlyValidEnds() {
        val cues = CloudTranscription.groupWords(
            listOf(
                CloudTranscription.TimedWord("negative", -20L, 100L),
                CloudTranscription.TimedWord("empty", 100L, 100L),
                CloudTranscription.TimedWord("  ", 100L, 200L),
                CloudTranscription.TimedWord("valid", 9_800L, 12_000L),
                CloudTranscription.TimedWord("after", 10_000L, 10_500L),
            ),
            durationMs = 10_000L,
        )

        assertEquals(1, cues.size)
        assertEquals(9_800L to 10_000L, cues.single().first to cues.single().second)
        assertEquals("valid", cues.single().third)
    }

    @Test
    fun keepsTimelineWithinMediaDuration() {
        val cues = CloudTranscription.groupWords(
            listOf(
                CloudTranscription.TimedWord("ending", 850L, 1_000L),
                CloudTranscription.TimedWord("near", 700L, 820L),
            ),
            durationMs = 1_000L,
        )

        assertTrue(cues.all { it.first >= 0L && it.second <= 1_000L && it.second > it.first })
        assertEquals("near ending", cues.single().third)
    }

    @Test
    fun invalidDurationProducesNoCuesInsteadOfThrowing() {
        assertTrue(CloudTranscription.groupWords(
            listOf(CloudTranscription.TimedWord("hello", 0L, 100L)),
            durationMs = 0L,
        ).isEmpty())
        assertTrue(CloudTranscription.groupWords(emptyList(), durationMs = -1L).isEmpty())
    }

    @Test
    fun emptyWordListProducesNoCues() {
        assertTrue(CloudTranscription.groupWords(emptyList(), durationMs = 1_000L).isEmpty())
    }

    @Test
    fun rejectsInvalidProviderWordIntervalsAndStartsPastMediaEnd() {
        assertEquals(null, CloudTranscription.providerWordRange(1.0, 1.0, 10_000L))
        assertEquals(null, CloudTranscription.providerWordRange(2.0, 1.0, 10_000L))
        assertEquals(null, CloudTranscription.providerWordRange(-0.1, 0.5, 10_000L))
        assertEquals(null, CloudTranscription.providerWordRange(11.0, 12.0, 10_000L))
        assertEquals(null, CloudTranscription.providerWordRange(Double.NaN, 1.0, 10_000L))
        assertEquals(null, CloudTranscription.providerWordRange(0.0, 1.0, 0L))
    }

    @Test
    fun clampsOnlyAValidWordsEndThatExtendsPastMediaDuration() {
        assertEquals(
            9_900L to 10_000L,
            CloudTranscription.providerWordRange(9.9, 12.0, 10_000L),
        )
        assertEquals(
            0L to 1L,
            CloudTranscription.providerWordRange(0.0, 0.0005, 10_000L),
        )
    }

    @Test
    fun mapsKawaChunkLocalTimesWithinWholeMediaTimeline() {
        assertEquals(
            262_194L to 262_254L,
            CloudTranscription.mapChunkCue(
                offsetMs = 262_144L,
                localStartSeconds = 0.05,
                localEndSeconds = 0.11,
                chunkDurationMs = 262_144L,
                totalDurationMs = 600_000L,
            ),
        )
    }

    @Test
    fun clampsKawaCueEndAtChunkBoundaryAndRejectsStartOutsideChunk() {
        assertEquals(
            9_990L to 10_000L,
            CloudTranscription.mapChunkCue(
                offsetMs = 9_000L,
                localStartSeconds = 0.99,
                localEndSeconds = 9.0,
                chunkDurationMs = 1_000L,
                totalDurationMs = 20_000L,
            ),
        )
        assertEquals(
            null,
            CloudTranscription.mapChunkCue(
                offsetMs = 9_000L,
                localStartSeconds = 1.2,
                localEndSeconds = 1.4,
                chunkDurationMs = 1_000L,
                totalDurationMs = 20_000L,
            ),
        )
    }

    @Test
    fun invalidAndOverflowingKawaTimestampsCannotWrapIntoEarlierCues() {
        assertEquals(
            null,
            CloudTranscription.mapChunkCue(
                offsetMs = 262_144L,
                localStartSeconds = Double.MAX_VALUE,
                localEndSeconds = Double.MAX_VALUE,
                chunkDurationMs = 262_144L,
                totalDurationMs = 600_000L,
            ),
        )
        assertEquals(
            null,
            CloudTranscription.mapChunkCue(
                offsetMs = 10_000L,
                localStartSeconds = 0.5,
                localEndSeconds = 0.2,
                chunkDurationMs = 1_000L,
                totalDurationMs = 20_000L,
            ),
        )
    }

    @Test
    fun kawaContextChunksOverlapAudioButKeepCaptionOwnershipContiguous() {
        val chunkLimit = 8L * 1024L * 1024L
        val overlapBytes = 32_000L
        val pcmBytes = chunkLimit * 2L + 12_344L
        val ranges = CloudTranscription.kawaChunkRangesWithContext(pcmBytes)

        assertEquals(listOf(0L, chunkLimit - overlapBytes, chunkLimit * 2L - overlapBytes),
            ranges.map { it.offsetBytes })
        assertEquals(listOf(chunkLimit, chunkLimit + overlapBytes, overlapBytes + 12_344L),
            ranges.map { it.sizeBytes })
        assertEquals(listOf(0L, (chunkLimit - overlapBytes) / 32L, (chunkLimit * 2L - overlapBytes) / 32L),
            ranges.map { it.ownedStartMs })
        assertEquals(
            listOf((chunkLimit - overlapBytes) / 32L, (chunkLimit * 2L - overlapBytes) / 32L, pcmBytes / 32L),
            ranges.map { it.ownedEndMs },
        )
        assertTrue(ranges.all {
            it.offsetBytes >= 0L &&
                it.sizeBytes in 2L..(chunkLimit + overlapBytes) &&
                it.sizeBytes % 2L == 0L &&
                it.offsetBytes + it.sizeBytes <= pcmBytes
        })
        assertTrue(ranges.zipWithNext().all { (left, right) -> left.ownedEndMs == right.ownedStartMs })
        assertTrue(CloudTranscription.kawaChunkRangesWithContext(0L).isEmpty())
        assertTrue(CloudTranscription.kawaChunkRangesWithContext(3L).isEmpty())
    }

    @Test
    fun kawaChunksAreBoundedContiguousAndCoverTheWholePcm() {
        val chunkLimit = 8L * 1024L * 1024L
        val pcmBytes = chunkLimit * 2L + 12_344L
        val ranges = CloudTranscription.kawaChunkRanges(pcmBytes)

        assertEquals(listOf(0L, chunkLimit, chunkLimit * 2L), ranges.map { it.first })
        assertEquals(listOf(chunkLimit, chunkLimit, 12_344L), ranges.map { it.second })
        assertTrue(ranges.all { (offset, size) -> offset >= 0L && size in 2L..chunkLimit && size % 2L == 0L })
        assertEquals(pcmBytes, ranges.sumOf { it.second })
        assertTrue(CloudTranscription.kawaChunkRanges(0L).isEmpty())
        assertTrue(CloudTranscription.kawaChunkRanges(3L).isEmpty())
    }
}
