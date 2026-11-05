package io.translatetube.local.player

import java.util.Locale

/**
 * Converts pipeline failures to concise, stage-specific UI copy.
 *
 * The original Throwable, cause chain and detailed diagnostics remain in AppLogStore;
 * this policy deliberately never returns raw exception messages or host names to the UI.
 */
internal object ProcessingErrorDisplayPolicy {
    private val httpStatusPattern = Regex(
        """(?:http(?:/\d+(?:\.\d+)?)?\s*(?:error\s*)?|status(?:\s+code)?\s*[:=]\s*)(\d{3})""",
        RegexOption.IGNORE_CASE,
    )
    private val subtitleIndexPattern = Regex(
        """(?:第\s*(\d+)\s*条\s*字幕|字幕\s*#?\s*(\d+)|(?:cue|subtitle)\s*#?\s*(\d+))""",
        RegexOption.IGNORE_CASE,
    )

    fun title(phase: Phase?): String = when (phase) {
        Phase.PARSING -> "视频解析失败"
        Phase.DOWNLOADING_AUDIO -> "音频提取失败"
        Phase.TRANSCRIBING -> "语音转写失败"
        Phase.TRANSLATING -> "字幕翻译失败"
        Phase.SYNTHESIZING -> "配音合成失败"
        else -> "视频加载失败"
    }

    fun retryLabel(phase: Phase?): String =
        if (phase == Phase.PARSING) "重新解析" else "重试当前处理"

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

        return when (phase) {
            Phase.PARSING -> parsingMessage(source, raw, status)
            Phase.DOWNLOADING_AUDIO -> audioMessage(raw, status)
            Phase.TRANSCRIBING -> transcriptionMessage(raw)
            Phase.TRANSLATING -> translationMessage(raw, status, subtitleIndex)
            Phase.SYNTHESIZING -> synthesisMessage(raw, subtitleIndex)
            else -> playbackMessage(raw)
        }
    }

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
                "音轨地址已失效，需要重新获取"
            status == 404 -> "未找到可下载的视频音轨"
            isNetworkFailure(raw) -> "音轨下载中断或超时"
            "不支持" in raw || "unsupported" in raw -> "当前音轨格式暂不受支持"
            else -> "未能提取可用的视频音轨"
        }
        return "音频提取失败：$reason"
    }

    private fun transcriptionMessage(raw: String): String {
        val reason = when {
            "云端转写" in raw && ("不确定" in raw || "uncertain" in raw) ->
                "云端转写提交状态尚未确认，请确认后再重新提交"
            "outofmemory" in raw || "out of memory" in raw || "内存不足" in raw ->
                "设备可用内存不足，未能完成语音识别"
            "model not" in raw || "model unavailable" in raw ||
                "模型未就绪" in raw || "模型文件" in raw || "模型不存在" in raw ->
                "当前语音识别引擎或模型未就绪"
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
            isNetworkFailure(raw) -> "翻译服务请求中断或超时"
            "model not" in raw || "模型未就绪" in raw || "模型不存在" in raw ->
                "所选翻译模型未就绪"
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

    private fun synthesisMessage(raw: String, subtitleIndex: Int?): String {
        val reason = when {
            "outofmemory" in raw || "out of memory" in raw || "内存不足" in raw ->
                "设备可用内存不足"
            "model not" in raw || "模型未就绪" in raw || "模型文件" in raw ||
                "初始化失败" in raw || "initializ" in raw ->
                "所选语音引擎或模型未能正常启动"
            isNetworkFailure(raw) -> "在线语音服务请求中断或超时"
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
            "unknownhost", "sockettimeout", "connectexception", "sslhandshake",
            "connection reset", "connection refused", "timed out", "timeout",
            "network", "网络请求中断", "网络连接失败", "网络错误", "请求中断",
            "域名解析失败", "unable to resolve", "failed to connect",
        ).any { it in raw }
}
