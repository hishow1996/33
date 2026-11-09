package io.translatetube.local.util

internal data class IncidentDocumentField(val name: String, val value: String)

internal data class IncidentDocumentCodeLocation(
    val lines: List<String>,
    val additionalFrames: List<String> = emptyList(),
) {
    fun renderLines(): List<String> = lines + additionalFrames
}

internal data class IncidentDocumentPrevious(
    val scopeLine: String,
    val events: List<String>,
    val recoveryLine: String?,
    val includeCodeLocationGuidance: Boolean = true,
) {
    fun renderLines(): List<String> = buildList {
        add(scopeLine)
        if (events.isEmpty()) add("前序事件：未找到可确认的更早记录")
        else events.forEach { add("前序：" + it) }
        recoveryLine?.let { add(it) }
        if (includeCodeLocationGuidance) {
            add("要求：对照该 Git SHA 的代码定位真实根因，给出文件、方法和最小改动及验证方式。")
        } else {
            add("要求：依据已记录的处理上下文核对行为，不把普通状态信息推断为故障。")
        }
        add("区分已证实的事实与推断；\"未采集\"项不要猜；不要用吞异常/空结果/伪造成功掩盖故障。")
    }
}

/** Immutable shared content for both the detail screen and clipboard. */
internal data class IncidentDocument(
    val headerLine: String,
    val problemTitle: String,
    val symptom: String,
    val failureLocation: String,
    val directCause: String,
    val faultNature: String,
    val diagnosisConfidence: String,
    val diagnosticEvidence: List<String>,
    val faultLines: List<String>,
    val codeLocation: IncidentDocumentCodeLocation?,
    val sceneFields: List<IncidentDocumentField>,
    val previous: IncidentDocumentPrevious,
) {
    fun blockTitles(): List<String> = buildList {
        add("故障问题")
        add("现象")
        add("失败位置")
        add("关键原因")
        add("故障性质")
        add("基本信息")
        if (diagnosticEvidence.isNotEmpty()) add("关键证据")
        if (codeLocation != null) add("代码定位")
        add("现场参数")
        add("关联上下文")
    }

    fun toPlainText(): String {
        val output = StringBuilder()
        fun block(title: String, lines: List<String>) {
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(title)
            lines.filter { it.isNotBlank() }.forEach { output.append('\n').append(it) }
        }
        block("故障问题", listOf(problemTitle))
        block("现象", listOf(symptom))
        block("失败位置", listOf(failureLocation))
        block("关键原因", listOf(directCause))
        block("故障性质", listOf(faultNature, "诊断可信度：" + diagnosisConfidence))
        block("基本信息", listOf(headerLine) + faultLines)
        if (diagnosticEvidence.isNotEmpty()) block("关键证据", diagnosticEvidence)
        codeLocation?.let { block("代码定位", it.renderLines()) }
        block("现场参数", sceneFields.map { it.name + "：" + it.value })
        block("关联上下文", previous.renderLines())
        return AppLogStore.redactForExport(output.toString())
    }
}

internal object AppLogIncidentDocument {
    private const val MAX_CHARS = 5_000
    private const val THREAD_WINDOW_MS = 120_000L

