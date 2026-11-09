package io.translatetube.local.util

import java.util.Locale

/**
 * Converts recorded evidence into a cautious, actionable diagnosis.
 * It never claims a speculative explanation as a proven root cause.
 */
data class AppLogDiagnosis(
    val category: String,
    val confidence: String,
    val finding: String,
    val evidence: String,
    val nextSteps: List<String>,
)

object AppLogDiagnostics {
    fun analyze(record: AppLogRecord): AppLogDiagnosis {
        val currentDetails = focusedDetails(record.details)
        val raw = buildString {
            append(record.category).append('\n')
            append(record.tag).append('\n')
            append(record.message).append('\n')
            append(record.errorClass).append('\n')
            append(record.rootCauseMessage).append('\n')
            var exceptionNode = record.exceptionSnapshot
            while (exceptionNode != null) {
                append(exceptionNode.type).append(": ").append(exceptionNode.message).append('\n')
                exceptionNode = exceptionNode.cause
            }
            record.diagnosticContext.toSortedMap().forEach { (key, value) ->
                append(key).append('：').append(value).append('\n')
            }
            if (record.httpStatusCode in 100..599) {
                append("HTTP 状态码：").append(record.httpStatusCode).append('\n')
            }
            append(currentDetails)
        }
        val text = raw.lowercase(Locale.ROOT)
        val tag = record.tag.lowercase(Locale.ROOT)

        fun hasAny(vararg terms: String): Boolean = terms.any { text.contains(it.lowercase(Locale.ROOT)) }
        fun result(
            category: String,
            confidence: String,
            finding: String,
            terms: List<String>,
            steps: List<String>,
        ) = AppLogDiagnosis(
            category = category,
            confidence = confidence,
            finding = finding,
            evidence = evidence(record, terms),
            nextSteps = steps,
        )

        // Dedicated verifier/linker rules precede broad runtime and feature fallbacks.
        val verifyError = record.errorClass.contains("VerifyError", ignoreCase = true) ||
            Regex("""(?i)failed to verify|verifier rejected""").containsMatchIn(raw)
        if (verifyError) {
            val source = listOf(record.rootCauseMessage, record.message, currentDetails).joinToString("\n")
            val instruction = Regex("""(?i)\[0x[0-9a-f]+\][^\r\n]*""").find(source)?.value
            val dex = Regex("""(?i)L?((?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+);->([A-Za-z0-9_$<>]+)\(""").find(source)
            val java = Regex("""(?i)((?:[A-Za-z0-9_$]+\.)*[A-Za-z0-9_$]+)\.([A-Za-z0-9_$<>]+)\(""").find(source)
            val method = dex ?: java
            val methodLabel = method?.let {
                it.groupValues[1].substringAfterLast('/').substringAfterLast('.').removePrefix("L").removeSuffix(";") + "." + it.groupValues[2]
            }
            val largeRegister = Regex("""\bv(\d+)\b""").findAll(instruction.orEmpty()).any {
                it.groupValues[1].toIntOrNull()?.let { number -> number > 255 } == true
            }
            val finding = "这是构建产物（D8/R8 生成的 dex）被系统校验器拒绝，不是业务逻辑错误；优先检查报错方法是否过大。" +
                if (largeRegister) " 寄存器编号超过 255，方法很可能过大。" else ""
            val evidence = listOfNotNull(methodLabel?.let { "报错方法：$it" }, instruction?.let { "校验指令：$it" })
                .joinToString("；").ifBlank { "异常文本包含 VerifyError/Verifier rejected。" }
            return AppLogDiagnosis(
                "字节码校验失败（编译产物问题）", "已确认", finding, evidence,
                listOf(
                    "找到报错方法并统计行数，把其中的协程作用域、启动器、本地函数拆到独立的类或 @Composable 里。",
                    "重新构建后在同一 Android 版本上复测。",
                    "仍然失败时升级 AGP/D8 版本再试。",
                ),
            )
        }

        val linkerTypes = listOf("NoSuchMethodError", "NoClassDefFoundError", "ClassNotFoundException",
            "IncompatibleClassChangeError", "AbstractMethodError", "UnsatisfiedLinkError")
        val linkerType = linkerTypes.firstOrNull { raw.contains(it, ignoreCase = true) }
        if (linkerType != null) {
            val line = listOf(record.rootCauseMessage, record.message, currentDetails).firstNotNullOfOrNull { candidate ->
                candidate.lineSequence().firstOrNull { it.contains(linkerType, ignoreCase = true) }
            }?.trim()
            val missingClass = Regex("""(?i)(?:NoClassDefFoundError|ClassNotFoundException)\s*:\s*([^\s;]+)""")
                .find(raw)?.groupValues?.getOrNull(1)
            val library = Regex("""(?i)lib[A-Za-z0-9_.+-]+\.so""").find(raw)?.value
            val methodSignature = Regex("""(?i)(?:No virtual method|No static method|No interface method|method)\s+([^\s]+)""")
                .find(raw)?.groupValues?.getOrNull(1)
            val ownerClass = Regex("""(?i)\bin class L?([^;\s]+);""")
                .find(raw)?.groupValues?.getOrNull(1)?.replace('/', '.')
            val target = when {
                !library.isNullOrBlank() -> "缺失原生库：$library"
                !missingClass.isNullOrBlank() -> "缺失类：$missingClass"
                !methodSignature.isNullOrBlank() -> "缺失方法：$methodSignature" +
                    ownerClass?.let { "；所属类：$it" }.orEmpty()
                !line.isNullOrBlank() -> line.substringAfter(':', line).take(220)
                else -> linkerType
            }
            return AppLogDiagnosis(
                "类/方法/原生库链接失败", "链接错误已确认",
                "多为依赖版本冲突、API 级别不足（minSdk 24 的设备上缺少该 API）、脱糖缺失，或原生库的 ABI 不匹配。",
                "异常类型：$linkerType；$target",
                listOf(
                    "核对该 API 的最低系统版本，并确认 minSdk 24 设备可用。",
                    "检查 Gradle 依赖树中的版本冲突及 core library desugaring 配置。",
                    "UnsatisfiedLinkError 时核对 abiFilters、APK 内原生库目录与设备 ABI。",
                ),
            )
        }

        if (
            hasAny("remoteserviceexception", "bad notification") &&
            hasAny("couldn't inflate contentviews", "contentviews") &&
            hasAny("concurrentmodificationexception")
        ) {
            val notificationId = Regex("""(?i)\bid=(-?\d+)""")
                .find(raw)?.groupValues?.getOrNull(1)
            val idEvidence = notificationId?.let { "系统异常消息报告的通知 ID=$it" }
                ?: "系统异常消息未提取到通知 ID"
            return result(
                "Android 通知内容视图膨胀失败",
                "系统通知失败已确认；具体并发冲突根因仍待验证",
                "Android 在展开通知 contentViews 时报告 RemoteServiceException，异常消息提及 ConcurrentModificationException。该信息能确认通知显示阶段失败，但不一定包含 SystemUI 内部真实异常对象的调用栈，也不能仅凭这一行断定是通知 ID 冲突。$idEvidence；需要对照通知提交审计记录确认当时哪类通知占用了该 ID。",
                listOf("RemoteServiceException", "Bad notification", "Couldn't inflate contentViews", "ConcurrentModificationException"),
                listOf(
                    "先用系统异常消息中的通知 ID，匹配崩溃前最近的 NotificationDiagnostics 记录，核对通知用途、样式、操作按钮数和提交线程。",
                    "如果异常对象链没有真正的 ConcurrentModificationException，只是在异常消息文本中出现该名称，就不能把它当成已取得内部栈的根因；继续检查同一通知 ID 是否被不同通知用途复用。",
                    "检查通知构建后是否仍修改共享 Notification/RemoteViews/Bundle/动作集合，以及是否有多个线程同时提交同一 ID。",
                    "再次复现时保留完整异常详情与通知审计轨迹；如果新日志未记录系统内部修改栈，应明确注明这是 Android 系统层证据缺口，而不是猜测 App 的具体修改行。",
                ),
            )
        }
        if (hasAny("outofmemoryerror", "failed to allocate", "memory allocation failed", "内存不足")) {
            return result(
                "内存分配失败",
                "异常类型已确认",
                "运行过程中申请内存失败；若发生在加载模型、缓存音频或解析大型清单时，可能由瞬时内存峰值触发。",
                listOf("OutOfMemoryError", "failed to allocate", "内存不足"),
                listOf(
                    "对照日志里的 Java 堆、Native 堆和系统可用内存数值。",
                    "检查故障前是否同时加载转写/配音模型、缓存整段音频或解析大量文件。",
                    "保留发生时的操作步骤和视频长度，避免只根据设备总 RAM 推断原因。",
                ),
            )
        }
        if (hasAny("reason_anr", "应用无响应", "application not responding", "anr trace")) {
            return result(
                "应用无响应（ANR）",
                "系统/日志已记录 ANR 证据",
                "应用在限定时间内未能响应；具体阻塞点需要结合线程跟踪或主线程调用栈确认。",
                listOf("REASON_ANR", "ANR", "应用无响应"),
                listOf(
                    "优先查看系统跟踪中主线程正在执行或等待的调用。",
                    "检查主线程是否执行同步网络/文件 I/O、长循环或等待锁。",
                    "将 ANR 时间与模型初始化、音视频解码及页面切换日志对齐。",
                ),
            )
        }
        if (tag.contains("webplayerconsole") &&
            hasAny("beforeeach", "nuxt") &&
            hasAny("undefined", "error caught during app initialization")
        ) {
            return result(
                "网页端 Nuxt/路由初始化异常",
                "报错现象已确认，根因仍待验证",
                "网页脚本在前端应用初始化期间访问了未定义对象的 beforeEach；这能确认页面 JavaScript 初始化失败，但单条控制台错误不能证明是 App 的注入代码导致。",
                listOf("beforeEach", "nuxt", "undefined"),
                listOf(
                    "核对同一时间是否有主文档或 JavaScript chunk 的 onReceivedError / HTTP 错误。",
                    "对照 WebView 内核版本、页面标题和 UA 标记，确认页面采用的浏览器兼容分支。",
                    "用系统浏览器打开同一分享链接作对照；不要只凭这个错误直接判定为网页本身或 App 的单一责任。",
                    "查看稍后的页面 DOM 检查结果，确认是脚本报错但仍有内容，还是应用根节点确实为空。",
                ),
            )
        }
        if (tag.contains("pikpakpagerender") || (hasAny("白屏", "疑似未渲染") && hasAny("webview", "pikpak"))) {
            return result(
                "网页主体未完成渲染",
                "检测到异常现象，具体原因待验证",
                "日志显示网页加载回调已完成，但 DOM 检查怀疑主体没有渲染；这不等同于已确认网络断开或文件夹过于复杂。",
                listOf("主体疑似未渲染", "body", "应用根节点", "WebView"),
                listOf(
                    "先看 body 子元素数、HTML 长度和应用根节点子元素数，确认页面是否真空白。",
                    "检查同一时间的 JavaScript 初始化错误和主文档/脚本资源加载失败。",
                    "核对 WebView 内核版本与 UA 是否仍带 wv 标记。",
                    "将清单读取日志与页面渲染日志分开判断：原生清单请求失败不应单独作为网页白屏的结论。",
                ),
            )
        }
        if (hasAny("unknownhostexception", "unable to resolve host", "no address associated with hostname", "找不到主机")) {
            return result(
                "域名解析失败",
                "异常类型已确认",
                "请求阶段未能把主机名解析为 IP；通常发生在 DNS、当前网络或目标域名解析链路。",
                listOf("UnknownHostException", "unable to resolve host", "找不到主机"),
                listOf(
                    "查看专项诊断快照里的网络连接状态。",
                    "确认同一网络下目标网站/API 能否在浏览器中访问，必要时更换网络或 DNS。",
                    "若网络已连接，记录失败的目标主机名和发生时间，但不要复制含令牌的完整 URL。",
                ),
            )
        }
        if (hasAny("sslhandshakeexception", "certpathvalidatorexception", "certificate_unknown", "trust anchor")) {
            return result(
                "HTTPS/TLS 证书校验失败",
                "异常类型已确认",
                "TLS 握手或证书链验证失败；可能与证书链、系统时间、代理拦截或服务端 TLS 配置有关。",
                listOf("SSLHandshakeException", "CertPathValidatorException", "trust anchor"),
                listOf(
                    "核对设备日期和时间是否正确。",
                    "检查异常对应的主机，以及该主机证书链是否完整。",
                    "确认网络代理/抓包证书是否影响 HTTPS，不要通过关闭证书校验来绕过问题。",
                ),
            )
        }
        val recordedHttpError = (
            Regex("""(?i)\bHTTP(?:\s*状态码)?\s*[:：]?\s*(\d{3})\b""").find(raw)
                ?: Regex("""(?i)\bHTTP/\d(?:\.\d)?\s+(\d{3})\b""").find(raw)
            )?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { it in 400..599 } == true
        if (!recordedHttpError && hasAny("sockettimeoutexception", "timeout", "timed out", "超时")) {
            return result(
                "请求或处理超时",
                "超时现象已确认，具体阶段待定位",
                "某个操作没有在超时时限内完成；需要用诊断上下文区分连接、读取响应、远程任务轮询还是本地处理耗时。",
                listOf("SocketTimeoutException", "timeout", "超时"),
                listOf(
                    "查看错误上下文中的操作阶段、请求类型、重试次数和耗时字段。",
                    "若连接已建立但读取超时，核对响应大小、服务器负载和网络稳定性。",
                    "若为云端转写任务，区分提交超时、轮询超时和本地时间轴整理失败。",
                ),
            )
        }
        val httpMatch = Regex("""(?i)\bHTTP(?:\s*状态码)?\s*[:：]?\s*(\d{3})\b""").find(raw)
            ?: Regex("""(?i)\bHTTP/\d(?:\.\d)?\s+(\d{3})\b""").find(raw)
        val httpCode = httpMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpCode != null && httpCode in 400..599) {
            val category: String
            val finding: String
            val steps: List<String>
            when (httpCode) {
                401 -> {
                    category = "HTTP 401：认证未通过"
                    finding = "服务器拒绝了未通过认证的请求；优先检查 API Key/登录状态是否有效，不能仅凭状态码判断密钥已过期。"
                    steps = listOf("核对服务商、密钥是否配置到当前引擎，以及请求使用的认证方式。", "查看响应错误正文中的服务端错误码；日志必须先脱敏再分享。")
                }
                403 -> {
                    category = "HTTP 403：服务器拒绝访问"
                    finding = "服务器明确拒绝了请求；可能是权限/分享校验、反自动化策略、请求头、来源限制或签名地址问题。"
                    steps = listOf("查看请求阶段、响应类型和服务器返回的错误说明。", "对比网页内请求与原生请求所带的 Referer/UA/授权状态，勿输出 Cookie 或签名参数。", "若是分享页，确认已完成官方密码/验证码流程。")
                }
                404 -> {
                    category = "HTTP 404：资源不存在"
                    finding = "服务端没有找到请求资源；可能是地址失效、资源被移除、路径错误或临时媒体地址过期。"
                    steps = listOf("确认失败的是网页主文档、API、HLS 清单还是音频/视频分段。", "若为临时媒体地址，重新从原网页获取资源后再试。")
                }
                408, 504 -> {
                    category = "HTTP " + httpCode + "：请求等待超时"
                    finding = "服务器或网关未能在时限内完成请求；需检查资源体积、网络质量和服务端响应。"
                    steps = listOf("查看实际耗时、请求阶段和响应是否已有部分数据。", "稍后重试并对比同一资源的响应情况。")
                }
                429 -> {
                    category = "HTTP 429：请求频率受限"
                    finding = "服务端正在限制请求频率；连续立即重试可能进一步延长限制。"
                    steps = listOf("遵循服务端 Retry-After（若提供），降低并发或延长重试间隔。", "核对同一操作是否被多个后台任务重复发起。")
                }
                in 500..599 -> {
                    category = "HTTP " + httpCode + "：服务端或网关错误"
                    finding = "服务端/网关返回失败状态；这证明请求未被正常处理，但不能单独说明是 App 代码还是服务端故障。"
                    steps = listOf("检查响应正文中的服务端错误码及请求阶段。", "同一资源稍后重试；若持续发生，保留时间、状态码、服务商和脱敏后的响应摘要。")
                }
                else -> {
                    category = "HTTP " + httpCode + "：请求被拒绝或未成功"
                    finding = "HTTP 请求返回非成功状态，需结合接口错误正文和请求阶段继续定位。"
                    steps = listOf("查看接口返回的错误码/摘要，而不只看 HTTP 状态。", "确认请求地址类型、认证方式和请求参数是否符合接口要求。")
                }
            }
            return result(
                category,
                "HTTP 状态码已确认",
                finding,
                listOf("HTTP " + httpCode, "HTTP 状态码"),
                steps,
            )
        }
        if (hasAny("arrayindexoutofboundsexception", "indexoutofboundsexception", "index out of bounds")) {
            return result(
                "数组/缓冲区索引越界",
                "异常类型已确认",
                "代码访问了超出有效范围的数组或缓冲区位置；如果发生在音频合成/切片流程，应对照索引、长度、采样点数和切片边界。",
                listOf("ArrayIndexOutOfBoundsException", "IndexOutOfBoundsException", "index out of bounds"),
                listOf(
                    "看完整根因链中第一个 App 自有代码栈帧，定位越界发生在哪个函数。",
                    "对照日志中的 buffer length、index、PCM 样本数和切片起止位置。",
                    "检查长度为零、末尾边界和并发释放后访问等情况；不要只增加数组长度来掩盖越界。",
                ),
            )
        }
        if (hasAny("audiotrack", "state_uninitialized", "error_dead_object", "audio track write", "音频轨道")) {
            return result(
                "Android 音频输出初始化/写入异常",
                "音频输出故障已记录",
                "音频输出轨道初始化或写入环节出现异常；需要区分轨道状态、采样格式、通道配置、缓冲区大小和输出设备切换。",
                listOf("AudioTrack", "STATE_UNINITIALIZED", "ERROR_DEAD_OBJECT", "音频轨道"),
                listOf(
                    "查看采样率、声道数、PCM 格式、缓冲区大小和 write 返回值/异常。",
                    "对照专项诊断快照中的系统音量和输出设备。",
                    "若轨道在写入前处于 STATE_NO_STATIC_DATA，确认是否错误地把它当作初始化失败；记录写入后状态。",
                ),
            )
        }
        if (hasAny("texttospeech", "system tts", "系统 tts", "配音合成失败", "语音合成失败", "speech synthesis")) {
            return result(
                "语音合成失败",
                "合成故障已记录，具体原因以根因链为准",
                "字幕文本未能稳定生成可播放音频；问题可能出现在引擎初始化、服务回调、返回音频格式或 PCM 校验阶段。",
                listOf("TextToSpeech", "System TTS", "语音合成失败", "配音合成失败"),
                listOf(
                    "先看异常根因链最深层异常，再区分系统 TTS 服务、云端 TTS、解码还是 PCM 校验。",
                    "核对当前引擎名、文本长度、音频字节数、采样率、声道和超时/回调状态。",
                    "系统 TTS 不应被误判为缺少本地配音模型；确认设备中文语音数据与默认 TTS 服务可用。",
                ),
            )
        }
        if (hasAny("transcrib", "whisper", "sensevoice", "云端转写", "词级时间戳", "时间轴字幕")) {
            return result(
                "语音转写/时间轴生成失败",
                "转写环节已定位，需结合异常细节判断",
                "故障出现在音频准备、识别请求、识别结果解析或时间戳整理链路；具体子阶段以错误上下文为准。",
                listOf("转写", "Whisper", "SenseVoice", "词级时间戳", "时间轴字幕"),
                listOf(
                    "检查音频文件是否存在、大小是否合理、WAV/PCM 采样率和时长是否有效。",
                    "云端转写要核对 HTTP 状态、模型/接口权限和返回数据是否包含时间戳。",
                    "本地转写要对照模型完整性、CPU/ABI、内存和音频分段数量。",
                ),
            )
        }
        val translationScope = tag.contains("translation") || hasAny("翻译", "translation", "字幕译文")
        val qualityRejected = translationScope && (
            hasAny(
                "dominantforeigntextreason", "译文质量校验", "字幕质量校验",
                "外文字母数", "汉字数", "未通过质量校验",
            ) ||
                (hasAny("质量校验", "质量规则") && hasAny("未通过", "拒绝", "失败", "重试"))
            )
        if (qualityRejected) {
            val status = record.httpStatusCode.takeIf { it in 100..599 }
                ?: Regex("""(?i)\bHTTP(?:\s*状态码)?\s*[:：]?\s*(\d{3})\b""")
                    .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val batch = Regex("""第\s*\d+\s*批""").find(raw)?.value
            val row = Regex("""第\s*\d+\s*行(?:\s*[（(]索引\s*\d+[）)])?""")
                .find(raw)?.value
            val hanCount = Regex("""汉字(?:数|数量)?\s*[=:：]?\s*(\d+)""")
                .find(raw)?.groupValues?.getOrNull(1)
            val latinCount = Regex("""外文字母(?:数|数量)?\s*[=:：]?\s*(\d+)""")
                .find(raw)?.groupValues?.getOrNull(1)
            val retriesExhausted = hasAny(
                "重试耗尽", "耗尽重试", "重试后仍", "反复重试",
                "最终中止", "最终停止", "retry exhausted", "attempts exhausted",
            )
            val finding = buildString {
                append("模型已返回译文，但应用的字幕质量规则拒绝了至少一行译文，")
                if (!batch.isNullOrBlank() || !row.isNullOrBlank()) {
                    append("失败位置：")
                    append(listOfNotNull(batch, row).joinToString("，"))
                    append("；")
                }
                if (hanCount != null && latinCount != null) {
                    append("该行汉字数为 ").append(hanCount)
                        .append("，外文字母数为 ").append(latinCount)
                        .append("，未满足当前外文占比校验条件；")
                }
                append(if (retriesExhausted) {
                    "同一译文经重试仍未通过校验，最终停止整条翻译任务。"
                } else {
                    "当前记录显示该译文未通过质量校验；是否已耗尽重试次数，以重试轨迹为准。"
                })
            }
            val cause = record.rootCauseMessage.ifBlank {
                record.exceptionSnapshot?.message.orEmpty()
            }
            val evidenceText = listOfNotNull(
                cause.takeIf { it.isNotBlank() },
                listOfNotNull(batch, row).joinToString("，").takeIf { it.isNotBlank() },
                if (hanCount != null && latinCount != null) "汉字数=$hanCount，外文字母数=$latinCount" else null,
                status?.let { "HTTP 状态码：$it" },
                evidence(record, listOf("质量校验", "外文字母数", "汉字数", "重试轨迹")),
            ).distinct().joinToString("；").take(900)
            return AppLogDiagnosis(
                "字幕译文质量校验失败",
                if (retriesExhausted) "质量校验拒绝及重试耗尽已确认" else "质量校验拒绝已确认；重试结果需核对",
                finding,
                evidenceText.ifBlank { record.message.take(360) },
                listOf(
                    "查看未通过校验的字幕行及具体命中规则，确认是否为专有名词、品牌名或原文保留词。",
                    "核对该行的汉字数、外文字母数和质量规则阈值；不要把正常专有名词一概当作未翻译内容。",
                    "检查重试轨迹，确认失败是否来自质量校验，而不是请求、解析或授权阶段。",
                ),
            )
        }

