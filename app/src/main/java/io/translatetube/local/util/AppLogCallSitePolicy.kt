package io.translatetube.local.util

/**
 * Identifies where a log call was emitted, independently from where a Throwable was created.
 *
 * A call site is useful for failure branches that log a status without passing a Throwable.
 * It must never be presented as the exception origin: when a Throwable exists, its own stack
 * remains the primary evidence for the failure location.
 */
internal data class AppLogCallSite(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int,
    val chain: List<String>,
) {
    fun diagnosticFields(): Map<String, String> = linkedMapOf(
        "日志调用位置采集状态" to "已定位到应用内调用帧",
        "日志调用位置" to formatFrame(className, methodName, fileName, lineNumber),
        "日志调用类" to className,
        "日志调用方法" to methodName,
        "日志调用文件" to fileName.orEmpty().ifBlank { "未提供" },
        "日志调用行号" to lineNumber.takeIf { it > 0 }?.toString().orEmpty().ifBlank { "未提供" },
        "日志调用链（最多8帧）" to chain.joinToString(" <- "),
    )

    companion object {
        private fun formatFrame(
            className: String,
            methodName: String,
            fileName: String?,
            lineNumber: Int,
        ): String {
            val file = fileName.orEmpty().ifBlank { "未知文件" }
            val line = lineNumber.takeIf { it > 0 }?.toString() ?: "行号未知"
            return "$className.$methodName($file:$line)"
        }
    }
}

internal object AppLogCallSitePolicy {
    private const val APP_PACKAGE_PREFIX = "io.translatetube.local."
    private const val MAX_CHAIN_FRAMES = 8

    /**
     * Select the first application-owned frame outside the logging infrastructure.
     * Input is injectable so the filtering rules can be unit-tested without Android.
     */
    fun from(stackTrace: Array<StackTraceElement>): AppLogCallSite? {
        val frames = stackTrace.asSequence()
            .filter { frame ->
                frame.className.startsWith(APP_PACKAGE_PREFIX) &&
                    !frame.className.startsWith("io.translatetube.local.util.AppLog")
            }
            .take(MAX_CHAIN_FRAMES)
            .toList()

        val primary = frames.firstOrNull() ?: return null
        return AppLogCallSite(
            className = primary.className,
            methodName = primary.methodName,
            fileName = primary.fileName,
            lineNumber = primary.lineNumber,
            chain = frames.map { frame ->
                val file = frame.fileName.orEmpty().ifBlank { "未知文件" }
                val line = frame.lineNumber.takeIf { it > 0 }?.toString() ?: "?"
                "${frame.className}.${frame.methodName}($file:$line)"
            },
        )
    }

    fun diagnosticFields(stackTrace: Array<StackTraceElement>): Map<String, String> =
        from(stackTrace)?.diagnosticFields() ?: mapOf(
            "日志调用位置采集状态" to "未找到应用内调用帧",
            "日志调用位置" to "未采集",
            "日志调用链（最多8帧）" to "未采集",
        )
}
