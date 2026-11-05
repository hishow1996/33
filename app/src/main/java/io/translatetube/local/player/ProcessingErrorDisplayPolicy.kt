package io.translatetube.local.player

import java.util.Locale

/**
 * Converts pipeline failures to concise, stage-specific UI copy.
 *
 * The original Throwable, cause chain and detailed diagnostics remain in AppLogStore;
 * this policy deliberately never returns raw exception messages or host names to the UI.
 */
internal object ProcessingErrorDisplayPolicy {
    private enum class VideoSourceKind { YOUTUBE, WEB, LOCAL, UNKNOWN }

    private val httpStatusPattern = Regex(
        """(?:http(?:/[0-9.]+)?\s*(?:error\s*)?|(?:status|response(?:\s+status)?|code)[_\s-]*(?:code)?\s*[:=]\s*|["'](?:status|code)["']\s*:\s*|响应(?:状态)?码\s*[:：=]\s*)(\d{3})""",
        RegexOption.IGNORE_CASE,
    )
    private val subtitleIndexPattern = Regex(
        """(?:第\s*(\d+)\s*条\s*字幕|字幕\s*#?\s*(\d+)|(?:cue|subtitle)\s*#?\s*(\d+))""",
        RegexOption.IGNORE_CASE,
    )

    fun resolvePhase(phase: Phase?, progress: String?, error: String?): Phase? {
        if (phase.isProcessingPhase()) return phase
        return phaseFromStageText(progress.orEmpty())
            ?: phaseFromStageText(error.orEmpty())
    }

    fun canResumeExistingWork(
        phase: Phase?,
        progress: String?,
        error: String?,
        hasLines: Boolean,
        sourceLanguage: String?,
    ): Boolean {
        if (!hasLines || sourceLanguage.isNullOrBlank()) return false
        val resolved = resolvePhase(phase, progress, error) ?: return false
        // A parser failure must reacquire the source. Unknown failures are deliberately not resumed.
        return resolved != Phase.PARSING
    }

    fun title(phase: Phase?, progress: String? = null, error: String? = null): String =
        when (resolvePhase(phase, progress, error)) {
            Phase.PARSING -> "视频解析失败"
            Phase.DOWNLOADING_AUDIO -> "音频提取失败"
            Phase.TRANSCRIBING -> "语音转写失败"
            Phase.TRANSLATING -> "字幕翻译失败"
            Phase.SYNTHESIZING -> "配音合成失败"
            else -> "视频加载失败"
        }

    fun retryLabel(
        phase: Phase?,
        progress: String? = null,
        error: String? = null,
    ): String = if (resolvePhase(phase, progress, error) == Phase.PARSING) {
        "重新解析"
    } else {
        "重试当前处理"
    }

    fun message(
        sessionKey: String,
        phase: Phase?,
        progress: String?,
        error: String?,
    ): String {
        val raw = listOfNotNull(progress, error)
            .joinToString(" ")
            .trim()
            .lowercase(Locale.ROOT)
        val source = when {
            sessionKey.startsWith("youtube:", ignoreCase = true) -> VideoSourceKind.YOUTUBE
            sessionKey.startsWith("remote:", ignoreCase = true) ||
                sessionKey.startsWith("webpage:", ignoreCase = true) -> VideoSourceKind.WEB
            sessionKey.startsWith("local:", ignoreCase = true) -> VideoSourceKind.LOCAL
            else -> VideoSourceKind.UNKNOWN
        }
        val status = httpStatusPattern.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val subtitleIndex = subtitleIndexPattern.find(raw)?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.toIntOrNull()
        val resolvedPhase = resolvePhase(phase, progress, error)
        val detail = when (resolvedPhase) {
            Phase.PARSING -> parsingMessage(source, raw, status)
            Phase.DOWNLOADING_AUDIO -> audioMessage(raw, status)
            Phase.TRANSCRIBING -> transcriptionMessage(raw, status)
            Phase.TRANSLATING -> translationMessage(raw, status, subtitleIndex)
            Phase.SYNTHESIZING -> synthesisMessage(raw, status, subtitleIndex)
            else -> playbackMessage(raw)
        }
        val category = classifyErrorCategory(resolvedPhase, source, raw, status)
        return formatSingleSentence(category, detail)
    }

