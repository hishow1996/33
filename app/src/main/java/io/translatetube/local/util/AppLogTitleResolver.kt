package io.translatetube.local.util

import java.util.Locale

/**
 * Human-readable feature labels for log titles.
 *
 * The stored tag remains unchanged for filtering/backward compatibility. This resolver only
 * improves how a record is presented, and deliberately keeps both the feature and original
 * message visible so a title can be traced back to its source.
 */
object AppLogTitleResolver {
    fun featureLabel(tag: String, message: String = ""): String {
        val normalizedTag = tag.lowercase(Locale.ROOT)
        val normalizedMessage = message.lowercase(Locale.ROOT)
        val combined = "$normalizedTag $normalizedMessage"

        fun hasAny(vararg terms: String): Boolean =
            terms.any { combined.contains(it.lowercase(Locale.ROOT)) }

        return when {
            normalizedTag.contains("notificationdiagnostics") ->
                "应用运行 / 通知发布与布局"
            normalizedTag.contains("uncaughtexception") &&
                hasAny("remoteserviceexception", "bad notification", "contentviews") ->
                "应用运行 / 通知膨胀崩溃诊断"
            normalizedTag.contains("dubbingmixer") ->
                "配音 / 音频混流"
            normalizedTag.contains("dubbingfallback") ->
                "配音 / 译文缺失与原声回退"
            normalizedTag.contains("dubbingtimeline") ||
                (normalizedTag.contains("dubbing") && hasAny("时间轴", "时间槽", "同步", "超出时间")) ->
                "配音 / 时间轴同步"
            normalizedTag.contains("dubbingengine") -> when {
                hasAny("audiotrack", "静态音轨", "音轨创建", "音轨分段") ->
                    "配音 / 音轨创建与输出"
                hasAny("tts", "朗读", "utterance", "系统语音") ->
                    "配音 / 系统 TTS 朗读"
                hasAny("混流", "挂载总线", "mix feed", "混流总线") ->
                    "配音 / 混流挂载"
                hasAny("缓存", "cache", "pcm 预检", "pcm") ->
                    "配音 / PCM 合成与缓存"
                hasAny("暂停", "恢复", "停止", "释放", "播放状态", "播放控制") ->
                    "配音 / 播放状态与控制"
                hasAny("初始化", "绑定字幕", "会话") ->
                    "配音 / 引擎初始化"
                hasAny("合成", "synth", "音频生成") ->
                    "配音 / 逐句合成"
                else -> "中文配音 / 引擎运行"
            }
            normalizedTag.contains("ytdlp") ||
                normalizedTag.contains("yt-dlp") ||
                normalizedTag.contains("youtube-dl") ||
                combined.contains("yt-dlp") -> when {
                hasAny("更新", "下载", "版本", "github api", "镜像") ->
                    "yt-dlp / 版本检查与更新"
                else -> "yt-dlp / 视频解析"
            }
            normalizedTag.contains("webaudio") ||
                normalizedTag.contains("videosource") ||
                normalizedTag.contains("mediasource") ||
                normalizedTag.contains("streamsource") ||
                hasAny("视频源检测", "独立音轨识别", "blob / mse", "视频源卡片") -> when {
                hasAny("音轨", "audio track", "独立音频", "独立音轨") ->
                    "视频源检测 / 音轨识别"
                hasAny("码率", "分辨率", "画质", "音画混合", "blob", "mse") ->
                    "视频源检测 / 媒体类型与画质"
                else -> "视频源检测 / 资源识别"
            }
            normalizedTag.contains("pikpak") || combined.contains("pikpak") ->
                "PikPak / 分享页与媒体加载"
            normalizedTag.contains("youtubeextractor") || normalizedTag.contains("newpiperepo") ->
                when {
                    hasAny("字幕") -> "YouTube / 字幕轨获取与解析"
                    hasAny("转写音轨", "音轨选择", "audio") -> "YouTube / 转写音轨选择"
                    else -> "YouTube / 视频元数据与媒体流"
                }
            normalizedTag.contains("storagemanagementscreen") ->
                "存储管理 / 空间扫描与数据清理"
            normalizedTag.contains("webplayerconsole") ->
                "网页播放器 / JavaScript 错误"
            normalizedTag.contains("webview") || normalizedTag.contains("webpage") ||
                normalizedTag.contains("webplayer") || normalizedTag.contains("webmedia") ->
                "网页播放 / 页面加载与媒体读取"
            normalizedTag.contains("whisper") ->
                "Whisper / 语音转写"
            normalizedTag.contains("sensevoice") ->
                "SenseVoice / 语音转写"
            normalizedTag.contains("cloudtranscription") ->
                "云端语音转写 / 请求与结果处理"
            normalizedTag.contains("audioextract") ||
                (hasAny("音轨提取", "音频解码", "pcm 解码") && !normalizedTag.contains("dubbing")) ->
                "音轨提取 / 音频解码"
            normalizedTag.contains("transcription") ||
                normalizedTag.contains("transcrib") ||
                normalizedTag.contains("localasr") ||
                normalizedTag == "asr" || normalizedTag.endsWith("asr") ->
                "语音转写 / 转写引擎"
            normalizedTag.contains("translation") ||
                normalizedTag.contains("translate") ||
                hasAny("字幕翻译", "翻译请求", "翻译结果") ->
                "字幕翻译 / 引擎请求与结果"
            normalizedTag.contains("subtitle") || normalizedTag.contains("subs") ||
                hasAny("字幕导入", "字幕获取", "字幕解析", "字幕时间轴") ->
                "字幕 / 获取、导入与时间轴"
            (normalizedTag.contains("model") || normalizedTag.contains("archive")) &&
                hasAny("下载", "安装", "模型", "解压") ->
                "模型管理 / 下载与安装"
            normalizedTag.contains("modeldownload") ||
                normalizedTag.contains("modelinstall") ||
                normalizedTag.contains("modelmanager") ->
                "模型管理 / 下载与安装"
            normalizedTag.contains("apimanager") ||
                normalizedTag.contains("providerlogo") ||
                normalizedTag.contains("provider") ||
                (normalizedTag.contains("api") && !normalizedTag.contains("playback")) ->
                "API 设置 / 服务商配置与连接"
            normalizedTag.contains("playbackpipeline") ->
                "视频处理流程 / 解析、转写、翻译与配音"
            normalizedTag.contains("playbackservice") || normalizedTag.contains("playermanager") ->
                "视频播放 / 播放控制与时间轴"
            normalizedTag.contains("logrecovery") ->
                "诊断日志 / 故障恢复跟踪"
            normalizedTag.contains("applog") ->
                "诊断日志 / 记录与写入"
            normalizedTag.contains("processexit") ||
                normalizedTag.contains("uncaughtexception") ||
                normalizedTag.contains("processsession") ||
                normalizedTag.contains("crash") ->
                "应用运行 / 启动与异常退出"
            normalizedTag.contains("storage") || normalizedTag.contains("fileprovider") ->
                "文件与存储 / 读写管理"
            else -> "功能日志 / ${humanizeTag(tag)}"
        }
    }

    fun title(tag: String, message: String): String {
        val feature = featureLabel(tag, message)
        val summary = message.trim().ifBlank { tag.trim().ifBlank { "未提供日志摘要" } }
        return "$feature：$summary"
    }

    private fun humanizeTag(tag: String): String {
        val readable = tag
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            .replace('_', ' ')
            .trim()
        return readable.ifBlank { "未知模块" }
    }
}
