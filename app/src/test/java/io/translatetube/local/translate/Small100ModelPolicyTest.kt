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

        val decoded = tokenizer.decode(longArrayOf(128102L, 88888L, 5L, 2L), skipSpecialTokens = true)
        assertEquals("你好.", decoded)
    }
}
