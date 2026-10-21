package io.translatetube.local.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTextParserTest {
    @Test
    fun extractsBilibiliShortLinkFromCopiedShareCardText() {
        assertEquals(
            "https://b23.tv/LfQ2WWb",
            SharedLinkTextParser.extractFirstHttpUrl(
                "【韩国女生第一次来上海｜竟然劳斯莱斯接送？上海真的让人充满惊喜…-哔哩哔哩】 https://b23.tv/LfQ2WWb",
            ),
        )
    }

    @Test
    fun extractsFirstLinkFromMultiLineDescriptionAndTrimsChinesePunctuation() {
        assertEquals(
            "https://example.com/watch?id=123",
            SharedLinkTextParser.extractFirstHttpUrl(
                "视频标题\n简介内容\nhttps://example.com/watch?id=123，更多介绍",
            ),
        )
    }

    @Test
    fun supportsUrlsWithQueryParameters() {
        assertEquals(
            "https://m.bilibili.com/video/BV1kgJq6fEmh?p=2&from=share",
            SharedLinkTextParser.extractFirstHttpUrl(
                "【标题】 https://m.bilibili.com/video/BV1kgJq6fEmh?p=2&from=share",
            ),
        )
    }

    @Test
    fun extractsUrlsFromOtherWebsiteShareTextsToo() {
        assertEquals(
            "https://www.youtube.com/watch?v=abcdefghijk&feature=share",
            SharedLinkTextParser.extractFirstHttpUrl(
                "【旅行视频】复制打开 YouTube，看看这段精彩内容！ https://www.youtube.com/watch?v=abcdefghijk&feature=share。",
            ),
        )
        assertEquals(
            "https://www.douyin.com/video/123456789",
            SharedLinkTextParser.extractFirstHttpUrl(
                "短视频标题\n作者简介\nhttps://www.douyin.com/video/123456789，欢迎观看",
            ),
        )
    }

    @Test
    fun returnsNullWhenTextContainsNoHttpUrl() {
        assertNull(SharedLinkTextParser.extractFirstHttpUrl("没有链接的文本 bilibili://video/123"))
        assertNull(SharedLinkTextParser.extractFirstHttpUrl(""))
    }
}
