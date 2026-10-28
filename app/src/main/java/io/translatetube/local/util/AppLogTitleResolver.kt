package io.translatetube.local.util

import java.util.Locale

/**
 * Produces compact, actionable titles for diagnostic records.
 *
 * Stored tags and messages are not changed. Only the display title is shortened, so filtering,
 * exported details, exception chains, and historical records remain available for investigation.
 */
object AppLogTitleResolver {
    private const val MAX_TITLE_CODE_POINTS = 72

    fun featureLabel(tag: String, message: String = ""): String {
        val normalizedTag = tag.lowercase(Locale.ROOT)
        val combined = "$normalizedTag " + message.lowercase(Locale.ROOT)

        fun hasAny(vararg terms: String): Boolean =
            terms.any { combined.contains(it.lowercase(Locale.ROOT)) }

        return when {
            normalizedTag.contains("notificationdiagnostics") ->
                "通知诊断"
            normalizedTag.contains("uncaughtexception") &&
                hasAny("remoteserviceexception", "bad notification", "contentviews") ->
                "通知崩溃"
            normalizedTag.contains("dubbingmixer") ->
                "配音混流"
            normalizedTag.contains("dubbingfallback") ->
                "配音回退"
            normalizedTag.contains("dubbingtimeline") ||
                (normalizedTag.contains("dubbing") && hasAny("时间轴", "时间槽", "同步", "超出时间")) ->
                "配音时间轴"
            normalizedTag.contains("dubbingengine") -> when {
                hasAny("audiotrack", "静态音轨", "音轨创建", "音轨分段") ->
                    "配音音轨"
                hasAny("tts", "朗读", "utterance", "系统语音") ->
                    "系统 TTS"
                hasAny("混流", "挂载总线", "mix feed", "混流总线") ->
                    "配音混流挂载"
                hasAny("缓存", "cache", "pcm 预检", "pcm") ->
                    "配音缓存"
                hasAny("暂停", "恢复", "停止", "释放", "播放状态", "播放控制") ->
                    "配音播放控制"
                hasAny("初始化", "绑定字幕", "会话") ->
                    "配音引擎初始化"
                hasAny("合成", "synth", "音频生成") ->
                    "配音合成"
                else -> "配音引擎"
            }
            normalizedTag.contains("ytdlp") ||
                normalizedTag.contains("yt-dlp") ||
                normalizedTag.contains("youtube-dl") ||
                combined.contains("yt-dlp") -> when {
                hasAny("更新", "下载", "版本", "github api", "镜像") ->
                    "yt-dlp 更新"
                else -> "yt-dlp 解析"
            }
            normalizedTag.contains("webaudio") ||
                normalizedTag.contains("videosource") ||
                normalizedTag.contains("mediasource") ||
                normalizedTag.contains("streamsource") ||
                hasAny("视频源检测", "独立音轨识别", "blob / mse", "视频源卡片") -> when {
                hasAny("音轨", "audio track", "独立音频", "独立音轨") ->
                    "网页音轨"
                hasAny("码率", "分辨率", "画质", "音画混合", "blob", "mse") ->
                    "媒体规格"
                else -> "视频源检测"
            }
            normalizedTag.contains("pikpak") || combined.contains("pikpak") ->
                "PikPak 媒体加载"
            normalizedTag.contains("youtubeextractor") || normalizedTag.contains("newpiperepo") ->
                when {
                    hasAny("字幕") -> "YouTube 字幕轨"
                    hasAny("转写音轨", "音轨选择", "audio") -> "YouTube 转写音轨"
                    else -> "YouTube 媒体解析"
                }
            normalizedTag.contains("storagemanagementscreen") ->
                "存储清理"
            normalizedTag.contains("webplayerconsole") ->
                "网页脚本错误"
            normalizedTag.contains("webview") || normalizedTag.contains("webpage") ||
                normalizedTag.contains("webplayer") || normalizedTag.contains("webmedia") ->
                "网页媒体加载"
            normalizedTag.contains("whisper") ->
                "Whisper 转写"
            normalizedTag.contains("sensevoice") ->
                "SenseVoice 转写"
            normalizedTag.contains("cloudtranscription") ->
                "云端转写"
            normalizedTag.contains("audioextract") ||
                (hasAny("音轨提取", "音频解码", "pcm 解码") && !normalizedTag.contains("dubbing")) ->
                "音轨提取"
            normalizedTag.contains("transcription") ||
                normalizedTag.contains("transcrib") ||
                normalizedTag.contains("localasr") ||
                normalizedTag == "asr" || normalizedTag.endsWith("asr") ->
                "语音转写"
            normalizedTag.contains("translation") ||
                normalizedTag.contains("translate") ||
                hasAny("字幕翻译", "翻译请求", "翻译结果") ->
                "字幕翻译"
            normalizedTag.contains("subtitle") || normalizedTag.contains("subs") ||
                hasAny("字幕导入", "字幕获取", "字幕解析", "字幕时间轴") ->
                "字幕处理"
            (normalizedTag.contains("model") || normalizedTag.contains("archive")) &&
                hasAny("下载", "安装", "模型", "解压") ->
                "模型下载"
            normalizedTag.contains("modeldownload") ||
                normalizedTag.contains("modelinstall") ||
                normalizedTag.contains("modelmanager") ->
                "模型管理"
            normalizedTag.contains("apimanager") ||
                normalizedTag.contains("providerlogo") ||
                normalizedTag.contains("provider") ||
                (normalizedTag.contains("api") && !normalizedTag.contains("playback")) ->
                "API 连接"
            normalizedTag.contains("playbackpipeline") ->
                "视频处理流程"
            normalizedTag.contains("playbackservice") || normalizedTag.contains("playermanager") ->
                "视频播放"
            normalizedTag.contains("logrecovery") ->
                "故障恢复"
            normalizedTag.contains("applog") ->
                "日志记录"
            normalizedTag.contains("processexit") ||
                normalizedTag.contains("uncaughtexception") ||
                normalizedTag.contains("processsession") ||
                normalizedTag.contains("crash") ->
                "应用异常"
            normalizedTag.contains("storage") || normalizedTag.contains("fileprovider") ->
                "文件存储"
            else -> humanizeTag(tag)
        }
    }