        val responseFormatRejected = translationScope && (
            hasAny(
                "混用了0起始和1起始边界", "混用0起始和1起始", "0起始和1起始",
                "编号混用", "mixed zero-based and one-based", "mixed 0-based and 1-based",
            ) ||
                (hasAny("响应格式不完整", "字幕编号", "编号不完整") &&
                    hasAny("parsemodeltranslations", "parsenumbered", "0起始", "1起始", "编号混用"))
            )
        if (responseFormatRejected) {
            val status = record.httpStatusCode.takeIf { it in 100..599 }
                ?: Regex("""(?i)\bHTTP(?:\s*状态码)?\s*[:：]?\s*(\d{3})\b""")
                    .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val batch = Regex("""第\s*\d+\s*批""").find(raw)?.value
            val rowRange = Regex("""(?:索引|index)\s*[：:=]?\s*\d+\s*(?:-|–|~|至)\s*\d+""", RegexOption.IGNORE_CASE)
                .find(raw)?.value
            val rows = Regex("""(?:本批输入字幕行数|输入字幕行数|批次大小)\s*[：:=]?\s*\d+""")
                .find(raw)?.value
            val recoverySkipped = hasAny(
                "未纳入可恢复异常白名单", "未触发行级重试", "没有触发行级修复",
                "未执行行级重试", "未触发同批重试",
            )
            val finding = buildString {
                append("模型已返回响应，但字幕编号同时采用 0 起始和 1 起始规则，解析器无法安全地将译文逐行对应回原字幕。")
                if (!batch.isNullOrBlank()) append("失败发生在").append(batch).append("；")
                if (!rows.isNullOrBlank()) append(rows).append("；")
                if (!rowRange.isNullOrBlank()) append(rowRange).append("；")
                append("为避免字幕错位，解析器拒绝了这批结果并终止当前翻译流程。")
                if (recoverySkipped) append("该异常没有进入可恢复异常路径，因此未执行行级修复。")
                if (status != null && status in 200..299) {
                    append(" HTTP ").append(status).append(" 表明服务端已返回响应，故障在响应编号/格式解析阶段。")
                }
            }
            val cause = record.rootCauseMessage.ifBlank {
                record.exceptionSnapshot?.message.orEmpty()
            }
            val evidenceText = listOfNotNull(
                cause.takeIf { it.isNotBlank() },
                listOfNotNull(batch, rows, rowRange).joinToString("，").takeIf { it.isNotBlank() },
                status?.let { "HTTP 状态码：$it" },
                evidence(record, listOf("编号", "响应格式", "批次", "重试轨迹")),
            ).distinct().joinToString("；").take(900)
            return AppLogDiagnosis(
                "字幕响应编号/格式异常",
                "编号对齐失败已确认",
                finding,
                evidenceText.ifBlank { record.message.take(360) },
                listOf(
                    "检查模型原始响应中的编号格式，确认是否在同一批次混用了 0 起始和 1 起始编号。",
                    "核对解析器拒绝位置及输入行数，不能通过简单整体加一或减一来猜测对齐关系。",
                    "检查同批重试、结构化 JSON 修复和失败批次拆分是否按预期执行。",
                ),
            )
        }

