package io.translatetube.local.translate

import io.translatetube.local.subs.SubLine
import java.util.Locale

/**
 * Produces bounded, local-only evidence for diagnosing subtitle translation failures.
 * Subtitle previews are intentionally short; credentials, endpoint URLs and request bodies are
 * never added here. AppLogStore applies its credential redaction again before writing the log.
 */
internal object TranslationFailureDiagnosticPolicy {
    private val httpStatusPatterns = listOf(
        Regex("""(?i)\bHTTP(?:/\d(?:\.\d)?)?\s*[:=]?\s*(\d{3})\b"""),
        Regex("""(?i)\bstatus(?:\s+code)?\s*[:=]\s*(\d{3})\b"""),
        Regex("""(?i)\bcode\s*[:=]\s*(\d{3})\b"""),
    )

    fun sourceType(videoKey: String): String = when {
        videoKey.startsWith("youtube:", ignoreCase = true) -> "YouTube"
        videoKey.startsWith("remote:", ignoreCase = true) -> "网页视频/远程媒体"
        videoKey.startsWith("webpage:", ignoreCase = true) -> "网页链接"
        videoKey.startsWith("local:", ignoreCase = true) -> "本地视频"
        else -> "导入字幕/其他"
    }

    /** Summarize provider payload shape without storing a full successful response or subtitle batch. */
    fun responseBodySummary(body: String, includeErrorPreview: Boolean = false): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "body=empty"
        if (trimmed.startsWith("data:", ignoreCase = true) || trimmed.lineSequence().any { it.startsWith("data:") }) {
            val dataLines = trimmed.lineSequence().filter { it.startsWith("data:") }.toList()
            val done = dataLines.any { it.substringAfter("data:").trim() == "[DONE]" }
            val jsonData = dataLines.count { line -> runCatching { org.json.JSONObject(line.substringAfter("data:").trim()) }.isSuccess }
            val errorEvent = if (includeErrorPreview) {
                dataLines.firstOrNull()?.let { safeSnippet(it, 160) }.orEmpty()
            } else {
                "成功流正文不采集"
            }
            return "format=sse; chars=${trimmed.length}; dataEvents=${dataLines.size}; jsonEvents=$jsonData; terminalDone=$done; errorEvent=$errorEvent"
        }
        val root = runCatching { org.json.JSONObject(trimmed) }.getOrNull()
            ?: return if (includeErrorPreview) {
                "format=non-json; chars=${trimmed.length}; errorPreview=${safeSnippet(trimmed, 140)}"
            } else {
                "format=non-json; chars=${trimmed.length}; bodyPreview=成功响应正文不采集"
            }
        val rootKeys = root.keys().asSequence().toList().sorted().take(24)
        val errorNode = when (val value = root.opt("error")) {
            is org.json.JSONObject -> value
            is String -> org.json.JSONObject().put("message", value)
            else -> root.optJSONObject("Response")?.optJSONObject("Error")
                ?: root.optJSONObject("Error")
                ?: root.optJSONObject("error_details")
                ?: root.takeIf {
                    includeErrorPreview && (it.has("message") || it.has("error_description") || it.has("code"))
                }
        }
        val code = errorNode?.opt("code")?.takeUnless { it == org.json.JSONObject.NULL }?.toString()
            ?: errorNode?.opt("error_code")?.takeUnless { it == org.json.JSONObject.NULL }?.toString()
            ?: root.opt("code")?.takeUnless { it == org.json.JSONObject.NULL }?.toString()
        val type = errorNode?.optString("type")?.takeIf { it.isNotBlank() }
            ?: errorNode?.optString("error_type")?.takeIf { it.isNotBlank() }
        val message = errorNode?.optString("message")?.takeIf { it.isNotBlank() }
            ?: errorNode?.optString("error_message")?.takeIf { it.isNotBlank() }
            ?: root.optString("error_description").takeIf { it.isNotBlank() }

