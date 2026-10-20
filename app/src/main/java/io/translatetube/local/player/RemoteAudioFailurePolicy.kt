package io.translatetube.local.player

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

    fun classify(failure: Throwable): Diagnosis {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val text = chain.joinToString(" ") { it.javaClass.name + " " + it.message.orEmpty() }
            .lowercase(Locale.ROOT)
        fun has(vararg terms: String) = terms.any { text.contains(it) }
        fun http(code: Int) = Regex("""\bhttp\s*[:=]?\s*$code\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

        return when {
            chain.any { it is java.util.concurrent.CancellationException } ->
                Diagnosis("cancelled", "任务已取消", "任务被取消或播放会话已切换", "不要按网络故障处理；确认没有继续写入缓存。")
            http(401) || http(403) || has("unauthorized", "forbidden", "访问被拒绝") ->
                Diagnosis("access_denied", "媒体地址访问被拒绝", "可能需要 Referer、Cookie、User-Agent，或签名已失效", "检查网页会话凭证和请求头；不要把完整 Cookie 或签名 URL 写入日志。")
            http(404) || http(410) || has("not found", "链接可能过期", "地址已过期", "资源已失效") ->
                Diagnosis("expired_or_missing", "媒体地址不存在或已过期", "临时媒体地址可能失效，或资源已被移除", "重新打开网页并重新检测媒体源，再立即提取。")
            http(429) || has("too many requests", "rate limit", "限流") ->
                Diagnosis("rate_limited", "请求受到站点限流", "请求频率过高或站点限制自动化访问", "减少重复检测，稍后重试并优先复用音轨缓存。")
            chain.any { it is SocketTimeoutException } || has("timed out", "timeout", "超时") ->
                Diagnosis("network_timeout", "媒体请求超时", "网络慢、服务器无响应或分段请求卡住", "检查网络和代理状态，记录处理阶段后重试。")
            chain.any { it is UnknownHostException || it is ConnectException || it is SSLException } ->
                Diagnosis("network_connection", "无法连接媒体服务器", "DNS、连接或 TLS 握手失败", "检查网络、DNS、代理及 HTTPS 证书。")
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
}