    fun build(
        record: AppLogRecord,
        allRecords: List<AppLogRecord>,
        env: AppLogEnvironmentSnapshot,
    ): IncidentDocument {
        val domain = AppLogDiagnosticCoveragePolicy.detectDomain(record)
        val stage = value(record, "当前处理阶段", "失败阶段", "故障阶段", "处理阶段", "当前 UI 阶段", "阶段")
        val engine = engine(record, domain)
        val batch = if (domain == "字幕翻译") translationBatch(record) else null
        val engineBatch = listOfNotNull(engine, batch).joinToString(" / ").ifBlank { "未采集" }
        val module = record.category.ifBlank { AppLogTitleResolver.featureLabel(record.tag, record.message) }
        val diagnosis = AppLogDiagnostics.analyze(record)

        val header = "应用 " + clip(env.appVersion, 80) +
            " | " + clip(env.device, 64) +
            " Android " + clip(env.androidVersion, 40) +
            " | " + clip(AppLogStore.formatTime(record.timestampMs), 32) +
            " | " + clip(record.eventId.ifBlank { "旧版日志未记录" }, 48)

        val fault = buildList {
            add(
                clip(record.level.ifBlank { "未采集" }, 12) + " · " +
                    clip(module, 48) + " · " +
                    clip(stage ?: "未采集", 48) + " · " +
                    clip(engineBatch, 80),
            )
            if (!record.level.equals("INFO", true)) {
                val deepest = AppLogAiReport.deepestCause(record.exceptionSnapshot)
                val type = deepest?.type?.takeIf { it.isNotBlank() }
                    ?: record.errorClass.ifBlank { "未采集" }
                val root = AppLogAiReport.rootCauseForAi(record).ifBlank { "未采集" }
                add("异常类型：" + clip(AppLogAiReport.simpleErrorType(type), 60) + "：" + clip(root, 200))
            }
        }

        val code = if (record.level.uppercase() in setOf("WARN", "ERROR", "FATAL")) {
            codeLocation(record)
        } else null

        val symptom = AppLogTitleResolver.title(record.tag, record.message)
        val failureLocation = code?.lines?.firstOrNull()
            ?: "未记录失败调用位置（普通状态日志）"
        val nature = faultNature(record, diagnosis)
        val evidenceLines = listOf(diagnosis.evidence)
            .filter { it.isNotBlank() }
            .map { clip(it, 900) }
        return fit(
            IncidentDocument(
                headerLine = header,
                problemTitle = if (record.level.equals("INFO", true)) {
                    "运行状态记录"
                } else {
                    AppLogTitleResolver.incidentTitle(record.tag, record.message, diagnosis.category)
                },
                symptom = clip(symptom, 420),
                failureLocation = clip(failureLocation, 300),
                directCause = if (record.level.equals("INFO", true)) {
                    "这是一条普通状态记录，不表示处理失败。"
                } else {
                    clip(diagnosis.finding, 1_000)
                },
                faultNature = if (record.level.equals("INFO", true)) {
                    "普通运行状态"
                } else {
                    clip(nature, 520)
                },
                diagnosisConfidence = diagnosis.confidence,
                diagnosticEvidence = evidenceLines,
                faultLines = fault,
                codeLocation = code,
                sceneFields = scene(record, domain),
                previous = previous(record, allRecords),
            ),
        )
    }

    private fun faultNature(record: AppLogRecord, diagnosis: AppLogDiagnosis): String {
        val status = record.httpStatusCode.takeIf { it in 100..599 }
            ?: Regex("""(?i)\bHTTP(?:\s*状态码)?\s*[:：]?\s*(\d{3})\b""")
                .find(record.message + "\n" + record.details + "\n" +
                    record.diagnosticContext.entries.joinToString(" ") { it.key + ":" + it.value })
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when (diagnosis.category) {
            "字幕译文质量校验失败", "字幕响应编号/格式异常" -> if (status != null && status in 200..299) {
                "HTTP $status：服务端已返回响应。失败发生在收到响应后的字幕质量校验或编号/格式解析阶段；现有证据不支持将本次故障归类为网络连接中断或 HTTP 授权失败。"
            } else if (status != null) {
                "HTTP $status：同时记录到 HTTP 状态异常和字幕响应处理问题；需要以完整异常链和请求阶段确定首要故障。"
            } else {
                "已定位为字幕内容校验或响应格式处理问题，但没有可用 HTTP 状态证据；不推断网络或授权是否正常。"
            }
            "域名解析失败", "HTTPS/TLS 证书校验失败", "请求或处理超时" ->
                diagnosis.category + "；" + diagnosis.confidence
            else -> diagnosis.category + "；" + diagnosis.confidence
        }
    }