    fun title(tag: String, message: String): String {
        val feature = featureLabel(tag, message)
        // Keep the event summary in the title; appended technical exception details stay in
        // the record details and are intentionally never surfaced in the title.
        val visibleMessage = normalizeMessage(splitTechnicalSuffix(message).first)
        if (visibleMessage.isBlank()) {
            return limitTitle("$feature：未提供日志摘要")
        }

        val knownSummary = summarizeKnownEvent(tag, visibleMessage)
        if (knownSummary != null) {
            return limitTitle("$feature：$knownSummary")
        }

        if (alreadyHasUsefulFeatureAndOutcome(visibleMessage, feature)) {
            return limitTitle(visibleMessage)
        }

        val summary = stripRedundantPrefix(tag, feature, visibleMessage)
            .ifBlank { visibleMessage }
        return limitTitle("$feature：$summary")
    }

    private fun summarizeKnownEvent(tag: String, message: String): String? {
        val normalizedTag = tag.lowercase(Locale.ROOT)
        if (normalizedTag.contains("dubbingfallback")) {
            val numberedFallback = Regex(
                "播放第\\s*(\\d+)\\s*/\\s*(\\d+)\\s*句时未检测到可播放中文配音.*已恢复原声",
            ).find(message)
            if (numberedFallback != null) {
                return "第 " + numberedFallback.groupValues[1] + "/" +
                    numberedFallback.groupValues[2] + " 句无可用配音，已恢复原声"
            }
            if (message.contains("未检测到可播放中文配音") && message.contains("恢复原声")) {
                return "未检测到可用中文配音，已恢复原声"
            }
        }
        if ((normalizedTag.contains("webaudio") || normalizedTag.contains("videosource")) &&
            message.contains("检测到网页独立音轨")
        ) {
            return "已识别独立音轨"
        }
        if ((normalizedTag.contains("ytdlp") || normalizedTag.contains("yt-dlp")) &&
            message.contains("更新下载失败")
        ) {
            return "下载失败"
        }
        if (normalizedTag.contains("dubbingengine") && message == "配音 PCM 缓存预检完成") {
            return "PCM 预检完成"
        }
        if (normalizedTag.contains("playermanager")) {
            when (message) {
                "播放器报告错误" -> return "播放出错"
                "视频首帧已渲染" -> return "首帧已渲染"
                "视频缓冲较慢" -> return "缓冲较慢"
            }
        }
        return null
    }

