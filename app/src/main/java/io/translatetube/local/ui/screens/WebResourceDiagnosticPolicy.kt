package io.translatetube.local.ui.screens

import java.net.URI
import java.util.Locale

internal data class WebResourceFailureClassification(
    val errorCodeName: String,
    val category: String,
    val explanation: String,
)

/**
 * Safe diagnostics for WebView resource failures. Never stores raw request headers or signed
 * query values; the URL identity is used only as input to a one-way issue-key hash.
 */
internal object WebResourceDiagnosticPolicy {
    val RECOVERABLE_ERROR_CODES: List<Int> = listOf(-1, -2, -6, -7, -8, -11)

    /**
     * Stable across short-lived query signature refreshes. Query parameter names are retained
     * only inside the one-way hash input; parameter values are deliberately excluded.
     */
    fun candidateIdentity(rawUrl: String): String = runCatching {
        val uri = URI(rawUrl)
        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val port = uri.port.takeIf { it >= 0 }?.toString().orEmpty()
        val path = uri.rawPath.orEmpty()
        val queryNames = uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("&")
        listOf(scheme, host, port, path, queryNames).joinToString("|")
    }.getOrElse {
        rawUrl.substringBefore('#').substringBefore('?')
    }

    fun describeRange(value: String?): String {
        val range = value?.trim().orEmpty()
        if (range.isBlank()) return "未提供"
        return when {
            Regex("""(?i)^bytes=\d+-\d+$""").matches(range) -> "固定字节区间"
            Regex("""(?i)^bytes=\d+-$""").matches(range) -> "从指定偏移到末尾"
            Regex("""(?i)^bytes=-\d+$""").matches(range) -> "文件尾部区间"
            else -> "已提供（格式未识别）"
        }
    }

    fun classifyFailure(
        errorCode: Int,
        description: String,
    ): WebResourceFailureClassification {
        val chromiumError = Regex("""(?i)net::(err_[a-z0-9_]+)""")
            .find(description)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase(Locale.ROOT)

        return when (errorCode) {
            -1 -> WebResourceFailureClassification(
                errorCodeName = "ERROR_UNKNOWN",
                category = if (chromiumError == "ERR_FAILED") {
                    "通用失败（Chromium ERR_FAILED）"
                } else {
                    "通用失败（原因未确定）"
                },
                explanation = if (chromiumError == "ERR_FAILED") {
                    "WebView 只返回通用 ERR_FAILED，单凭此错误不能区分 DNS、TCP、TLS、服务器故障或请求被取消。"
                } else {
                    "Android WebView 返回 ERROR_UNKNOWN，没有提供足以确定网络故障类型的信息。"
                },
            )
            -2 -> WebResourceFailureClassification(
                "ERROR_HOST_LOOKUP",
                "DNS/域名解析失败",
                "系统未能解析媒体资源主机名；需结合设备 DNS、代理 DNS 和域名状态检查。",
            )
            -3 -> WebResourceFailureClassification(
                "ERROR_UNSUPPORTED_AUTH_SCHEME",
                "不支持的身份验证协议",
                "请求遇到 WebView 不支持的身份验证协议。",
            )
            -4 -> WebResourceFailureClassification(
                "ERROR_AUTHENTICATION",
                "身份验证失败",
                "媒体服务器要求的身份验证未通过。",
            )
            -5 -> WebResourceFailureClassification(
                "ERROR_PROXY_AUTHENTICATION",
                "代理身份验证失败",
                "当前代理要求身份验证，但该请求未能通过代理认证。",
            )
            -6 -> WebResourceFailureClassification(
                "ERROR_CONNECT",
                "连接媒体服务器失败",
                "未能建立到媒体服务器的网络连接。",
            )
            -7 -> WebResourceFailureClassification(
                "ERROR_IO",
                "媒体请求 I/O 失败",
                "请求在传输过程中发生 I/O 错误或连接中断。",
            )
            -8 -> WebResourceFailureClassification(
                "ERROR_TIMEOUT",
                "网络请求超时",
                "媒体服务器或中间网络未能在超时时间内完成请求。",
            )
            -9 -> WebResourceFailureClassification(
                "ERROR_REDIRECT_LOOP",
                "重定向循环",
                "媒体请求在重定向过程中未能到达最终资源。",
            )
            -10 -> WebResourceFailureClassification(
                "ERROR_UNSUPPORTED_SCHEME",
                "不支持的资源协议",
                "媒体地址使用了 WebView 当前不支持的协议。",
            )
            -11 -> WebResourceFailureClassification(
                "ERROR_FAILED_SSL_HANDSHAKE",
                "TLS/证书握手失败",
                "与媒体服务器建立 HTTPS 安全连接时握手失败。",
            )
            -12 -> WebResourceFailureClassification(
                "ERROR_BAD_URL",
                "媒体地址格式无效",
                "WebView 判定请求地址格式不正确。",
            )
            -13 -> WebResourceFailureClassification(
                "ERROR_FILE",
                "本地文件访问失败",
                "WebView 访问本地文件资源时发生错误。",
            )
            -14 -> WebResourceFailureClassification(
                "ERROR_FILE_NOT_FOUND",
                "本地文件不存在",
                "请求的本地文件资源不存在。",
            )
            -15 -> WebResourceFailureClassification(
                "ERROR_TOO_MANY_REQUESTS",
                "请求数量过多",
                "WebView 或服务器拒绝了过多请求。",
            )
            else -> WebResourceFailureClassification(
                errorCodeName = "UNKNOWN_WEBVIEW_ERROR",
                category = "其他 WebView 请求错误",
                explanation = "WebView 返回了未映射的错误代码；不能仅凭该代码确定具体网络原因。",
            )
        }
    }
}