    private fun codeLocation(record: AppLogRecord): IncidentDocumentCodeLocation {
        val snapshot = record.exceptionSnapshot
        if (snapshot == null) {
            val callSite = value(record, "日志调用位置", "日志调用点", "调用位置") ?: "未采集"
            return IncidentDocumentCodeLocation(
                lines = listOf("无异常对象；位置仅为日志调用点：" + clip(callSite, 240)),
            )
        }

        val appFrames = AppLogAiReport.exceptionFrames(snapshot).filter {
            it.className.startsWith("io.translatetube.local.") &&
                !AppLogAiReport.isFrameworkNoiseFrame(it)
        }
        val firstFrame = record.firstAppStackFrame.ifBlank {
            appFrames.firstOrNull()?.let(AppLogAiReport::formatFrameForAi).orEmpty()
        }
        val extraFrames = appFrames.drop(1).map(AppLogAiReport::formatFrameForAi)
            .filter { it.isNotBlank() && it != firstFrame }
            .distinct().take(2).map { "应用栈帧：" + clip(it, 200) }

        val chain = mutableListOf<String>()
        var node: AppLogExceptionSnapshot? = snapshot
        var depth = 0
        while (node != null && depth < 12) {
            if (node.type.isNotBlank()) chain.add(AppLogAiReport.simpleErrorType(node.type))
            node = node.cause
            depth++
        }
        if (chain.isEmpty() && record.errorClass.isNotBlank()) {
            chain.add(AppLogAiReport.simpleErrorType(record.errorClass))
        }

        val lines = mutableListOf<String>()
        lines.add("首个应用栈帧：" + clip(firstFrame.ifBlank { "未采集" }, 240))
        if (chain.isNotEmpty()) lines.add("异常链：" + clip(chain.distinct().joinToString(" → "), 180))
        val suppressed = AppLogAiReport.suppressedTypes(snapshot)
            .map(AppLogAiReport::simpleErrorType).distinct().take(4)
        if (suppressed.isNotEmpty()) lines.add("suppressed：" + clip(suppressed.joinToString(" → "), 120))
        return IncidentDocumentCodeLocation(lines, extraFrames)
    }

