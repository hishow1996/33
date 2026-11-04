package io.translatetube.local.util

/**
 * Checks whether a warning/error contains the evidence normally needed to repair that module.
 * This is an evidence checklist, not a root-cause classifier. Missing means "not recorded".
 */
internal data class AppLogDiagnosticCoverage(
    val domain: String,
    val collectedEvidenceCount: Int,
    val requiredEvidenceCount: Int,
    val missingEvidence: List<String>,
    val collectionAdvice: List<String>,
) {
    val completenessPercent: Int
        get() = if (requiredEvidenceCount == 0) 100
        else (collectedEvidenceCount * 100 / requiredEvidenceCount).coerceIn(0, 100)
}

internal object AppLogDiagnosticCoveragePolicy {
    private data class EvidenceRule(
        val label: String,
        val check: (AppLogRecord) -> Boolean,
        val advice: String,
    )

    fun analyze(record: AppLogRecord): AppLogDiagnosticCoverage {
        if (record.level.uppercase() !in setOf("WARN", "ERROR", "FATAL")) {
            return AppLogDiagnosticCoverage("不适用", 0, 0, emptyList(), emptyList())
        }
        val domain = detectDomain(record)
        val rules = commonRules() + domainRules(domain)
        val missing = rules.filterNot { it.check(record) }
        return AppLogDiagnosticCoverage(
            domain = domain,
            collectedEvidenceCount = rules.size - missing.size,
            requiredEvidenceCount = rules.size,
            missingEvidence = missing.map { it.label },
            collectionAdvice = missing.map { it.advice }.distinct(),
        )
    }

    private fun detectDomain(record: AppLogRecord): String {
        val scope = listOf(record.tag, record.category, record.message, record.details.take(1_200))
            .joinToString(" ")
        return when {
            containsAny(scope, "translation", "字幕翻译", "翻译阶段", "翻译请求", "译文校验") -> "字幕翻译"
            containsAny(scope, "texttospeech", "system tts", "tts", "dubbing", "配音合成", "语音合成", "朗读") -> "语音合成"
            containsAny(scope, "transcrib", "speech recognition", "sensevoice", "whisper", "语音转写", "语音识别") -> "语音转写"
            containsAny(scope, "playback", "player", "audiotrack", "播放器", "播放同步", "音轨输出") -> "播放/音频输出"
            containsAny(scope, "youtube", "yt-dlp", "newpipe", "webview", "websource", "hls", "网页视频", "媒体解析", "视频解析", "媒体下载") -> "视频解析/媒体获取"
            containsAny(scope, "modeldownload", "model download", "storage", "cache", "install", "文件安装", "存储", "缓存", "模型下载") -> "下载/存储"
            containsAny(scope, "http", "https", "network", "socket", "dns", "request", "网络", "请求", "连接", "超时") -> "网络/接口请求"
            else -> "通用异常"
        }
    }

    private fun commonRules() = listOf(
        EvidenceRule("异常类型、根因消息或服务端错误码", { hasFailureCause(it) },
            "在真实失败分支记录异常类型、最深层 cause、服务商错误码或明确失败分类；不要只写“处理失败”。"),
        EvidenceRule("异常堆栈或日志调用位置", { hasApplicationFrame(it) },
            "优先保留 Throwable 原始对象、cause/suppressed 和异常自身堆栈；若调用方没有 Throwable，至少记录日志调用文件、方法、行号及调用链，并明确它不是异常抛出位置。"),
        EvidenceRule("任务关联标识", { hasOperationCorrelation(it) },
            "为完整任务记录稳定 operationId；子请求另记 requestId/diagnosticId，并通过协程上下文传递。"),
        EvidenceRule("失败阶段与具体子步骤", { hasStageEvidence(it) },
            "在失败点记录当前处理阶段、具体子步骤，以及失败前最后一个成功步骤。"),
        EvidenceRule("诊断字段没有因日志容量上限而省略或截断", { hasCompleteContextBudget(it) },
            "检查日志诊断字段总数、省略数和截断数；如有字段丢失，优先保留阶段、引擎、请求状态、输入输出计数及失败位置。"),
    )

