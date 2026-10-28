package io.translatetube.local.yt

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Turns the English / host-name-only messages thrown while parsing a YouTube link into a reason
 * the user can act on. Before this, a private video or a dead network showed up as text such as
 * `www.youtube.com` or `Could not get ...` on the processing page.
 *
 * Extractor exception types are matched by simple class name so this file does not depend on
 * the extractor library (and stays unit-testable on the plain JVM).
 */
internal object YouTubeParseFailurePolicy {
    private const val ORIGINAL_ERROR_LIMIT = 120

    /** A short Chinese explanation, or null when the error is not one we recognise. */
    fun describeOrNull(error: Throwable): String? {
        val type = error.javaClass.simpleName
        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            type == "PrivateContentException" || "private video" in message ->
                "这是私享视频，只有作者授权的账号才能观看，无法解析。"
            type == "AgeRestrictedContentException" ||
                "age-restricted" in message || "confirm your age" in message ->
                "该视频有年龄限制，匿名无法解析。请在「网页视频」页打开 YouTube 并登录后再试。"
            type == "GeographicRestrictionException" ||
                "not available in your country" in message ||
                "not available in this country" in message ->
                "该视频在当前网络所在地区不可用，换一个网络或节点后再试。"
            type == "AccountTerminatedException" ->
                "该视频所属的频道已被关闭，无法解析。"
            type == "PaidContentException" || type == "YoutubeMusicPremiumContentException" ->
                "这是付费或会员专享内容，无法解析。"
            type == "ContentNotSupportedException" ->
                "暂不支持这种类型的 YouTube 内容（例如播放列表或频道页）。请打开具体的视频链接。"
            type == "ContentNotAvailableException" ->
                "该视频不可用，可能已被删除、下架或设为私有。"
            type == "ReCaptchaException" || "http 429" in message || "too many requests" in message ->
                "YouTube 提示请求过于频繁或需要验证，请等几分钟后重试，或换一个网络。"
            error is UnknownHostException ->
                "连不上 YouTube（域名解析失败）。请检查网络；使用代理时，youtube.com 和 googlevideo.com 都要走代理。"
            error is SocketTimeoutException || error is ConnectException ||
                error is NoRouteToHostException || error is SSLException ->
                "连接 YouTube 超时或被中断，请检查网络或代理后重试。"
            else -> null
        }
    }

    /**
     * The error to surface: the Chinese reason (keeping the original text, shortened, for
     * diagnosis) with the original as the cause. Unrecognised errors are returned unchanged.
     * An IOException stays an IOException so callers that treat I/O failures specially still do.
     */
    fun toUserFacing(error: Throwable): Throwable {
        val reason = describeOrNull(error) ?: return error
        // Already rewritten once (its text still contains the original markers): keep as is.
        if (error.message.orEmpty().startsWith(reason)) return error
        val original = error.message.orEmpty().trim().take(ORIGINAL_ERROR_LIMIT)
        val text = if (original.isBlank() || original == reason) reason else "$reason（原始错误：$original）"
        return if (error is IOException) IOException(text, error) else IllegalStateException(text, error)
    }
}