    private fun scene(record: AppLogRecord, domain: String): List<IncidentDocumentField> {
        val stage = value(record, "当前处理阶段", "失败阶段", "故障阶段", "处理阶段", "当前 UI 阶段", "阶段")
        val step = value(record, "当前处理步骤", "最近处理步骤", "处理子步骤", "步骤")
        val stageStep = combine(listOf("阶段" to stage, "步骤" to step))

        return when (domain) {
            "字幕翻译" -> {
                val batch = translationBatch(record)
                listOf(
                    field(
                        "翻译引擎/批次",
                        combine(
                            listOf(
                                "引擎" to value(record, "翻译引擎名称", "当前翻译引擎", "翻译引擎", "翻译服务商名称", "翻译服务商", "模型名称", "模型 ID"),
                                "批次" to batch,
                                "批大小" to value(record, "本次翻译批次大小", "单批字幕行数", "批次大小来源"),
                            ),
                        ),
                    ),
                    field("输入字幕行数", value(record, "本批输入字幕行数", "输入字幕行数", "请求字幕行数", "本次需要翻译的字幕行数", "输入字幕总行数")),
                    field(
                        "有效译文数/拒绝行数",
                        combine(
                            listOf(
                                "有效译文" to value(record, "本批已获得有效译文行数", "已获得有效译文行数", "有效译文行数", "输出字幕行数", "已翻译行数", "模型返回行数"),
                                "拒绝行数" to value(record, "本批未通过校验字幕行数", "拒绝行数", "待翻译或校验未通过行数", "未完成译文行数"),
                            ),
                        ),
                    ),
                    field("HTTP 状态码", httpStatus(record)),
                    field(
                        "响应结构/响应摘要",
                        combine(
                            listOf(
                                "结构" to value(record, "响应结构", "响应格式", "结构化 HTTP 错误详情", "响应 Content-Type"),
                                "摘要" to value(record, "响应摘要", "模型原始响应前8行", "最后一次模型原始响应前8行", "响应文本字符数"),
                            ),
                            300,
                        ),
                        300,
                    ),
                    field("网络重试轨迹", value(record, "传输/响应/网络重试轨迹", "传输/响应/重试轨迹", "网络重试轨迹", "重试轨迹", "批次拆分恢复尝试", "本批重试次数")),
                    field("请求耗时", value(record, "本次请求耗时 ms", "最近一次 HTTP 请求耗时 ms", "请求耗时 ms", "请求耗时")),
                    field("当前处理阶段/步骤", stageStep),
                )
            }
            "语音合成" -> listOf(
                field(
                    "配音引擎/音色",
                    combine(
                        listOf(
                            "引擎" to value(record, "配音引擎名称", "当前配音引擎", "选定配音引擎", "本次选择的配音引擎", "目标配音引擎", "配音服务商", "系统 TTS 引擎包名"),
                            "音色" to value(record, "音色 ID", "当前选择的中文音色", "实际选用的系统音色", "当前默认中文音色", "目标音色名称", "音色"),
                        ),
                    ),
                ),
                field("字幕行号", value(record, "字幕行号", "字幕序号", "字幕索引", "当前配音字幕索引", "当前字幕索引", "失败字幕序号")),
                field("文本长度", value(record, "文本长度", "输入字符数", "待合成字数", "字符数")),
                field(
                    "输出音频时长/字节数",
                    combine(
                        listOf(
                            "时长" to value(record, "音频时长（秒）", "输出音频时长", "生成音频时长", "音频时长"),
                            "字节数" to value(record, "输出音频字节数", "音频字节数", "输出字节数", "PCM 音频文件字节数"),
                        ),
                    ),
                ),
                field(
                    "采样率/声道",
                    combine(
                        listOf(
                            "采样率" to value(record, "输出采样率 Hz", "标准化后采样率 Hz", "WAV 解码采样率 Hz", "PCM 采样率 Hz", "采样率"),
                            "声道" to value(record, "PCM 声道数", "声道数", "声道"),
                        ),
                    ),
                ),
                field(
                    "回调状态/失败分类",
                    combine(
                        listOf(
                            "回调" to value(record, "回调状态", "回调阶段", "系统 TTS 初始化回调状态码"),
                            "失败分类" to value(record, "失败分类", "失败阶段", "TTS 回调错误详情", "系统 TTS 回调失败原因", "失败原因"),
                        ),
                    ),
                ),
                field("当前处理阶段/步骤", stageStep),
            )
            "语音转写" -> listOf(
                field("转写引擎/模型", value(record, "转写引擎名称", "转写引擎", "本地 ASR 模型名称", "本地 ASR 模型 ID", "云端转写模型 ID", "云端模型 ID", "转写服务商")),
                field("音频时长", value(record, "转写音频时长 ms", "云端音频时长 ms", "PCM 音频时长 ms", "音频时长 ms", "音频时长")),
                field(
                    "分段索引/总数",
                    combine(
                        listOf(
                            "分段" to value(record, "当前正在识别分段起始序号", "当前分段索引", "分段索引", "预计分段数量"),
                            "总数" to value(record, "转写总分段", "本地 ASR 总分段数", "VAD 最终分段总数", "预计分段数量", "分段总数"),
                        ),
                    ),
                ),
                field("处理进度", value(record, "最近转写进度", "转写进度百分比", "处理进度", "累计已完成分段数", "当前 ASR 组内已完成分段数")),
                field("采样率", value(record, "PCM 采样率 Hz", "最终选中音频采样率", "采样率 Hz", "采样率")),
                field("当前处理阶段/步骤", stageStep),
            )
            "播放/音频输出" -> listOf(
                field("播放器状态", value(record, "播放器状态", "视频播放状态", "播放器阶段", "播放状态")),
                field("播放位置", value(record, "当前播放位置 ms", "视频播放位置 ms", "播放位置毫秒", "播放位置 ms", "起播调用后播放器位置 ms")),
                field(
                    "原声/配音音轨状态",
                    combine(
                        listOf(
                            "原声" to value(record, "原声状态", "原声是否静音", "原声音轨状态"),
                            "配音" to value(record, "配音状态", "中文配音开关", "是否开启配音", "是否启用配音", "配音音轨状态"),
                        ),
                    ),
                ),
                field(
                    "配音队列/当前字幕索引",
                    combine(
                        listOf(
                            "队列" to value(record, "剩余补播队列长度", "配音队列长度", "配音队列"),
                            "字幕索引" to value(record, "当前配音字幕索引", "当前视频字幕索引", "当前字幕索引", "字幕索引"),
                        ),
                    ),
                ),
                field("当前处理阶段/步骤", stageStep),
            )
            "视频解析/媒体获取", "下载/存储", "网络/接口请求" -> listOf(
                field("视频来源主机名", value(record, "媒体源主机名", "页面主机名", "网页主机名", "HTTP 请求主机名", "请求主机名", "目标主机")?.let(::hostOnly)),
                field("请求阶段", value(record, "云端请求阶段", "请求阶段", "解析阶段", "操作阶段")),
                field("HTTP 状态码", httpStatus(record)),
                field(
                    "候选源/解析器",
                    combine(
                        listOf(
                            "候选源" to value(record, "候选源", "媒体候选", "选择来源", "最终选择的来源"),
                            "解析器" to value(record, "解析器", "下载器", "提取方式", "媒体解析器"),
                        ),
                    ),
                ),
                field(
                    "Content-Type/响应长度",
                    combine(
                        listOf(
                            "类型" to value(record, "响应 Content-Type", "Content-Type", "内容类型"),
                            "长度" to value(record, "响应 Content-Length", "响应长度", "响应字节数", "响应内容长度"),
                        ),
                    ),
                ),
                field("重试决策", value(record, "重试决策", "恢复策略", "候选切换", "回退决策", "当前已使用重试次数", "重试次数")),
                field(
                    "预期/实际大小",
                    combine(
                        listOf(
                            "预期" to value(record, "预期大小", "预期文件大小"),
                            "实际" to value(record, "实际大小", "实际文件大小", "已下载字节数", "原始音频文件字节数", "文件大小"),
                        ),
                    ),
                ),
                field("当前处理阶段/步骤", stageStep),
            )
            else -> listOf(
                field("线程", record.thread.takeIf { it.isNotBlank() } ?: value(record, "异常线程", "线程")),
                field("当前处理阶段", stage),
                field("当前处理步骤", step),
                field("处理流程", value(record, "处理流程", "当前处理流程")),
                field("最后一个成功步骤", value(record, "最后一个成功步骤", "最近成功步骤", "最后成功步骤")),
            )
        }.take(8)
    }