    private fun alreadyHasUsefulFeatureAndOutcome(message: String, feature: String): Boolean {
        if (!message.startsWith(feature, ignoreCase = true)) return false
        val remainder = message.substring(feature.length).trimStart()
        if (remainder.isBlank()) return true
        return startsWithAny(
            remainder,
            "失败", "错误", "异常", "超时", "中断", "完成", "成功", "已", "未",
            "无法", "不能", "请求", "结果", "接口", "返回", "检测", "恢复", "退出",
            "崩溃", "启动", "：", ":", "/", "—", "-",
        )
    }

    private fun stripRedundantPrefix(tag: String, feature: String, message: String): String {
        val normalizedTag = tag.lowercase(Locale.ROOT)
        val candidates = mutableListOf(feature)
        when {
            normalizedTag.contains("dubbingmixer") ->
                candidates += listOf("配音混流处理器", "配音混流")
            normalizedTag.contains("dubbingengine") -> {
                candidates += listOf("配音 PCM 缓存", "配音引擎", "配音")
                if (feature == "系统 TTS") candidates += "系统 TTS"
            }
            normalizedTag.contains("ytdlp") || normalizedTag.contains("yt-dlp") ->
                candidates += listOf("yt-dlp 更新", "yt-dlp")
            normalizedTag.contains("webaudio") ->
                candidates += listOf("网页独立音轨", "网页音轨")
            normalizedTag.contains("translation") || normalizedTag.contains("translate") ->
                candidates += "翻译"
            normalizedTag.contains("transcription") || normalizedTag.contains("transcrib") ||
                normalizedTag.contains("localasr") || normalizedTag.endsWith("asr") ->
                candidates += listOf("语音识别", "语音转写", "转写")
            normalizedTag.contains("audioextract") ->
                candidates += listOf("音轨提取", "音频解码", "提取音频")
        }

        for (candidate in candidates.distinct().sortedByDescending { it.length }) {
            if (!message.startsWith(candidate, ignoreCase = true)) continue
            val remainder = message.substring(candidate.length)
            val trimmed = remainder.trimStart(' ', '\t', '：', ':', '/', '—', '-', '、')
            val first = remainder.firstOrNull()
            if (trimmed.isBlank()) continue
            if (first?.isWhitespace() == true ||
                (first != null && first in charArrayOf('：', ':', '/', '—', '-', '、')) ||
                startsWithAny(
                    trimmed,
                    "处理器", "引擎", "模块", "模型", "任务", "服务", "接口", "响应", "内容",
                    "字幕", "音轨", "下载", "读取", "请求", "调用", "创建", "初始化", "合成",
                    "缓存", "预检", "执行", "获取", "检测", "识别", "写入", "返回", "结果",
                    "解析", "转写", "加载", "安装", "更新", "恢复", "播放", "退出",
                )
            ) {
                val compacted = trimmed
                    .removePrefix("处理器")
                    .removePrefix("引擎")
                    .removePrefix("模块")
                    .trimStart(' ', '：', ':', '/', '—', '-')
                if (compacted.isNotBlank()) return compacted
            }
        }
        return message
    }

    /** Separate appended exception diagnostics from the user-facing event summary. */
    private fun splitTechnicalSuffix(message: String): Pair<String, String?> {
        val separator = message.indexOf(" | ")
        if (separator < 0) return message to null

        val suffix = message.substring(separator + 3)
        val technical = Regex(
            """(?i)(\b[\w.$]*(?:exception|error|throwable)\b|\bcaused by\s*:|\broot cause\b|根因\s*=|\bhttp\s+\d{3}\b|\bat\s+[\w.$]+\()""",
        )
        return if (technical.containsMatchIn(suffix)) {
            message.substring(0, separator).trimEnd() to suffix
        } else {
            message to null
        }
    }

    private fun normalizeMessage(message: String): String =
        message.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun startsWithAny(value: String, vararg prefixes: String): Boolean =
        prefixes.any { value.startsWith(it, ignoreCase = true) }

    private fun limitTitle(value: String): String {
        val title = normalizeMessage(value)
        val codePointCount = title.codePointCount(0, title.length)
        if (codePointCount <= MAX_TITLE_CODE_POINTS) return title
        val end = title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1)
        return title.substring(0, end).trimEnd() + "…"
    }

    private fun humanizeTag(tag: String): String {
        val readable = tag
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            .replace('_', ' ')
            .trim()
        return readable.ifBlank { "未分类日志" }
    }
}
