package io.translatetube.local.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliDeepLinkPolicyTest {
    @Test
    fun prefersEmbeddedBilibiliWebUrlAndKeepsPageIndex() {
        val deepLink =
            "bilibili://video/117455779269453?page=0&open_app_url=" +
                "https%3A%2F%2Fm.bilibili.com%2Fvideo%2FBV1kgJq6fEmh%3Fp%3D2%26spm_id_from%3Dshare"
        assertEquals(
            "https://m.bilibili.com/video/BV1kgJq6fEmh?p=2",
            BilibiliDeepLinkPolicy.toWebUrl(deepLink),
        )
    }

    @Test
    fun convertsNumericVideoRouteToAidPage() {
        assertEquals(
            "https://m.bilibili.com/video/av117455779269453",
            BilibiliDeepLinkPolicy.toWebUrl("bilibili://video/117455779269453?page=0"),
        )
    }

    @Test
    fun ignoresUnrelatedSchemes() {
        assertNull(BilibiliDeepLinkPolicy.toWebUrl("https://www.bilibili.com/video/BV1kgJq6fEmh"))
        assertNull(BilibiliDeepLinkPolicy.toWebUrl("intent://video/BV1kgJq6fEmh#Intent;end"))
    }

    @Test
    fun rejectsUntrustedEmbeddedWebUrls() {
        val deepLink =
            "bilibili://video/unknown?open_app_url=" +
                "https%3A%2F%2Fevil.example%2Fvideo%2FBV1kgJq6fEmh"
        assertNull(BilibiliDeepLinkPolicy.toWebUrl(deepLink))
    }
}