    private fun domainRules(domain: String): List<EvidenceRule> = when (domain) {
        "字幕翻译" -> listOf(
            rule("翻译引擎名称及稳定 ID") { hasContext(it, "翻译引擎", "引擎 ID", "服务商", "provider") },
            rule("批次编号、请求阶段或句子索引") { hasContext(it, "当前批次", "批次编号", "批次", "请求阶段", "句子索引") },
            rule("本批输入字幕行数") { hasContext(it, "输入字幕行数", "输入行数", "批次输入行数", "批次行数") },
            rule("有效译文数及未通过校验行数") { hasContext(it, "有效译文", "无效译文", "拒绝行数", "rejectedRows") },
            rule("HTTP 状态码或服务商错误码") {
                it.httpStatusCode in 400..599 || hasContext(it, "HTTP 状态码", "服务商错误码", "providerErrorCode", "失败分类代码")
            },
            rule("请求、响应解析与重试轨迹") {
                hasContext(it, "传输/响应", "网络重试轨迹", "响应结构", "重试轨迹", "responseBodySummary")
            },
        )
        "语音转写" -> listOf(
            rule("转写引擎及模型名称/版本") { hasContext(it, "转写引擎", "识别引擎", "模型名称", "模型 ID") },
            rule("音频时长或音频文件大小") { hasContext(it, "音频时长", "媒体时长", "音频字节数", "文件大小") },
            rule("采样率、声道及音频格式") { hasContext(it, "采样率", "声道", "音频格式", "PCM 格式") },
            rule("分段/分块索引及总数") { hasContext(it, "分段索引", "分块索引", "片段", "分段数量", "chunk") },
            rule("已处理音频时长或转写进度") { hasContext(it, "已处理时长", "转写进度", "当前处理步骤", "处理进度") },
        )
        "语音合成" -> listOf(
            rule("配音引擎、音色或服务商") { hasContext(it, "配音引擎", "TTS 引擎", "语音合成引擎", "音色", "语音服务") },
            rule("字幕行号/句子索引") { hasContext(it, "字幕行号", "字幕索引", "句子索引", "当前句") },
            rule("待合成文本字符数（不要求记录全文）") { hasContext(it, "文本长度", "字符数", "输入字符数", "待合成字数") },
            rule("输出音频字节数、时长或 PCM 样本数") { hasContext(it, "音频字节数", "音频时长", "PCM 样本数", "输出字节数", "生成音频时长") },
            rule("输出采样率、声道和编码格式") { hasContext(it, "采样率", "声道", "编码格式", "音频格式", "PCM 格式") },
            rule("引擎回调状态、超时或失败分类") { hasContext(it, "回调状态", "超时", "失败分类", "失败原因", "合成状态", "服务商错误码") },
        )
        "播放/音频输出" -> listOf(
            rule("视频来源及会话标识") { hasContext(it, "视频来源", "媒体来源", "视频会话指纹", "会话指纹") },
            rule("播放器状态与播放位置") { hasContext(it, "播放器状态", "播放状态", "视频播放位置", "播放位置", "position", "playbackState") },
            rule("原声/配音音轨状态") { hasContext(it, "原声状态", "配音状态", "音轨状态", "AudioTrack", "音频输出状态") },
            rule("配音队列、当前字幕索引及准备进度") { hasContext(it, "配音队列", "当前字幕索引", "已准备配音", "待处理配音", "配音进度") },
        )
        "视频解析/媒体获取" -> listOf(
            rule("视频来源及安全主机名") { hasContext(it, "视频来源", "页面主机名", "网页主机名", "媒体主机名", "来源类型") },
            rule("解析器/下载器及所选候选源") { hasContext(it, "解析器", "下载器", "媒体候选", "候选源", "选择来源", "提取方式") },
            rule("请求阶段、HTTP 状态或解析失败分类") {
                it.httpStatusCode in 400..599 || hasContext(it, "请求阶段", "HTTP 状态码", "失败分类", "错误分类")
            },
            rule("媒体容器、编码及音视频轨道信息") { hasContext(it, "媒体类型", "容器", "视频编码", "音频编码", "音轨", "轨道") },
            rule("重试、资源刷新或候选切换决策") { hasContext(it, "重试", "重新提取", "候选切换", "回退决策", "恢复方式") },
        )
        "下载/存储" -> listOf(
            rule("文件/模型操作类型及目标类别") { hasContext(it, "文件操作", "操作类型", "模型名称", "模型 ID", "缓存类型", "目标类别") },
            rule("目标文件预期大小与实际大小") { hasContext(it, "预期大小", "实际大小", "文件大小", "下载字节数", "已下载字节数") },
            rule("可用存储空间及目录") { hasContext(it, "可用空间", "剩余空间", "目录可用空间") || it.details.contains("应用文件目录可用空间") },
            rule("校验、解压或原子安装结果") { hasContext(it, "校验结果", "解压结果", "安装结果", "checksum", "文件完整性") },
        )
        "网络/接口请求" -> listOf(
            rule("目标服务商或安全主机名") { hasContext(it, "服务商", "主机名", "请求主机", "目标主机", "页面主机名") },
            rule("请求阶段/接口操作类型") { hasContext(it, "请求阶段", "请求类型", "接口操作", "操作阶段") },
            rule("HTTP 状态码或底层网络异常类型") {
                it.httpStatusCode in 100..599 || it.errorClass.isNotBlank() || hasContext(it, "HTTP 状态码", "异常类型", "错误码", "服务商错误码")
            },
            rule("请求耗时及超时配置") { hasContext(it, "请求耗时", "耗时 ms", "连接超时", "读取超时", "超时配置") },
            rule("响应类型、响应长度及重试信息") { hasContext(it, "Content-Type", "响应长度", "响应字节", "重试次数", "Retry-After", "响应格式") },
        )
        else -> emptyList()
    }