        if (hasAny("translation", "翻译", "translationengine") && hasAny("exception", "失败", "error", "无效", "empty", "解析")) {
            return result(
                "字幕翻译失败",
                "翻译环节已定位，需结合请求证据判断",
                "故障出现在译文请求、返回内容解析或字幕合并环节；仅凭“翻译失败”不能断定是密钥、网络还是服务端的问题。",
                listOf("翻译", "Translation", "HTTP", "异常"),
                listOf(
                    "查看服务商、接口方式、HTTP 状态码与脱敏后的服务端错误摘要。",
                    "检查响应是否为 JSON、译文字段是否为空，以及批次/字幕行号是否记录。",
                    "确认失败后有没有触发重试或备用引擎，并区分请求失败与字幕合并失败。",
                ),
            )
        }
        if (hasAny("modeldownload", "model download", "模型下载", "模型安装", "zipException", "unexpected end of file", "no space left", "sha-256")) {
            return result(
                "模型下载/安装异常",
                "模型操作环节已定位",
                "故障出现在模型下载、解压、校验或原子安装流程；需要根据异常类型区分网络中断、归档损坏、空间不足和完整性校验失败。",
                listOf("ModelDownload", "模型安装", "ZipException", "No space left", "SHA-256"),
                listOf(
                    "核对下载字节数/总大小、文件校验结果、解压阶段和最终完整性检查。",
                    "查看专项诊断快照中的应用目录可用空间。",
                    "检查是否存在被中断的临时包或备份目录；不要手动删除唯一的有效模型备份。",
                ),
            )
        }
        val hasBusinessOutcomeEvidence = hasAny(
            "没有确认可直接播放", "未发现可播放", "候选源总数", "已验证可访问的候选源数",
            "请求完成，但没有", "未启动 yt-dlp", "no playable source", "empty result",
        )
        if ((tag.contains("websourcedetection") || tag.contains("websourceverify") ||
                tag.contains("hls") || tag.contains("webvideoparse") || tag.contains("pikpak")) &&
            !hasBusinessOutcomeEvidence
        ) {
            return result(
                "网页媒体源检测/解析异常",
                "失败模块已定位，根因证据不足",
                "问题位于网页媒体发现、候选源验证、HLS 清单读取或分享文件清单流程；应先区分网页渲染、接口响应和媒体地址校验这几条独立链路。",
                listOf(record.tag, "HTTP", "检测步骤", "页面主机名", "异常类型"),
                listOf(
                    "按时间顺序检查网页加载、JavaScript 错误、DOM 渲染、网络 hook、候选源发现和 HTTP 校验日志。",
                    "查看状态码、Content-Type、请求阶段和异常类；仅有“未发现候选源”不能说明网页没有视频。",
                    "对 PikPak，先验证官方网页是否正常渲染，再单独判断分享清单读取结果。",
                ),
            )
        }
        if (hasAny("jsonexception", "jsonsyntaxexception", "unexpected token", "无法解析响应", "返回的内容无法解析")) {
            return result(
                "服务端响应/本地数据解析失败",
                "解析失败已确认",
                "输入内容未符合当前解析器期望的结构；可能是响应格式变化、错误页被当成数据解析或数据不完整。",
                listOf("JSONException", "JsonSyntaxException", "无法解析", "Unexpected token"),
                listOf(
                    "核对响应状态码、Content-Type、响应长度和解析失败的位置。",
                    "只保留已脱敏的响应摘要，不记录整个授权响应正文。",
                    "若接口最近变更，检查字段名、字段类型和空值兼容处理。",
                ),
            )
        }

