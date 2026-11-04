package io.translatetube.local.web

import io.translatetube.local.web.BilibiliVideoRefPolicy.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliVideoRefPolicyTest {
    @Test
    fun keepsBvidCaseExactly() {
        // The old code upper-cased the whole id, producing a different (invalid) BV number.
        val ref = BilibiliVideoRefPolicy.parse("https://www.bilibili.com/video/BV1GJ411x7h7/")!!
        assertEquals("BV1GJ411x7h7", ref.bvid)
        assertNull(ref.aid)
        assertNull(ref.page)
    }

    @Test
    fun normalizesOnlyThePrefixOfALowercaseBvPrefix() {
        val ref = BilibiliVideoRefPolicy.parse("https://m.bilibili.com/video/bv1GJ411x7h7")!!
        assertEquals("BV1GJ411x7h7", ref.bvid)
    }

    @Test
    fun readsPartNumberFromQuery() {
        val ref = BilibiliVideoRefPolicy.parse(
            "https://www.bilibili.com/video/BV1GJ411x7h7/?share_source=copy_web&p=3&vd_source=abc",
        )!!
        assertEquals("BV1GJ411x7h7", ref.bvid)
        assertEquals(3, ref.page)
    }

    @Test
    fun ignoresInvalidPartNumbers() {
        assertNull(BilibiliVideoRefPolicy.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=0")!!.page)
        assertNull(BilibiliVideoRefPolicy.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=abc")!!.page)
        assertNull(BilibiliVideoRefPolicy.parse("https://www.bilibili.com/video/BV1GJ411x7h7?q=2")!!.page)
    }

    @Test
    fun parsesAvNumbersAndDeepLinkConvertedUrls() {
        val av = BilibiliVideoRefPolicy.parse("https://www.bilibili.com/video/av170001?p=2")!!
        assertNull(av.bvid)
        assertEquals(170001L, av.aid)
        assertEquals(2, av.page)
        val converted = BilibiliVideoRefPolicy.parse("https://m.bilibili.com/video/BV1GJ411x7h7?p=4")!!
        assertEquals(4, converted.page)
    }

    @Test
    fun rejectsNonVideoPages() {
        assertNull(BilibiliVideoRefPolicy.parse("https://www.bilibili.com/"))
        assertNull(BilibiliVideoRefPolicy.parse("https://space.bilibili.com/12345"))
        assertNull(BilibiliVideoRefPolicy.parse("https://www.bilibili.com/video/BV1GJ411x7h7extra"))
    }

    @Test
    fun recognisesBothShortLinkHosts() {
        assertTrue(BilibiliVideoRefPolicy.isShortLinkHost("b23.tv"))
        assertTrue(BilibiliVideoRefPolicy.isShortLinkHost("BILI2233.CN"))
        assertFalse(BilibiliVideoRefPolicy.isShortLinkHost("www.bilibili.com"))
        assertFalse(BilibiliVideoRefPolicy.isShortLinkHost(null))
    }

    private val pages = listOf(
        PageInfo(cid = 11, page = 1, part = "开场", durationSec = 60),
        PageInfo(cid = 22, page = 2, part = "正片", durationSec = 600),
        PageInfo(cid = 33, page = 3, part = "", durationSec = 120),
    )

    @Test
    fun picksRequestedPartOrFallsBackToFirst() {
        assertEquals(22L, BilibiliVideoRefPolicy.pickPage(pages, 2)!!.cid)
        assertEquals(11L, BilibiliVideoRefPolicy.pickPage(pages, null)!!.cid)
        assertEquals(11L, BilibiliVideoRefPolicy.pickPage(pages, 99)!!.cid)
        assertNull(BilibiliVideoRefPolicy.pickPage(emptyList(), 2))
        assertNull(BilibiliVideoRefPolicy.pickPage(listOf(PageInfo(0, 1, "", 0)), 1))
    }

    @Test
    fun titleMentionsPartOnlyForLaterPartsOfMultiPartVideos() {
        assertEquals("标题", BilibiliVideoRefPolicy.displayTitle("标题", pages, pages[0]))
        assertEquals("标题 · P2 正片", BilibiliVideoRefPolicy.displayTitle("标题", pages, pages[1]))
        assertEquals("标题 · P3", BilibiliVideoRefPolicy.displayTitle("标题", pages, pages[2]))
        val single = listOf(PageInfo(1, 1, "x", 10))
        assertEquals("标题", BilibiliVideoRefPolicy.displayTitle("标题", single, single[0]))
    }
}