    private fun rule(label: String, check: (AppLogRecord) -> Boolean): EvidenceRule =
        EvidenceRule(label, check, adviceFor(label))

    private fun adviceFor(label: String): String = when {
        label.contains("引擎") || label.contains("模型") ->
            "记录实际生效的引擎/模型名称及稳定 ID，不能只记录设置页选择值。"
        label.contains("批次") || label.contains("字幕行") || label.contains("句子") ->
            "记录批次序号/总数、输入行数、输出行数和失败字幕索引；不要记录字幕全文。"
        label.contains("音频") || label.contains("采样率") || label.contains("声道") || label.contains("PCM") ->
            "记录音频时长、字节数、采样率、声道数、PCM 格式，以及处理前后的数量变化。"
        label.contains("HTTP") || label.contains("服务商") || label.contains("响应") || label.contains("请求") ->
            "记录脱敏后的服务商/主机名、HTTP 状态、服务商错误码、响应类型/长度、耗时和重试结果；禁止记录密钥、Cookie 和完整签名 URL。"
        label.contains("存储") || label.contains("文件") || label.contains("安装") ->
            "记录操作类型、文件类别、预期/实际字节数、可用空间、校验和安装阶段；避免输出用户私人路径。"
        label.contains("播放器") || label.contains("播放") || label.contains("音轨") || label.contains("配音队列") ->
            "记录播放器状态、播放位置、当前字幕/配音索引、原声与配音音轨状态及队列长度。"
        label.contains("关联") || label.contains("任务") ->
            "为父任务和子请求添加稳定关联 ID，并在所有协程/线程日志中继承；不得仅凭时间相邻推断因果。"
        label.contains("阶段") || label.contains("步骤") ->
            "记录失败阶段、当前子步骤和失败前最后一个成功步骤。"
        label.contains("调用位置") ->
            "保留异常对象和完整异常树，输出首个应用包名栈帧及行号。"
        else ->
            "在产生异常的真实分支补充结构化上下文，只记录客观值，不以推测替代证据。"
    }