        val hasExceptionTrace = Regex("""(?m)^#\d+\s+[\w.$]+""").containsMatchIn(record.details)
        if (hasExceptionTrace) {
            val causeLine = record.details.lineSequence().lastOrNull { it.trim().matches(Regex("""#\d+\s+.+""")) }
                ?.trim().orEmpty()
            return result(
                "未归类的运行时异常",
                "已保存异常链，但缺少对应专项规则",
                "已有异常根因链，当前诊断规则未匹配到更具体的已知类别；真实根因应以最深层异常及首个 App 自有栈帧为准。",
                listOf(causeLine.takeIf { it.isNotBlank() } ?: "异常根因链"),
                listOf(
                    "查看异常链最深层异常以及其上方第一个属于本应用的调用栈帧。",
                    "对照同一时间的最近操作轨迹和专项环境快照。",
                    "如果要进一步自动识别，请保留完整日志详情，而不是只复制列表中的一行摘要。",
                ),
            )
        }
        val hasStructuredException = record.exceptionSnapshot != null ||
            record.errorClass.isNotBlank() || hasExceptionTrace
        if (!hasStructuredException && record.httpStatusCode !in 400..599) {
            val callSite = record.diagnosticContext["日志调用位置"]
                ?.takeIf { it.isNotBlank() && it !in setOf("未采集", "不可用", "旧版日志未记录") }
                ?: "未记录日志调用位置"
            val captureStatus = record.diagnosticContext["异常对象采集状态"]
                ?.takeIf { it.isNotBlank() }
                ?: "旧版日志未记录异常对象采集状态"
            val businessOutcomeOnly = hasAny(
                "没有确认可直接播放",
                "未发现可播放",
                "候选源",
                "需要有效提取码",
                "不符合解析器支持条件",
                "没有返回可枚举条目",
                "请求完成，但没有",
                "未启动 yt-dlp",
                "原生解析失败，回退",
                "no playable source",
                "empty result",
            )
            if (businessOutcomeOnly) {
                return result(
                    "业务状态未达成（未抛出异常）",
                    if (callSite == "未记录日志调用位置") "业务结果已记录，判定位置缺失" else "业务结果与判定位置已记录",
                    "这条记录描述的是业务检查/解析/校验结果未达到目标；没有 Throwable 不等于程序抛出异常。当前证据应从调用位置对应的返回值、判定条件、候选数量和服务端状态判断是登录/权限限制、资源不存在、DRM/格式不支持，还是应用自身遗漏处理。",
                    listOf(
                        record.message.take(240),
                        callSite,
                        captureStatus,
                    ),
                    listOf(
                        "打开日志调用位置对应的方法，检查导致该业务状态的返回值和 if/else 判定条件，不要为了补堆栈而人为抛异常。",
                        "记录实际返回状态、候选/条目数量、失败原因枚举、HTTP 状态、请求阶段和耗时；保持 Cookie、令牌和完整签名 URL 脱敏。",
                        "区分用户需要补充信息、网站限制/DRM、远端资源不可用与应用解析缺陷，不能把所有“未成功”都归因于网络。",
                    ),
                )
            }
            return result(
                "失败事件缺少异常对象",
                if (callSite == "未记录日志调用位置") "调用位置和异常根因均缺失" else "日志调用位置已记录，根因待补充",
                "当前失败记录没有 Throwable、结构化异常树或可识别的 HTTP 错误状态。日志调用位置只能定位到记录失败状态的源码分支，不能代表异常真正抛出位置；必须结合该分支的返回值、错误码和控制流继续确认根因。",
                listOf(callSite, captureStatus),
                listOf(
                    "从日志调用位置打开对应文件和方法，找到生成这条失败日志的源码分支（if/else 或结果判定）。",
                    "检查该分支是否丢弃了原始异常；若存在 Throwable，应把原始对象传给 AppLogStore.error/warning。",
                    "若失败不是异常而是业务状态，记录明确失败枚举、返回码、请求阶段、耗时和输入/输出数量，不要只写“失败”。",
                    "补充失败前最后一个成功步骤，并为父任务和子请求传递稳定 operationId/requestId。",
                ),
            )
        }
        return result(
            "根因信息不足",
            "证据不足",
            "当前记录没有足够证据支持确定性结论；诊断不会把可能性当成事实。应补充失败调用的异常对象、操作阶段和关键返回状态。",
            listOf(record.message.takeIf { it.isNotBlank() } ?: record.tag),
            listOf(
                "先查看该记录前后的操作轨迹，确认失败发生在哪一个处理阶段。",
                "检查错误记录是否包含真实异常对象/根因链、HTTP 状态或返回值，而不只是文字提示。",
                "复现时保留日志详情；若该问题没有异常堆栈，建议在实际失败分支补记具体状态和输入规模。",
            ),
        )
    }

    /**
     * Remove prior-event breadcrumbs and generic runtime snapshots before classifying a single
     * failure. They remain in the exported log, but must not make unrelated errors look similar.
     */
    private fun focusedDetails(details: String): String {
        var focused = details
        focused = focused.replace(
            Regex("""(?s)出错前最近操作/异常轨迹（最多 \d+ 条，按先后顺序）：\n.*?(?=运行环境：\n|异常详情与调用堆栈：\n|$)"""),
            "",
        )
        focused = focused.replace(
            Regex("""(?s)运行环境：\n.*?(?=专项诊断快照：\n|异常详情与调用堆栈：\n|$)"""),
            "",
        )
        focused = focused.replace(
            Regex("""(?s)专项诊断快照：\n.*?(?=异常详情与调用堆栈：\n|$)"""),
            "",
        )
        return focused
    }

    private fun evidence(record: AppLogRecord, terms: List<String>): String {
        // Honor caller priority; a generic exception-capture status must not mask a call site.
        for (term in terms) {
            val entry = record.diagnosticContext.entries.sortedBy { it.key }.firstOrNull { (key, value) ->
                key.contains(term, ignoreCase = true) || value.contains(term, ignoreCase = true)
            }
            if (entry != null) return (entry.key + "：" + entry.value).trim().take(360)
        }
        val lines = focusedDetails(record.details).lineSequence().toList()
        for (term in terms) {
            val line = lines.firstOrNull { it.contains(term, ignoreCase = true) }
            if (line != null) return line.trim().take(360)
        }
        return record.message.ifBlank { record.tag }.take(360)
    }
}