    private fun engine(record: AppLogRecord, domain: String): String? = when (domain) {
        "字幕翻译" -> value(record, "翻译引擎名称", "当前翻译引擎", "翻译引擎", "翻译服务商名称", "模型名称", "模型 ID")
        "语音合成" -> value(record, "配音引擎名称", "当前配音引擎", "选定配音引擎", "本次选择的配音引擎", "配音服务商", "系统 TTS 引擎包名")
        "语音转写" -> value(record, "转写引擎名称", "转写引擎", "本地 ASR 模型名称", "云端转写模型 ID", "转写服务商")
        else -> value(record, "配音引擎名称", "当前配音引擎", "转写引擎名称", "翻译引擎名称", "当前翻译引擎", "解析器")
    }

    private fun translationBatch(record: AppLogRecord): String? {
        val current = value(record, "当前批次进度", "当前批次", "当前翻译批次", "批次索引") ?: return null
        if (current.contains("/")) return current
        val total = value(record, "翻译总批次数", "翻译批次总数", "总批次数")
        return if (total.isNullOrBlank()) current else current + "/" + total
    }

    private fun httpStatus(record: AppLogRecord): String? =
        value(record, "HTTP 状态码", "从异常中提取的 HTTP 状态码", "可识别 HTTP 状态码", "HTTP 错误状态码")
            ?: record.httpStatusCode.takeIf { it in 100..599 }?.toString()

    private fun field(name: String, value: String?, max: Int = 120) =
        IncidentDocumentField(name, clip(value ?: "未采集", max))

    private fun combine(parts: List<Pair<String, String?>>, max: Int = 120): String {
        val raw = parts.joinToString("；") { part ->
            part.first + "=" + (part.second?.takeIf { it.isNotBlank() } ?: "未采集")
        }
        return clip(raw, max)
    }

    private fun value(record: AppLogRecord, vararg aliases: String): String? {
        aliases.forEach { alias ->
            val exact = record.diagnosticContext[alias]?.takeIf { it.isNotBlank() }
            if (exact != null) return line(exact)
            val found = record.diagnosticContext.entries.firstOrNull { entry ->
                entry.key.contains(alias, ignoreCase = true) && entry.value.isNotBlank()
            }?.value
            if (!found.isNullOrBlank()) return line(found)
        }
        return null
    }

    private fun hostOnly(raw: String): String {
        val safe = line(raw)
        val uriHost = runCatching {
            java.net.URI(if (safe.contains("://")) safe else "https://" + safe).host
        }.getOrNull()
        val host = uriHost ?: safe.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return clip(host.removePrefix("www.").ifBlank { "未采集" }, 120)
    }