    private fun hasContext(record: AppLogRecord, vararg aliases: String): Boolean {
        val inMap = record.diagnosticContext.entries.any { (key, value) ->
            aliases.any { key.contains(it, ignoreCase = true) } && isRecordedValue(value)
        }
        if (inMap) return true
        return record.details.lineSequence().any { line ->
            val normalized = line.trim().removePrefix("-").trim()
            val separator = listOf('：', ':', '=').map { normalized.indexOf(it) }
                .filter { it > 0 }.minOrNull() ?: return@any false
            val key = normalized.substring(0, separator)
            val value = normalized.substring(separator + 1)
            aliases.any { key.contains(it, ignoreCase = true) } && isRecordedValue(value)
        }
    }

    private fun isRecordedValue(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) return false
        val unknown = listOf("未记录", "未采集", "未获取", "未确定", "未知", "unknown", "not recorded", "not collected", "n/a", "null", "（空）")
        return unknown.none { normalized == it || normalized.startsWith("$it；") || normalized.startsWith("$it ") }
    }

    private fun hasFailureCause(record: AppLogRecord): Boolean =
        record.errorClass.isNotBlank() || record.rootCauseMessage.isNotBlank() ||
            record.exceptionSnapshot != null || record.httpStatusCode in 400..599 ||
            hasContext(record, "服务商错误码", "失败分类代码", "错误码", "失败原因") ||
            Regex("(?m)^\\s*#\\d+\\s+[A-Za-z0-9_.$]+").containsMatchIn(record.details)

    private fun hasApplicationFrame(record: AppLogRecord): Boolean {
        if (record.firstAppStackFrame.isNotBlank() || record.details.contains("at io.translatetube.local.")) return true
        val callSite = record.diagnosticContext["日志调用位置"].orEmpty().trim()
        if (callSite.isNotBlank() && callSite !in setOf("未采集", "不可用", "旧版日志未记录")) return true
        fun containsFrame(snapshot: AppLogExceptionSnapshot?): Boolean {
            if (snapshot == null) return false
            return snapshot.stackFrames.any { it.className.startsWith("io.translatetube.local.") } ||
                containsFrame(snapshot.cause) || snapshot.suppressed.any(::containsFrame)
        }
        return containsFrame(record.exceptionSnapshot)
    }

    private fun hasCompleteContextBudget(record: AppLogRecord): Boolean {
        // Legacy records predate explicit budget metadata and must not be marked incomplete.
        if (!record.diagnosticContext.containsKey("日志诊断字段总数")) return true
        val omitted = record.diagnosticContext["日志诊断字段省略数"]?.toIntOrNull() ?: return false
        val truncated = record.diagnosticContext["日志诊断字段截断数"]?.toIntOrNull() ?: return false
        return omitted == 0 && truncated == 0
    }

    private fun hasOperationCorrelation(record: AppLogRecord): Boolean =
        record.operationId.isNotBlank() ||
            hasContext(record, "操作关联 ID", "翻译诊断 ID", "requestId", "request id", "traceId", "trace id", "任务 ID")

    private fun hasStageEvidence(record: AppLogRecord): Boolean =
        hasContext(record, "当前处理阶段", "失败阶段", "故障阶段", "当前 UI 阶段", "请求阶段",
            "处理阶段", "当前处理步骤", "最近处理步骤")

    private fun containsAny(value: String, vararg terms: String): Boolean =
        terms.any { value.contains(it, ignoreCase = true) }
}
