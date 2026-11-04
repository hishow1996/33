package io.translatetube.local.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PikpakPassCodeTest {

    @Test
    fun extractsCodeFromPastedShareText() {
        assertEquals(
            "bubq",
            PikpakShare.extractPassCode(
                "https://mypikpak.com/s/VP4RyuTsvMjm3ieG0VMRXElro2\n密码: bubq\n22\n复制链接后打开 PikPak App，保存当前文件",
            ),
        )
    }

    @Test
    fun extractsCodeFromColonAndLabelVariants() {
        assertEquals("ab12", PikpakShare.extractPassCode("链接……\n提取码：ab12"))
        assertEquals("cd34", PikpakShare.extractPassCode("口令 cd34，速存"))
        assertEquals("ef56", PikpakShare.extractPassCode("pass code: ef56"))
        assertEquals("gh78", PikpakShare.extractPassCode("code：gh78"))
    }

    @Test
    fun extractsCodeFromUrlQueryParameters() {
        assertEquals("zz9", PikpakShare.extractPassCode("https://mypikpak.com/s/AbCdEf123456?p=zz9"))
        assertEquals("y8x", PikpakShare.extractPassCode("https://mypikpak.com/s/AbCdEf123456?code=y8x"))
        assertEquals("w7v", PikpakShare.extractPassCode("https://mypikpak.com/s/AbCdEf123456?pass_code=w7v"))
    }

    @Test
    fun returnsNullWhenNoCodePresent() {
        assertNull(PikpakShare.extractPassCode("https://mypikpak.com/s/VP4RyuTsvMjm3ieG0VMRXElro2"))
        assertNull(PikpakShare.extractPassCode("普通文本没有链接和密码"))
        assertNull(PikpakShare.extractPassCode(""))
    }

    @Test
    fun doesNotTreatTheWordCodeAloneAsCode() {
        // 「打开 PikPak App」里没有分隔符跟码值，不应误提取
        assertNull(
            PikpakShare.extractPassCode("复制链接后打开 PikPak App，保存当前文件"),
        )
    }
}