    private fun previous(selected: AppLogRecord, all: List<AppLogRecord>): IncidentDocumentPrevious {
        val candidates = all.asSequence()
            .filter { AppLogEventRelationPolicy.eventKey(it) != AppLogEventRelationPolicy.eventKey(selected) }
            .filter {
                it.timestampMs < selected.timestampMs ||
                    (it.timestampMs == selected.timestampMs && it.sequence < selected.sequence)
            }
            .let { events ->
                when {
                    selected.sessionId.isNotBlank() && selected.operationId.isNotBlank() ->
                        events.filter {
                            it.sessionId == selected.sessionId && it.operationId == selected.operationId
                        }
                    selected.operationId.isBlank() -> {
                        val cutoff = selected.timestampMs - THREAD_WINDOW_MS
                        events.filter { it.timestampMs in cutoff..selected.timestampMs && sameThread(selected, it) }
                    }
                    else -> events.filter { false }
                }
            }
            .sortedWith(compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence })
            .toList()
            .takeLast(3)
            .map { event ->
                AppLogStore.formatTime(event.timestampMs) + " " +
                    clip(event.level, 12) + " " + clip(event.tag, 32) + " " + clip(event.message, 80)
            }

        val scope = when {
            selected.sessionId.isNotBlank() && selected.operationId.isNotBlank() ->
                "前序范围：同 sessionId + operationId"
            selected.operationId.isBlank() ->
                "前序范围：仅同线程（operationId 未记录，时间窗 2 分钟）"
            else ->
                "前序范围：sessionId 未记录，无法验证同一任务"
        }
        val recovery = if (
            selected.level.uppercase() in setOf("WARN", "ERROR", "FATAL") && selected.issueKey.isNotBlank()
        ) {
            val recovered = selected.recoveredAtMs ?: all.asSequence()
                .filter { it.resolvesIssueKey == selected.issueKey && it.timestampMs >= selected.timestampMs }
                .minByOrNull { it.timestampMs }?.timestampMs
            if (recovered == null) "尚无恢复记录" else "已恢复 " + AppLogStore.formatTime(recovered)
        } else null
        return IncidentDocumentPrevious(
            scopeLine = scope,
            events = candidates,
            recoveryLine = recovery,
            includeCodeLocationGuidance = selected.level.uppercase() in setOf("WARN", "ERROR", "FATAL"),
        )
    }

    private fun sameThread(a: AppLogRecord, b: AppLogRecord): Boolean {
        if (a.threadId > 0L && b.threadId > 0L) return a.threadId == b.threadId
        return a.thread.isNotBlank() && a.thread == b.thread
    }

    private fun fit(input: IncidentDocument): IncidentDocument {
        var out = input
        var eventCount = out.previous.events.size
        while (out.toPlainText().length > MAX_CHARS && eventCount > 0) {
            eventCount--
            out = out.copy(previous = out.previous.copy(events = out.previous.events.takeLast(eventCount)))
        }
        if (out.toPlainText().length > MAX_CHARS) {
            out = out.copy(
                diagnosticEvidence = out.diagnosticEvidence.take(3).map { clip(it, 320) },
                sceneFields = out.sceneFields.map { it.copy(value = clip(it.value, 80)) },
                symptom = clip(out.symptom, 320),
                directCause = clip(out.directCause, 720),
                faultNature = clip(out.faultNature, 360),
            )
        }
        if (out.toPlainText().length > MAX_CHARS) {
            out = out.copy(codeLocation = out.codeLocation?.copy(additionalFrames = emptyList()))
        }
        if (out.toPlainText().length > MAX_CHARS) {
            out = out.copy(
                headerLine = clip(out.headerLine, 260),
                faultLines = out.faultLines.map { clip(it, 220) },
                codeLocation = out.codeLocation?.copy(lines = out.codeLocation.lines.map { clip(it, 180) }),
                sceneFields = out.sceneFields.map { it.copy(value = clip(it.value, 48)) },
            )
        }
        return out
    }

    private fun line(text: String): String =
        AppLogStore.redactForExport(text)
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun clip(text: String, max: Int): String {
        val safe = line(text)
        if (safe.length <= max) return safe
        val marker = "[已截断，原长 " + safe.length + "]"
        val budget = (max - marker.length - 1).coerceAtLeast(0)
        if (budget == 0) return marker.take(max)
        return AppLogAiReport.clipForAi(safe, budget) + " " + marker
    }
}