    /**
     * Keep the category and its explanation in one natural sentence.
     * Never add a visible category heading or a line break to player error hints.
     */
    private fun formatSingleSentence(category: String, detail: String): String {
        val cleanDetail = detail
            .replace(Regex("\\s*[\\r\\n]+\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimEnd('。', '！', '？', '.', '!', '?')
        return "$category：$cleanDetail。"
    }

    /** A short, stable category that can be scanned quickly on the player and processing cards. */
    fun errorCategory(
        sessionKey: String,
        phase: Phase?,
        progress: String?,
        error: String?,
    ): String {
        val raw = listOfNotNull(progress, error)
            .joinToString(" ")
            .trim()
            .lowercase(Locale.ROOT)
        val source = when {
            sessionKey.startsWith("youtube:", ignoreCase = true) -> VideoSourceKind.YOUTUBE
            sessionKey.startsWith("remote:", ignoreCase = true) ||
                sessionKey.startsWith("webpage:", ignoreCase = true) -> VideoSourceKind.WEB
            sessionKey.startsWith("local:", ignoreCase = true) -> VideoSourceKind.LOCAL
            else -> VideoSourceKind.UNKNOWN
        }
        val status = httpStatusPattern.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return classifyErrorCategory(resolvePhase(phase, progress, error), source, raw, status)
    }

    private fun classifyErrorCategory(
        phase: Phase?,
        source: VideoSourceKind,
        raw: String,
        status: Int?,
    ): String {
        return when {
            isQuotaFailure(raw) || status == 402 -> "额度或配额不足"
            status == 401 || status == 403 || "unauthorized" in raw ||
                "api key" in raw || "鉴权" in raw || "授权失败" in raw ->
                "授权失败"
            status == 408 -> "请求超时"
            status == 425 -> "服务暂不可用"
            status == 429 || "too many requests" in raw || "rate limit" in raw ->
                "请求频率受限"
            status != null && status in 500..599 -> "服务端异常"
            isNetworkFailure(raw) -> "网络连接异常"
            isModelFailure(raw) -> "模型不可用"
            else -> when (phase) {
                Phase.PARSING -> when {
                    "blob:" in raw || "mse" in raw || "drm" in raw ->
                        "网页媒体源不可直接访问"
                    source == VideoSourceKind.YOUTUBE &&
                        ("login_required" in raw || "sign in" in raw || "recaptcha" in raw ||
                            "captcha" in raw || "not a bot" in raw || "年龄验证" in raw ||
                            "年龄限制" in raw) -> "平台访问受限"
                    "permission" in raw || "denied" in raw || "权限" in raw -> "文件权限不足"
                    "no such file" in raw || "enoent" in raw || "文件不存在" in raw ||
                        "已移动" in raw -> "视频文件不存在"
                    "unsupported" in raw || "不支持" in raw || "格式无效" in raw ->
                        "视频格式不支持"
                    else -> "视频来源信息异常"
                }
                Phase.DOWNLOADING_AUDIO -> when {
                    status == 404 || status == 410 || "expired" in raw ||
                        "签名" in raw || "地址已失效" in raw -> "音轨地址失效或不存在"
                    "unsupported" in raw || "不支持" in raw -> "音轨格式不支持"
                    else -> "音轨获取异常"
                }
                Phase.TRANSCRIBING -> when {
                    "outofmemory" in raw || "out of memory" in raw || "内存不足" in raw ->
                        "设备内存不足"
                    "no speech" in raw || "empty transcript" in raw || "未识别到语音" in raw ->
                        "未检测到有效语音"
                    else -> "语音识别异常"
                }
                Phase.TRANSLATING -> when {
                    "empty response" in raw || "invalid response" in raw ||
                        "无效译文" in raw || "未生成译文" in raw -> "译文无效"
                    else -> "翻译结果异常"
                }
                Phase.SYNTHESIZING -> when {
                    "outofmemory" in raw || "out of memory" in raw || "内存不足" in raw ->
                        "设备内存不足"
                    "empty audio" in raw || "invalid audio" in raw || "音频数据为空" in raw ->
                        "音频数据无效"
                    else -> "音频合成或输出异常"
                }
                else -> "播放器异常"
            }
        }
    }

    private fun Phase?.isProcessingPhase(): Boolean = when (this) {
        Phase.PARSING,
        Phase.DOWNLOADING_AUDIO,
        Phase.TRANSCRIBING,
        Phase.TRANSLATING,
        Phase.SYNTHESIZING -> true
        else -> false
    }

    private fun phaseFromStageText(value: String): Phase? {
        val raw = value.lowercase(Locale.ROOT)
        fun hasAny(vararg needles: String): Boolean = needles.any(raw::contains)
        return when {
            hasAny(
                "视频解析", "解析视频", "解析网页", "解析器", "获取视频信息",
                "视频源解析", "blob:", "mse", "网页内嵌流", "yt-dlp", "newpipe", "extractor", "parsing video",
            ) -> Phase.PARSING
            hasAny(
                "音频提取", "音轨提取", "提取音频", "下载音轨", "音轨下载",
                "音频分片", "audio track", "extract audio", "audio extraction",
            ) -> Phase.DOWNLOADING_AUDIO
            hasAny(
                "语音转写", "转写阶段", "正在转写", "语音识别", "识别阶段",
                "speech recognition", "transcrib", "whisper", "sensevoice", " asr",
            ) -> Phase.TRANSCRIBING
            hasAny(
                "字幕翻译", "翻译阶段", "翻译请求", "中文译文", "无效译文",
                "translation", "translate",
            ) -> Phase.TRANSLATING
            hasAny(
                "配音合成", "合成配音", "语音合成", "音频输出", "系统 tts",
                "tts", "dubbing", "synthesis", "朗读配音",
            ) -> Phase.SYNTHESIZING
            else -> null
        }
    }

    private fun serviceStatusReason(status: Int?): String? = when {
        status == 408 -> "服务请求超时"
        status == 425 -> "服务暂时无法处理请求"
        status == 429 -> "服务请求过于频繁，请稍后重试"
        status != null && status in 500..599 -> "服务端暂时异常（HTTP $status），请稍后重试"
        else -> null
    }

    private fun isQuotaFailure(raw: String): Boolean =
        listOf(
            "quota exceeded", "insufficient quota", "billing", "payment required",
            "daily limit", "limit: 0", "额度不足", "配额不足", "余额不足",
        ).any { it in raw }

    private fun isModelFailure(raw: String): Boolean =
        listOf(
            "model not", "model unavailable", "model_unavailable", "model_not_found", "模型未就绪",
            "模型文件", "模型不存在", "模型不可用", "模型加载失败",
        ).any { it in raw }

    private fun parsingMessage(source: VideoSourceKind, raw: String, status: Int?): String =
        when (source) {
            VideoSourceKind.YOUTUBE -> {
                val reason = when {
                    "private video" in raw || "私享" in raw || "私有视频" in raw ->
                        "该视频为私享内容或当前账号无权访问"
                    "age-restricted" in raw || "confirm your age" in raw ||
                        "年龄限制" in raw || "年龄验证" in raw ->
                        "该视频需要年龄验证"
                    "login_required" in raw || "sign in" in raw || "login required" in raw ||
                        "recaptcha" in raw || "captcha" in raw || "not a bot" in raw ||
                        "需要登录" in raw || "需要验证" in raw ->
                        "平台要求登录或完成验证"
                    status == 429 || "too many requests" in raw || "rate limit" in raw ||
                        "请求过于频繁" in raw ->
                        "平台请求过于频繁，请稍后重试"
                    status == 404 || "contentnotavailable" in raw || "视频不可用" in raw ->
                        "视频不存在或已不可用"
                    status == 401 || status == 403 ->
                        "平台暂时拒绝访问视频资源"
                    serviceStatusReason(status) != null ->
                        "平台服务暂时异常，请稍后重试"
                    isNetworkFailure(raw) ->
                        "网络连接中断或超时"
                    "字幕" in raw && ("读取" in raw || "获取" in raw || "track" in raw) ->
                        "未能读取视频可用字幕信息"
                    else -> "未能获取视频信息"
                }
                "YouTube 视频解析失败：$reason"
            }
            VideoSourceKind.WEB -> {
                val reason = when {
                    "blob:" in raw || "mse" in raw || "drm" in raw ->
                        "网页使用内嵌媒体流，未检测到可访问的视频地址"
                    status == 401 || status == 403 ->
                        "网站拒绝访问视频资源，可能需要登录或授权"
                    status == 404 || status == 410 || "expired" in raw || "已失效" in raw ->
                        "视频资源不存在或视频地址已失效"
                    status == 429 || "too many requests" in raw ->
                        "视频网站请求过于频繁，请稍后重试"
                    serviceStatusReason(status) != null ->
                        "视频网站服务暂时异常，请稍后重试"
                    isNetworkFailure(raw) ->
                        "网页视频源请求中断或超时"
                    "no usable" in raw || "no media" in raw || "未检测到可访问" in raw ||
                        "没有候选源" in raw ->
                        "未检测到可访问的视频地址"
                    "yt-dlp" in raw || "extractor" in raw || "parser" in raw || "解析器" in raw ->
                        "备用解析器未能提取可播放视频信息"
                    else -> "未能从网页获取可播放的视频信息"
                }
                "网页视频解析失败：$reason"
            }
            VideoSourceKind.LOCAL -> {
                val reason = when {
                    "导入" in raw && "字幕" in raw && ("读取" in raw || "文件" in raw) ->
                        "未能读取导入的字幕文件"
                    "permission" in raw || "denied" in raw || "权限" in raw ->
                        "没有读取视频文件的权限"
                    "enoent" in raw || "no such file" in raw || "not found" in raw ||
                        "文件不存在" in raw || "已移动" in raw ->
                        "视频文件不存在或已移动"
                    "unsupported" in raw || "不支持" in raw || "格式无效" in raw ->
                        "视频格式或编码暂不受支持"
                    isNetworkFailure(raw) || "i/o" in raw ->
                        "读取视频文件时发生中断"
                    else -> "未能读取本地视频信息"
                }
                "本地视频解析失败：$reason"
            }
            VideoSourceKind.UNKNOWN -> "视频解析失败：未能获取视频信息"
        }

    private fun audioMessage(raw: String, status: Int?): String {
        val reason = when {
            status == 401 || status == 403 || status == 410 ||
                "expired" in raw || "签名" in raw || "地址已失效" in raw ->
                "音轨地址已失效或当前无权访问，需要重新获取"
            status == 404 -> "未找到可下载的视频音轨"
            serviceStatusReason(status) != null -> serviceStatusReason(status)!!
            isNetworkFailure(raw) -> "音轨下载中断或超时"
            "不支持" in raw || "unsupported" in raw -> "当前音轨格式暂不受支持"
            else -> "未能提取可用的视频音轨"
        }
        return "音频提取失败：$reason"
    }

    private fun transcriptionMessage(raw: String, status: Int?): String {
        val reason = when {
            status == 401 || status == 403 || "unauthorized" in raw || "api key" in raw ||
                "鉴权" in raw || "授权失败" in raw ->
                "语音识别服务配置或授权不可用"
            isQuotaFailure(raw) || status == 402 ->
                "语音识别服务额度或配额不足"
            serviceStatusReason(status) != null -> serviceStatusReason(status)!!
            "outofmemory" in raw || "out of memory" in raw || "内存不足" in raw ->
                "设备可用内存不足，未能完成语音识别"
            isModelFailure(raw) -> "当前语音识别引擎或模型未就绪"
            "no speech" in raw || "empty transcript" in raw || "未识别到语音" in raw ->
                "未识别到可用语音内容"
            isNetworkFailure(raw) -> "语音识别请求中断或超时"
            else -> "未能完成视频语音识别"
        }
        return "语音转写失败：$reason"
    }

    private fun translationMessage(raw: String, status: Int?, subtitleIndex: Int?): String {
        val reason = when {
            status == 401 || status == 403 || "unauthorized" in raw ||
                "api key" in raw || "鉴权" in raw || "授权失败" in raw ->
                "翻译服务配置或授权不可用"
            isQuotaFailure(raw) || status == 402 ->
                "翻译服务额度或配额不足"
            serviceStatusReason(status) != null -> serviceStatusReason(status)!!
            isNetworkFailure(raw) -> "翻译服务请求中断或超时"
            isModelFailure(raw) -> "所选翻译模型未就绪"
            "empty response" in raw || "invalid response" in raw ||
                "无效译文" in raw || "未生成译文" in raw ->
                "未能生成有效中文译文"
            else -> null
        }
        return when {
            subtitleIndex != null && reason != null ->
                "字幕翻译失败：第 $subtitleIndex 条字幕处理失败，$reason"
            subtitleIndex != null ->
                "字幕翻译失败：第 $subtitleIndex 条字幕未能生成有效译文"
            reason != null -> "字幕翻译失败：$reason"
            else -> "字幕翻译失败：未能生成有效中文译文"
        }
    }

    private fun synthesisMessage(raw: String, status: Int?, subtitleIndex: Int?): String {
        val reason = when {
            "outofmemory" in raw || "out of memory" in raw || "内存不足" in raw ->
                "设备可用内存不足"
            status == 401 || status == 403 || "unauthorized" in raw ||
                "api key" in raw || "鉴权" in raw || "授权失败" in raw ->
                "在线语音服务配置或授权不可用"
            isQuotaFailure(raw) || status == 402 ->
                "在线语音服务额度或配额不足"
            serviceStatusReason(status) != null -> serviceStatusReason(status)!!
            isModelFailure(raw) || "初始化失败" in raw || "initializ" in raw ->
                "所选语音引擎或模型未能正常启动"
            isNetworkFailure(raw) -> "在线语音服务请求中断或超时"
            "empty audio" in raw || "invalid audio" in raw || "音频数据为空" in raw ->
                "引擎未生成可播放的音频数据"
            else -> null
        }
        return when {
            subtitleIndex != null && reason != null ->
                "配音合成失败：第 $subtitleIndex 条字幕未能生成配音，$reason"
            subtitleIndex != null ->
                "配音合成失败：第 $subtitleIndex 条字幕未能生成配音"
            reason != null -> "配音合成失败：$reason"
            else -> "配音合成失败：未能生成有效配音音频"
        }
    }

    private fun playbackMessage(raw: String): String = when {
        "播放器服务启动失败" in raw || "player service" in raw || "播放器初始化失败" in raw ->
            "播放器启动失败：未能正常启动视频播放服务"
        else -> "视频加载失败：播放器未能正常加载视频"
    }

    private fun isNetworkFailure(raw: String): Boolean =
        listOf(
            "unknownhost", "unknown host", "sockettimeout", "connectexception", "sslhandshake",
            "connection reset", "connection refused", "timed out", "timeout", "network",
            "网络请求中断", "网络连接失败", "网络错误", "请求中断", "域名解析失败",
            "unable to resolve", "failed to connect", "handshake",
        ).any { it in raw }
}
