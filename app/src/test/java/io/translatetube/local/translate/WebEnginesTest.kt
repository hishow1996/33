package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebEnginesTest {
    @Test
    fun microsoftCodesHandleChineseVariantsAndUnknownSource() {
        assertEquals("zh-Hans", WebLangCodes.microsoft("zh", target = true))
        assertEquals("zh-Hans", WebLangCodes.microsoft("und-Latn", target = true))
        assertEquals("zh-Hant", WebLangCodes.microsoft("zh-TW", target = true))
        assertEquals("zh-Hant", WebLangCodes.microsoft("zh-Hant-HK", target = true))
        // 源语言未知时留空，让服务端自动识别。
        assertEquals("", WebLangCodes.microsoft("", target = false))
        assertEquals("", WebLangCodes.microsoft("auto", target = false))
        assertEquals("ja", WebLangCodes.microsoft("ja-JP", target = false))
        assertEquals("nb", WebLangCodes.microsoft("no", target = false))
        assertEquals("he", WebLangCodes.microsoft("iw", target = false))
    }

    @Test
    fun baiduCodesUseBaiduSpecificSpellings() {
        assertEquals("jp", WebLangCodes.baidu("ja", target = false))
        assertEquals("kor", WebLangCodes.baidu("ko-KR", target = false))
        assertEquals("fra", WebLangCodes.baidu("fr", target = false))
        assertEquals("spa", WebLangCodes.baidu("es", target = false))
        assertEquals("vie", WebLangCodes.baidu("vi", target = false))
        assertEquals("zh", WebLangCodes.baidu("zh-Hans", target = true))
        assertEquals("cht", WebLangCodes.baidu("zh-TW", target = true))
        assertEquals("zh", WebLangCodes.baidu("", target = true))
        assertEquals("auto", WebLangCodes.baidu("", target = false))
    }

    @Test
    fun baiduUnsupportedSourceFallsBackToAutoButUnsupportedTargetFails() {
        assertEquals("auto", WebLangCodes.baidu("bn", target = false))
        assertThrows(IllegalStateException::class.java) { WebLangCodes.baidu("bn", target = true) }
    }

    @Test
    fun chunkingKeepsOrderAndRespectsItemAndCharLimits() {
        val items = listOf("a", "bb", "ccc", "dddd", "eeeee")
        val byItems = chunkForRequest(items, maxItems = 2, maxChars = 100) { it.length }
        assertEquals(listOf(listOf("a", "bb"), listOf("ccc", "dddd"), listOf("eeeee")), byItems)

        val byChars = chunkForRequest(items, maxItems = 100, maxChars = 6) { it.length }
        assertEquals(listOf(listOf("a", "bb", "ccc"), listOf("dddd"), listOf("eeeee")), byChars)

        // 单条超过字符上限时也必须单独成块，不能丢掉。
        val oversize = chunkForRequest(listOf("x".repeat(10)), maxItems = 5, maxChars = 3) { it.length }
        assertEquals(1, oversize.size)
        assertTrue(chunkForRequest(emptyList<String>(), 5, 5) { it.length }.isEmpty())
    }

    @Test
    fun microsoftResponseIsParsedInRequestOrder() {
        val body = """[
            {"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"你好，世界","to":"zh-Hans"}]},
            {"translations":[{"text":" 你今天好吗？ ","to":"zh-Hans"}]}
        ]"""
        assertEquals(listOf("你好，世界", "你今天好吗？"), parseMicrosoftTranslations(body, 2))
    }

    @Test
    fun microsoftResponseWithWrongCountIsRejectedInsteadOfShiftingCues() {
        val body = """[{"translations":[{"text":"一"}]}]"""
        val error = assertThrows(IllegalStateException::class.java) {
            parseMicrosoftTranslations(body, 2)
        }
        assertTrue(error.message.orEmpty().contains("数量不匹配"))
    }

    @Test
    fun microsoftErrorObjectsAndNonJsonBecomeReadableErrors() {
        val apiError = assertThrows(IllegalStateException::class.java) {
            parseMicrosoftTranslations("""{"error":{"code":429001,"message":"too many requests"}}""", 1)
        }
        assertTrue(apiError.message.orEmpty().contains("too many requests"))

        assertThrows(IllegalStateException::class.java) {
            parseMicrosoftTranslations("<html>blocked</html>", 1)
        }
    }

    @Test
    fun baiduResponseJoinsSentencePiecesWithoutSpacesForChinese() {
        val body = """{"from":"en","to":"zh","data":[
            {"src":"Hello.","dst":"你好。"},{"src":"How are you?","dst":"你好吗？"}
        ]}"""
        assertEquals("你好。你好吗？", parseBaiduTranslation(body, "zh"))
        assertEquals("Bonjour. Salut.", parseBaiduTranslation(
            """{"data":[{"dst":"Bonjour."},{"dst":"Salut."}]}""", "fra",
        ))
    }

    @Test
    fun baiduFailuresAreReportedNotSwallowed() {
        assertThrows(IllegalStateException::class.java) {
            parseBaiduTranslation("<html>captcha</html>", "zh")
        }
        val withCode = assertThrows(IllegalStateException::class.java) {
            parseBaiduTranslation("""{"error":997}""", "zh")
        }
        assertTrue(withCode.message.orEmpty().contains("997"))
        assertThrows(IllegalStateException::class.java) {
            parseBaiduTranslation("""{"data":[{"dst":"  "}]}""", "zh")
        }
    }
}
