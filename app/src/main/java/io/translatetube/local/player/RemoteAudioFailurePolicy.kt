package io.translatetube.local.player

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import javax.net.ssl.SSLException

/** Classifies remote audio failures without persisting signed URLs or session credentials. */
internal object RemoteAudioFailurePolicy {
    data class Diagnosis(
        val code: String,
        val label: String,
        val likelyCause: String,
        val nextStep: String,
    )

    private val httpStatusRegex = Regex(
        """(?i)(?:HTTP(?:/\d+(?:\.\d+)?)?(?:\s+(?:Error|错误))?\s*|失败\s*[（(]?\s*|(?:status(?:\s+code)?|code)\s*[:=]\s*)(\d{3})"""
    )

    /**
     * A fresh page extraction can recover expired or temporarily rejected signed CDN URLs.
     * Keep this narrowly scoped: network/DNS/rate-limit/server failures must not trigger page retries.
     */
    fun shouldRetryWithFreshPageExtraction(failure: Throwable): Boolean =
        classify(failure).code in setOf("expired_or_missing", "access_denied")

    fun classify(failure: Throwable): Diagnosis {
        val chain = causeChain(failure)
        val statusCode = deepestHttpStatus(chain)
        val text = chain.joinToString(" ") { it.javaClass.name + " " + it.message.orEmpty() }
            .lowercase(Locale.ROOT)
        fun has(vararg terms: String) = terms.any { text.contains(it) }

        return when {
            chain.any { it is java.util.concurrent.CancellationException } ->
                Diagnosis("cancelled", "任务已取消", "任务被取消或播放会话已切换", "不要按网络故障处理；确认没有继续写入缓存。")
            statusCode == 401 || statusCode == 403 ->
                Diagnosis("access_denied", "媒体地址访问被拒绝", "可能需要 Referer、Cookie、User-Agent，或签名已失效", "检查网页会话凭证和请求头；不要把完整 Cookie 或签名 URL 写入日志。")
            statusCode == 404 || statusCode == 410 ->
                Diagnosis("expired_or_missing", "媒体地址不存在或已过期", "临时媒体地址可能失效，或资源已被移除", "重新打开网页并重新检测媒体源，再立即提取。")
            statusCode == 408 ->
                Diagnosis("network_timeout", "媒体请求超时", "媒体服务器未能及时完成请求", "检查网络和代理状态，恢复后重试。")
            statusCode == 429 ->
                Diagnosis("rate_limited", "请求受到站点限流", "请求频率过高或站点限制自动化访问", "减少重复检测，遵循 Retry-After，稍后重试并优先复用音轨缓存。")
            statusCode != null && statusCode in 500..599 ->
                Diagnosis("server_error", "媒体服务器暂时异常", "媒体服务器返回 HTTP $statusCode", "稍后重试；不要把服务器临时故障当作签名过期反复重新解析。")
            statusCode != null ->
                Diagnosis("http_error", "媒体服务器返回 HTTP 错误", "媒体服务器返回 HTTP $statusCode", "检查请求地址、请求头和站点响应；该状态不会触发过期链接刷新。")
            has("unauthorized", "forbidden", "访问被拒绝") ->
                Diagnosis("access_denied", "媒体地址访问被拒绝", "可能需要 Referer、Cookie、User-Agent，或签名已失效", "检查网页会话凭证和请求头；不要把完整 Cookie 或签名 URL 写入日志。")
            has("not found", "链接可能过期", "地址已过期", "资源已失效") ->
                Diagnosis("expired_or_missing", "媒体地址不存在或已过期", "临时媒体地址可能失效，或资源已被移除", "重新打开网页并重新检测媒体源，再立即提取。")
            has("too many requests", "rate limit", "限流") ->
                Diagnosis("rate_limited", "请求受到站点限流", "请求频率过高或站点限制自动化访问", "减少重复检测，稍后重试并优先复用音轨缓存。")
            chain.any { it is SocketTimeoutException } || has("timed out", "timeout", "超时") ->
                Diagnosis("network_timeout", "媒体请求超时", "网络慢、服务器无响应或分段请求卡住", "检查网络和代理状态，记录处理阶段后重试。")
            chain.any { it is UnknownHostException } ->
                Diagnosis("dns_resolution_failed", "DNS 解析失败", "无法解析媒体服务器域名，DNS 未返回可用 IP 地址", "检查当前网络的 DNS、代理或 VPN 路由；网络恢复后重试。")
            chain.any { it is ConnectException } ->
                Diagnosis("network_connection", "无法连接媒体服务器", "域名解析后仍无法建立网络连接", "检查移动网络、代理路由和媒体服务器连接状态。")
            chain.any { it is SSLException } ->
                Diagnosis("tls_handshake_failed", "HTTPS 安全连接失败", "TLS 握手或证书校验未能完成", "检查系统时间、证书链和 HTTPS 代理设置。")
            has("服务器返回了网页或 json", "登录页或错误文本", "text/html", "application/json", "challenge page") ->
                Diagnosis("not_media_payload", "服务器返回的不是媒体数据", "实际响应可能是登录页、防盗链挑战页、网页或 JSON 错误", "检查 HTTP 状态、Content-Type、Referer、Cookie 和 User-Agent。")
            has("没有可提取的音频轨", "未找到音频轨", "无音频源", "no audio track") ->
                Diagnosis("no_audio_track", "媒体容器中没有可用音轨", "源可能是纯视频流、错误媒体源，或容器轨道未被系统识别", "重新选择媒体源，并核对实际轨道清单。")
            has("加密会话密钥", "使用加密密钥", "drm", "加密的 hls", "动态清单", "直播或尚未结束") ->
                Diagnosis("protected_or_live_stream", "媒体受保护或不是完整点播流", "HLS 加密、DRM，或直播/动态清单", "仅处理受支持且未加密的媒体；不绕过 DRM。")
            has("无法安全封装网页音频编码", "没有发现可用的音频解码器", "unsupported audio", "unsupported codec", "无法安全封装", "muxer", "解码器") ->
                Diagnosis("unsupported_audio_format", "音频编码或封装不兼容", "当前设备或处理路径无法安全解码/封装所选音轨", "核对音频 MIME、采样率、声道数和解码器检测结果，再尝试其他源。")
            has("分段下载失败", "音频缓存为空", "音轨为空", "响应为空", "下载长度不完整", "没有生成唯一的完整音频文件", "不存在或为空") ->
                Diagnosis("incomplete_media", "媒体下载结果不完整", "下载中断、片段缺失或缓存为空/不完整", "检查下载字节、片段序号和 Content-Length。")
            chain.any { it is IOException } ->
                Diagnosis("media_io_failure", "媒体读取或写入失败", "网络、容器读取或缓存写入过程中出现 I/O 错误", "结合处理阶段、源类型和根因异常继续排查。")
            else ->
                Diagnosis("unknown", "未分类的音轨处理失败", "异常没有匹配已有故障特征", "保留异常类型、根因和轨道摘要，再依据日志补充分类。")
        }
    }

    private fun deepestHttpStatus(chain: List<Throwable>): Int? {
        for (cause in chain.asReversed()) {
            val status = httpStatusRegex.findAll(cause.message.orEmpty())
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (status != null) return status
        }
        return null
    }

    /** Throwable causes are normally linear, but hand-built wrappers can form cycles. */
    private fun causeChain(failure: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = ArrayList<Throwable>()
        var current: Throwable? = failure
        while (current != null && result.size < 8 && seen.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }
}
