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

    /** Summarize request headers without retaining cookie, authorization, or signed values. */
    fun summarizeRequestHeaders(
        headers: Map<String, String>,
        pageUrl: String,
    ): Map<String, String> {
        fun get(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

        val refererHost = hostFromUrl(get("Referer")).orEmpty()
        val originValue = get("Origin").orEmpty()
        val originHost = if (originValue.equals("null", ignoreCase = true)) {
            "null"
        } else {
            hostFromUrl(originValue).orEmpty()
        }
        val pageHost = hostFromUrl(pageUrl).orEmpty()
        val range = get("Range")

        return linkedMapOf(
            "Referer 是否存在" to (get("Referer") != null).toString(),
            "Referer 主机名" to refererHost.ifBlank { "未提供" },
            "Referer 与页面主机是否一致" to (
                refererHost.isNotBlank() && pageHost.isNotBlank() &&
                    refererHost.equals(pageHost, ignoreCase = true)
                ).toString(),
            "Origin 是否存在" to (get("Origin") != null).toString(),
            "Origin 主机名" to originHost.ifBlank { "未提供" },
            "Origin 与页面主机是否一致" to (
                originHost.isNotBlank() && pageHost.isNotBlank() &&
                    originHost.equals(pageHost, ignoreCase = true)
                ).toString(),
            "请求头集合是否显式包含 Cookie" to (get("Cookie") != null).toString(),
            "请求头集合是否显式包含 Authorization" to (get("Authorization") != null).toString(),
            "User-Agent 是否存在" to (get("User-Agent") != null).toString(),
            "Accept 是否存在" to (get("Accept") != null).toString(),
            "Range 是否存在" to (range != null).toString(),
            "Range 类型" to describeRange(range),
            "Sec-Fetch-Site" to safeToken(
                get("Sec-Fetch-Site"),
                setOf("same-origin", "same-site", "cross-site", "none"),
            ),
            "Sec-Fetch-Mode" to safeToken(
                get("Sec-Fetch-Mode"),
                setOf("cors", "no-cors", "same-origin", "navigate", "websocket"),
            ),
            "Sec-Fetch-Dest" to safeToken(
                get("Sec-Fetch-Dest"),
                setOf("audio", "video", "empty", "document", "image", "script", "style"),
            ),
        )
    }

    /** Only recognized cache directives and numeric ages are retained. */
    fun summarizeCacheControl(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return "未提供"
        val allowed = setOf(
            "public", "private", "no-cache", "no-store", "no-transform",
            "must-revalidate", "proxy-revalidate", "immutable",
        )
        val values = raw.split(',')
            .mapNotNull { directive ->
                val part = directive.trim().trim('"')
                val name = part.substringBefore('=').trim().lowercase(Locale.ROOT)
                when {
                    name in allowed -> name
                    name in setOf("max-age", "s-maxage", "stale-while-revalidate", "stale-if-error") -> {
                        val age = part.substringAfter('=', "").trim().trim('"').toLongOrNull()
                        if (age != null && age >= 0L && age <= 315360000L) "$name=$age" else null
                    }
                    else -> null
                }
            }
            .distinct()
            .take(8)
        return values.joinToString(", ").ifBlank { "已提供（未识别的策略）" }
    }

    /** Server software is mapped to a short allowlist; arbitrary header text/version is not saved. */
    fun summarizeServer(value: String?): String {
        val raw = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (raw.isBlank()) return "未提供"
        val known = listOf(
            "cloudflare", "nginx", "openresty", "apache", "envoy",
            "varnish", "microsoft-iis", "litespeed", "akamai", "caddy", "gws",
        )
        val detected = known.filter { raw.contains(it) }
        return detected.joinToString(", ").ifBlank { "已提供（未识别）" }
    }

    fun summarizeAuthScheme(value: String?): String {
        val scheme = value?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            ?: return "未提供"
        return when (scheme.lowercase(Locale.ROOT)) {
            "basic" -> "Basic"
            "bearer" -> "Bearer"
            "digest" -> "Digest"
            "negotiate" -> "Negotiate"
            "ntlm" -> "NTLM"
            else -> "其他/未识别"
        }
    }

    /** Origin is reduced to a category and host only; never store a complete URL. */
    fun summarizeAccessControlOrigin(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return "未提供"
        if (raw == "*") return "*"
        if (raw.equals("null", ignoreCase = true)) return "null"
        val host = hostFromUrl(raw)
        return host?.let { "指定来源（$it）" } ?: "已提供（未识别）"
    }

    fun sanitizeContentRange(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return "未提供"
        if (!raw.startsWith("bytes ", ignoreCase = true)) return "格式异常"
        val pair = raw.substringAfter(' ', "").split('/', limit = 2)
        if (pair.size != 2) return "格式异常"
        val rangePart = pair[0].trim()
        val totalPart = pair[1].trim()
        val validRange = if (rangePart == "*") true else {
            val separator = rangePart.indexOf('-')
            if (separator <= 0 || separator != rangePart.lastIndexOf('-')) false else {
                val start = rangePart.substring(0, separator).toLongOrNull()
                val end = rangePart.substring(separator + 1).toLongOrNull()
                start != null && end != null && start <= end
            }
        }
        val validTotal = totalPart == "*" || totalPart.toLongOrNull() != null
        return if (validRange && validTotal) raw.take(80) else "格式异常"
    }

    fun summarizeResponseHeaders(
        requestUrl: String,
        headers: Map<String, String>,
    ): Map<String, String> {
        fun get(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

        val length = get("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
        val acceptRanges = when (get("Accept-Ranges")?.trim()?.lowercase(Locale.ROOT)) {
            "bytes" -> "bytes"
            "none" -> "none"
            null -> "未提供"
            else -> "已提供（值未识别）"
        }
        val location = get("Location")
        val locationHost = location?.let { resolveHost(requestUrl, it) }.orEmpty()
        val varyAllowed = setOf(
            "accept", "accept-encoding", "origin", "referer", "cookie", "user-agent",
        )
        val vary = get("Vary")?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it == "*" || it in varyAllowed }
            ?.distinct()
            ?.joinToString(", ")
            ?.ifBlank { "已提供（含其他字段）" }
            ?: "未提供"
        val allowCredentials = when (get("Access-Control-Allow-Credentials")?.trim()?.lowercase(Locale.ROOT)) {
            "true" -> "true"
            "false" -> "false"
            null -> "未提供"
            else -> "已提供（值未识别）"
        }

        return linkedMapOf(
            "Content-Length（字节）" to (length?.toString() ?: "未提供或非数字"),
            "Content-Range" to sanitizeContentRange(get("Content-Range")),
            "Accept-Ranges" to acceptRanges,
            "Server 标识" to summarizeServer(get("Server")),
            "Cache-Control 策略" to summarizeCacheControl(get("Cache-Control")),
            "Location 是否存在" to (location != null).toString(),
            "Location 目标主机名" to locationHost.ifBlank {
                if (location == null) "未提供" else "无法解析"
            },
            "WWW-Authenticate 认证方案" to summarizeAuthScheme(get("WWW-Authenticate")),
            "Access-Control-Allow-Origin" to summarizeAccessControlOrigin(get("Access-Control-Allow-Origin")),
            "Access-Control-Allow-Credentials" to allowCredentials,
            "Vary" to vary,
            "ETag 是否存在" to (get("ETag") != null).toString(),
            "Last-Modified 是否存在" to (get("Last-Modified") != null).toString(),
        )
    }

    fun describeHttpReason(statusCode: Int, reasonPhrase: String?): String {
        val reason = reasonPhrase?.trim().orEmpty()
        if (reason.isNotBlank()) {
            val safe = reason.filter { it.isLetterOrDigit() || it in " ._-" }.take(80)
            if (safe.isNotBlank()) return safe
        }
        return "未提供（HTTP/2 响应可不包含原因短语；状态码 $statusCode）"
    }

    fun explainHttpFailure(statusCode: Int): String = when (statusCode) {
        401 -> "服务器要求身份验证或当前凭据未通过验证；需结合 WWW-Authenticate 方案判断。"
        403 -> "服务器明确拒绝访问。可能涉及权限、防盗链、签名或 CDN 访问策略，但 403 本身不能确定具体原因。"
        404 -> "服务器未找到该资源；可能是路径失效或资源已被移除。"
        410 -> "服务器明确表示资源已失效。"
        416 -> "服务器拒绝当前字节范围请求；需检查 Range 和 Content-Range。"
        429 -> "请求频率受到限制；应避免立即重复请求。"
        in 500..599 -> "媒体服务器或其上游服务返回服务端错误。"
        else -> "需结合响应头、候选源校验结果和实际播放请求进一步判断。"
    }

    private fun safeToken(value: String?, allowed: Set<String>): String {
        val raw = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (raw.isBlank()) return "未提供"
        return if (raw in allowed) raw else "其他/未识别"
    }

    private fun hostFromUrl(value: String?): String? = runCatching {
        URI(value?.trim().orEmpty()).host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun resolveHost(baseUrl: String, target: String): String? = runCatching {
        URI(baseUrl).resolve(target).host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    }.getOrNull()

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