        val counts = listOf("choices", "translations", "candidates", "data", "output", "results")
            .mapNotNull { key ->
                root.optJSONArray(key)?.let { "$key=${it.length()}" }
            }.joinToString(",")
        val choices = root.optJSONArray("choices")
        val choice = choices?.optJSONObject(0)
        val messageObject = choice?.optJSONObject("message")
        val contentShape = messageObject?.opt("content")?.let { value ->
            when (value) {
                is String -> "string:${value.length}"
                is org.json.JSONArray -> "array:${value.length()}"
                is org.json.JSONObject -> "object:${value.length()}"
                org.json.JSONObject.NULL -> "null"
                else -> value.javaClass.simpleName
            }
        } ?: "missing"
        return buildString {
            append("format=json; chars=").append(trimmed.length)
            append("; rootFields=").append(rootKeys.joinToString(","))
            if (counts.isNotBlank()) append("; arrays=").append(counts)
            if (choices != null) append("; firstFinishReason=").append(choice?.optString("finish_reason", "missing") ?: "missing")
            if (messageObject != null) append("; assistantContent=").append(contentShape)
            if (!code.isNullOrBlank()) append("; providerErrorCode=").append(safeSnippet(code, 80))
            if (!type.isNullOrBlank()) append("; providerErrorType=").append(safeSnippet(type, 80))
            if (!message.isNullOrBlank()) append("; providerErrorMessage=").append(safeFailureMessage(message))
        }
    }

    /** Read a provider-generated request/trace ID from an error JSON body when no header exists. */
    fun responseRequestId(body: String): String? {
        val root = runCatching { org.json.JSONObject(body.trim()) }.getOrNull() ?: return null
        val keys = listOf("request_id", "requestId", "request-id", "trace_id", "traceId", "correlation_id")
        val nested = listOf("error", "metadata", "meta", "response", "Response")
        for (key in keys) {
            root.opt(key)?.takeUnless { it == org.json.JSONObject.NULL }?.toString()
                ?.takeIf { it.isNotBlank() }?.let { return safeDiagnosticText(it, 100) }
        }
        for (parentKey in nested) {
            val parent = root.optJSONObject(parentKey) ?: continue
            for (key in keys) {
                parent.opt(key)?.takeUnless { it == org.json.JSONObject.NULL }?.toString()
                    ?.takeIf { it.isNotBlank() }?.let { return safeDiagnosticText(it, 100) }
            }
        }
        return null
    }

    fun safeDiagnosticText(value: String?, maxChars: Int = 240): String =
        if (value.isNullOrBlank()) "（空）" else safeFailureMessage(value).take(maxChars.coerceIn(16, 800))

    fun httpTransportDetails(failure: Throwable): String {
        val responses = generateSequence(failure) { it.cause }
            .take(12)
            .filterIsInstance<TranslationHttpException>()
            .mapIndexed { index, response ->
                buildString {
                    append("#").append(index + 1)
                    append(" status=").append(response.statusCode?.toString() ?: "unknown")
                    append(" requestId=").append(response.requestId.orEmpty().ifBlank { "未提供" })
                    append(" contentType=").append(response.contentType.orEmpty().ifBlank { "未提供" })
                    append(" bytes=").append(response.responseBytes?.toString() ?: "unknown")
                    append(" retryAfter=").append(response.retryAfterHeader.orEmpty().ifBlank { response.retryAfterMs?.toString() ?: "未提供" })
                    append(" rateLimit=").append(response.rateLimitHeaders.orEmpty().ifBlank { "未提供" })
                    append(" providerCode=").append(response.providerErrorCode.orEmpty().ifBlank { "未提供" })
                    append(" providerType=").append(response.providerErrorType.orEmpty().ifBlank { "未提供" })
                    append(" providerMessage=").append(safeDiagnosticText(response.providerErrorMessage, 180))
                    append(" summary=").append(response.responseSummary.orEmpty().take(600))
                }
            }.toList()
        return responses.joinToString("\n").ifBlank { "异常链中没有结构化 HTTP 错误响应对象" }
    }

    private fun safeFailureMessage(message: String): String {
        var safe = message
        safe = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""")
            .replace(safe) { "${it.groupValues[1]} [REDACTED]" }
        safe = Regex("""(?i)\b(api[_-]?key|authorization|cookie|access[_-]?token|refresh[_-]?token|token|password|secret|client[_-]?secret|signature|sig)(\s*[=:]\s*)[^\s,;]+""")
            .replace(safe) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
        safe = Regex("""(?i)https?://[^\s"'<>]+""")
            .replace(safe) { match ->
                val authority = match.value.substringAfter("://").substringAfterLast('@')
                val host = authority.substringBefore('/').substringBefore('?').substringBefore('#')
                "https://$host/[URL path/query hidden]"
            }
        return safeSnippet(safe, 240)
    }

    fun causeChain(failure: Throwable, maxDepth: Int = 8): String =
        generateSequence(failure) { it.cause }
            .take(maxDepth.coerceIn(1, 16))
            .joinToString(" -> ") { cause ->
                cause.javaClass.name + cause.message?.takeIf { it.isNotBlank() }
                    ?.let { ": " + safeFailureMessage(it) }.orEmpty()
            }.take(2_400)

    fun httpStatus(failure: Throwable): String {
        val messages = generateSequence(failure) { it.cause }
            .take(8)
            .mapNotNull { it.message }
        for (message in messages) {
            for (pattern in httpStatusPatterns) {
                pattern.find(message)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return "未从异常文本中识别"
    }

    fun classify(failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause }
            .take(8)
            .toList()
        val status = httpStatus(failure).toIntOrNull()
        when (status) {
            400, 422 -> return "INVALID_REQUEST_OR_PAYLOAD"
            401, 403 -> return "AUTH_OR_PERMISSION"
            402 -> return "BILLING_OR_QUOTA"
            404 -> return "ENDPOINT_OR_MODEL_NOT_FOUND"
            408, 504 -> return "REQUEST_TIMEOUT"
            413 -> return "REQUEST_TOO_LARGE"
            415 -> return "UNSUPPORTED_CONTENT_TYPE"
            429 -> return "RATE_LIMITED"
        }
        if (status != null && status >= 500) return "PROVIDER_SERVER_ERROR"
        val types = chain.joinToString(" ") { it.javaClass.name }.lowercase(Locale.ROOT)
        val messages = chain.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        val responseFormatFailure =
            chain.filterIsInstance<IllegalStateException>().any {
                translationBatchFailureKind(it) == ApiSubtitleBatchFailureKind.RESPONSE_FORMAT
            } || messages.contains("响应格式不完整")
        if (responseFormatFailure) return "RESPONSE_FORMAT_OR_PARSE"
        return when {
            "unknownhostexception" in types || "no address associated" in messages -> "DNS_OR_HOST_UNREACHABLE"
            "sockettimeoutexception" in types || "timed out" in messages || "timeout" in messages -> "REQUEST_TIMEOUT"
            "sslexception" in types || "certpath" in messages || "handshake" in messages -> "TLS_OR_CERTIFICATE"
            "connectexception" in types || "connection refused" in messages || "failed to connect" in messages -> "CONNECTION_FAILED"
            "eofexception" in types || "unexpected end" in messages || "truncated" in messages -> "RESPONSE_TRUNCATED"
            "ioexception" in types -> "NETWORK_IO_FAILURE"
            "json" in types || "malformed" in messages || "parse" in messages -> "RESPONSE_FORMAT_OR_PARSE"
            "outofmemory" in types -> "MEMORY_LIMIT"
            else -> "ENGINE_OR_UNKNOWN_FAILURE"
        }
    }

    fun recommendedChecks(category: String): String = when (category) {
        "INVALID_REQUEST_OR_PAYLOAD" -> "检查请求 JSON 字段、消息角色、语言参数、模型专属参数和批次输入结构；根据服务商错误体修复请求。"
        "AUTH_OR_PERMISSION" -> "核对 API Key 是否存在、有效、具备推理权限；检查请求头和账号额度，不要记录或分享密钥。"
        "BILLING_OR_QUOTA" -> "核对服务商余额、套餐、免费额度和项目权限；不要把额度不足误判成译文质量错误。"
        "ENDPOINT_OR_MODEL_NOT_FOUND" -> "核对服务商 Base URL、接口路径与模型 ID 是否属于同一服务商；确认模型部署/推理路由真实可用。"
        "REQUEST_TIMEOUT" -> "检查网络/VPN、服务商响应时间、客户端超时设置；确认超时后是否需要安全重试。"
        "REQUEST_TOO_LARGE" -> "检查批次大小、单条字幕长度和服务商 token 限制；应拆小批次而不是丢弃字幕行。"
        "UNSUPPORTED_CONTENT_TYPE" -> "核对请求 Content-Type、Accept、SSE/JSON 模式和服务商实际要求的响应格式。"
        "RATE_LIMITED" -> "核对服务商限流、并发数和配额；检查 Retry-After 与退避策略。"
        "PROVIDER_SERVER_ERROR" -> "检查服务商 5xx 响应、请求 ID 与服务状态；确认错误响应解析没有吞掉服务端原因。"
        "DNS_OR_HOST_UNREACHABLE" -> "检查域名解析、代理/VPN、DNS 与网络切换；确认请求实际访问的是配置的服务主机。"
        "TLS_OR_CERTIFICATE" -> "检查 HTTPS 证书链、系统时间、TLS 握手和 Android 网络安全配置。"
        "CONNECTION_FAILED" -> "检查服务主机/端口可达性、代理配置及连接池复用；确认服务没有拒绝连接。"
        "RESPONSE_TRUNCATED" -> "检查响应流是否提前关闭、SSE 事件是否读完整、JSON 结束标记及连接重置；不能把截断结果当作成功。"
        "NETWORK_IO_FAILURE" -> "检查网络连接中断、读取流失败和代理/VPN 状态；核对失败发生在请求发送还是响应读取阶段。"
        "RESPONSE_FORMAT_OR_PARSE" -> "检查模型返回格式、JSON/SSE 解码和批次行数映射；不能按位置可靠对应时必须保留为缺失行。"
        "MEMORY_LIMIT" -> "检查本地模型内存峰值、输入长度、线程数和批次大小；未通过校验的字幕必须保持失败状态。"
        else -> "沿异常根因链检查引擎初始化、请求构造、响应解析和译文质量校验；定位到具体文件/方法后再修改。"
    }

    /** Short excerpt for the on-device log; removes control characters and never stores a full cue. */
    fun safeSnippet(value: String?, maxChars: Int = 96): String {
        if (value.isNullOrBlank()) return "（空）"
        var normalized = value
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        normalized = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
            .replace(normalized) { "${it.groupValues[1]} [REDACTED]" }
        normalized = Regex("(?i)\\b(api[_-]?key|authorization|cookie|access[_-]?token|refresh[_-]?token|token|password|secret|client[_-]?secret|signature|sig)(\\s*[=:]\\s*)[^\\s,;]+")
            .replace(normalized) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
        normalized = Regex("""(?i)https?://[^\s"'<>]+""")
            .replace(normalized) { match ->
                val authority = match.value.substringAfter("://").substringAfterLast('@')
                val host = authority.substringBefore('/').substringBefore('?').substringBefore('#')
                "https://$host/[URL path/query hidden]"
            }
        if (normalized.isEmpty()) return "（空）"
        val limit = maxChars.coerceIn(16, 240)
        return if (normalized.length > limit) normalized.take(limit) + "…" else normalized
    }

    /**
     * Includes a few original subtitle examples and their timestamps to identify row alignment
     * and source-language issues. Output remains bounded and is stored only in local app logs.
     */
    fun describeSubtitleRows(
        lines: List<SubLine>,
        indices: List<Int>,
        translatedByIndex: Map<Int, String?> = emptyMap(),
        reasonByIndex: Map<Int, String> = emptyMap(),
        maxRows: Int = 8,
    ): String {
        if (indices.isEmpty()) return "无"
        val unique = indices.distinct()
        val limit = maxRows.coerceIn(1, 12)
        val shown = unique.take(limit).mapNotNull { index ->
            val line = lines.getOrNull(index) ?: return@mapNotNull null
            val translated = translatedByIndex[index] ?: line.zh
            val reason = reasonByIndex[index].orEmpty().ifBlank { "未通过翻译阶段校验/未返回有效译文" }
            "第 ${index + 1} 行（索引=${index}，时间=${line.startMs}..${line.endMs} ms，原文字符=${line.text.length}，译文字符=${translated?.length ?: 0}）" +
                "；原因=${safeSnippet(reason, 100)}；原文片段=${safeSnippet(line.text)}；译文片段=${safeSnippet(translated)}"
        }
        val omitted = (unique.size - shown.size).coerceAtLeast(0)
        return shown.joinToString(" || ") +
            if (omitted > 0) " || 另有 $omitted 行未展开（问题行总数 ${unique.size}）" else ""
    }

    fun summarizeIndices(indices: List<Int>, maxItems: Int = 80): String {
        if (indices.isEmpty()) return "无"
        val unique = indices.distinct()
        val limit = maxItems.coerceIn(1, 200)
        val shown = unique.take(limit).joinToString(",") { (it + 1).toString() }
        val omitted = unique.size - minOf(unique.size, limit)
        return shown + if (omitted > 0) "…（另有 $omitted 行）" else ""
    }
}
