package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Small100ModelPolicyTest {
    @Test
    fun exposesAllHundredLanguagesFromThePinnedModel() {
        assertEquals(100, Small100LanguagePolicy.languages.size)
        assertEquals(100, Small100LanguagePolicy.expectedTokenMap().size)
        assertTrue(Small100LanguagePolicy.supportsSource("ja-JP"))
        assertTrue(Small100LanguagePolicy.supportsSource("af"))
        assertTrue(Small100LanguagePolicy.supportsSource("zh-Hans-CN"))
        assertFalse(Small100LanguagePolicy.supportsSource("xx"))
    }

    @Test
    fun canonicalizesCommonLanguageAliases() {
        assertEquals("he", Small100LanguagePolicy.canonicalCode("iw"))
        assertEquals("id", Small100LanguagePolicy.canonicalCode("in-ID"))
        assertEquals("no", Small100LanguagePolicy.canonicalCode("nb-NO"))
        assertEquals("zh", Small100LanguagePolicy.canonicalCode("zh-Hant-TW"))
    }

    @Test
    fun acceptsOnlyAResumeRangeThatStartsAtTheSavedOffsetAndEndsAtTheFileBoundary() {
        assertTrue(
            ModelDownloadRangePolicy.matches(
                "bytes 100-999/1000",
                expectedStart = 100L,
                expectedTotal = 1000L,
            )
        )
        assertFalse(
            ModelDownloadRangePolicy.matches(
                "bytes 99-999/1000",
                expectedStart = 100L,
                expectedTotal = 1000L,
            )
        )
        assertFalse(
            ModelDownloadRangePolicy.matches(
                "bytes 100-900/1000",
                expectedStart = 100L,
                expectedTotal = 1000L,
            )
        )
        assertFalse(
            ModelDownloadRangePolicy.matches(
                "bytes 100-999/1001",
                expectedStart = 100L,
                expectedTotal = 1000L,
            )
        )
    }

    @Test
    fun identifiesCompletedTemporaryFilesAndRejectsOversizedResponses() {
        assertTrue(ModelDownloadRangePolicy.isCompletePart(500L, 500L))
        assertFalse(ModelDownloadRangePolicy.isCompletePart(499L, 500L))
        assertFalse(ModelDownloadRangePolicy.isCompletePart(501L, 500L))
        assertTrue(ModelDownloadRangePolicy.canAcceptBytes(450L, 50L, 500L))
        assertFalse(ModelDownloadRangePolicy.canAcceptBytes(450L, 51L, 500L))
    }

    @Test
    fun marksCompleteModelFilesAsDownloadedEvenWhenRuntimeValidationIsPending() {
        val state = Small100DownloadState(
            status = Small100Status.PAUSED,
            downloadedBytes = Small100ModelManager.TOTAL_BYTES,
            totalBytes = Small100ModelManager.TOTAL_BYTES,
        )
        assertTrue(state.hasCompleteFiles)
    }

    @Test
    fun priorityQueueBpePreservesMergeRankAndLeftToRightOverlapRules() {
        val tokens = listOf("▁", "a", "▁a", "aa", "aaaa")
        val vocab = tokens.withIndex().associate { it.value to (20 + it.index) }
        val idToToken = arrayOfNulls<String>(64).apply {
            vocab.forEach { (token, id) -> this[id] = token }
        }
        val tokenizer = Small100BpeTokenizer(
            vocab = vocab,
            idToToken = idToToken,
            merges = mapOf(
                "▁ a" to 0,
                "a a" to 1,
                "aa aa" to 2,
            ),
            specialIds = emptySet(),
        )

        // "▁a" is formed first, then the remaining repeated letters are
        // merged left-to-right in non-overlapping pairs.
        val encoded = tokenizer.encode("aaaaaaaa")
        assertEquals(
            listOf(
                vocab.getValue("▁a"),
                vocab.getValue("aaaa"),
                vocab.getValue("aa"),
                vocab.getValue("a"),
                2,
            ),
            encoded.map { it.toInt() },
        )
    }

    @Test
    fun priorityQueueBpeHandlesLongUnbrokenTextWithoutChangingCharacterOrder() {
        val tokens = listOf("▁", "a", "▁a", "aa")
        val vocab = tokens.withIndex().associate { it.value to (20 + it.index) }
        val idToToken = arrayOfNulls<String>(64).apply {
            vocab.forEach { (token, id) -> this[id] = token }
        }
        val tokenizer = Small100BpeTokenizer(
            vocab = vocab,
            idToToken = idToToken,
            merges = mapOf("▁ a" to 0, "a a" to 1),
            specialIds = emptySet(),
        )
        val encoded = tokenizer.encode("a".repeat(512))
        assertEquals(258, encoded.size)
        assertEquals(2L, encoded.last())
        assertEquals(512, tokenizer.decode(encoded).replace(" ", "").length)
        assertEquals(1, encoded.dropLast(1).count { it.toInt() == vocab.getValue("▁a") })
        assertEquals(255, encoded.dropLast(1).count { it.toInt() == vocab.getValue("aa") })
        assertEquals(1, encoded.dropLast(1).count { it.toInt() == vocab.getValue("a") })
    }

    @Test
    fun parsesTokenizerJsonAndEncodesAndDecodesBpeTokens() {
        val json = """
            {
              "version": "1.0",
              "added_tokens": [
                {"id": 0, "content": "<s>", "special": true},
                {"id": 1, "content": "<pad>", "special": true},
                {"id": 2, "content": "</s>", "special": true},
                {"id": 3, "content": "<unk>", "special": true},
                {"id": 128102, "content": "__zh__", "special": true}
              ],
              "model": {
                "type": "BPE",
                "fuse_unk": true,
                "vocab": {
                  "<s>": 0,
                  "<pad>": 1,
                  "</s>": 2,
                  "<unk>": 3,
                  ".": 5,
                  "▁": 10,
                  "H": 11,
                  "e": 12,
                  "l": 13,
                  "o": 14,
                  "▁H": 15,
                  "el": 16,
                  "lo": 17,
                  "▁Hel": 18,
                  "▁Hello": 65761,
                  "▁你好": 88888
                },
                "merges": [
                  "▁ H",
                  "e l",
                  "l o",
                  "▁H el",
                  "▁Hel lo"
                ]
              }
            }
        """.trimIndent()

        val tokenizer = Small100BpeTokenizer.fromStream(json.byteInputStream(Charsets.UTF_8))
        val encoded = tokenizer.encode("Hello.")
        org.junit.Assert.assertArrayEquals(longArrayOf(65761L, 5L, 2L), encoded)

        val repeated = tokenizer.encode("Hello Hello")
        org.junit.Assert.assertArrayEquals(longArrayOf(65761L, 65761L, 2L), repeated)
        assertEquals(2, tokenizer.cachedWordCount)
        val repeatedAgain = tokenizer.encode("Hello Hello")
        org.junit.Assert.assertArrayEquals(repeated, repeatedAgain)

        val decoded = tokenizer.decode(longArrayOf(128102L, 88888L, 5L, 2L), skipSpecialTokens = true)
        assertEquals("你好.", decoded)

        for (index in 0 until 1100) {
            tokenizer.encode("cacheword$index")
        }
        assertEquals(1024, tokenizer.cachedWordCount)
    }
}
