package io.translatetube.local.yt

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeParseFailurePolicyTest {
    // Same simple names as the extractor's exception classes.
    private class PrivateContentException(message: String) : Exception(message)
    private class AgeRestrictedContentException(message: String) : Exception(message)
    private class GeographicRestrictionException(message: String) : Exception(message)
    private class ContentNotAvailableException(message: String) : Exception(message)

    @Test
    fun describesExtractorContentErrorsByType() {
        assertTrue(YouTubeParseFailurePolicy.describeOrNull(PrivateContentException("x"))!!.contains("私享"))
        assertTrue(YouTubeParseFailurePolicy.describeOrNull(AgeRestrictedContentException("x"))!!.contains("年龄"))
        assertTrue(YouTubeParseFailurePolicy.describeOrNull(GeographicRestrictionException("x"))!!.contains("地区"))
        assertTrue(YouTubeParseFailurePolicy.describeOrNull(ContentNotAvailableException("x"))!!.contains("不可用"))
    }

    @Test
    fun describesByMessageWhenTypeIsGeneric() {
        assertTrue(
            YouTubeParseFailurePolicy.describeOrNull(
                IllegalStateException("This age-restricted video cannot be watched anonymously"),
            )!!.contains("年龄"),
        )
        assertTrue(
            YouTubeParseFailurePolicy.describeOrNull(IOException("YouTube 返回 HTTP 429：请求过于频繁"))!!
                .contains("频繁"),
        )
    }

    @Test
    fun describesNetworkFailuresInsteadOfShowingBareHostName() {
        val dns = YouTubeParseFailurePolicy.describeOrNull(UnknownHostException("www.youtube.com"))!!
        assertTrue(dns.contains("googlevideo.com"))
        assertTrue(YouTubeParseFailurePolicy.describeOrNull(SocketTimeoutException("timeout"))!!.contains("超时"))
    }

    @Test
    fun leavesUnknownErrorsUntouched() {
        val error = IllegalStateException("something else")
        assertNull(YouTubeParseFailurePolicy.describeOrNull(error))
        assertSame(error, YouTubeParseFailurePolicy.toUserFacing(error))
    }

    @Test
    fun toUserFacingKeepsOriginalTextAndExceptionKind() {
        val io = YouTubeParseFailurePolicy.toUserFacing(UnknownHostException("www.youtube.com"))
        assertTrue(io is IOException)
        assertTrue(io.message!!.contains("原始错误：www.youtube.com"))
        assertTrue(io.cause is UnknownHostException)

        val other = YouTubeParseFailurePolicy.toUserFacing(PrivateContentException("This video is private"))
        assertTrue(other is IllegalStateException)
        assertTrue(other.message!!.contains("This video is private"))
    }

    @Test
    fun toUserFacingIsIdempotent() {
        val once = YouTubeParseFailurePolicy.toUserFacing(UnknownHostException("www.youtube.com"))
        val twice = YouTubeParseFailurePolicy.toUserFacing(once)
        assertSame(once, twice)
        assertEquals(1, Regex("原始错误").findAll(twice.message!!).count())
    }

    @Test
    fun longOriginalMessageIsShortened() {
        val wrapped = YouTubeParseFailurePolicy.toUserFacing(
            PrivateContentException("a".repeat(500)),
        )
        assertTrue(wrapped.message!!.length < 260)
    }
}
