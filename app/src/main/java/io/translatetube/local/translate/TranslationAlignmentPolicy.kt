package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale

/** 一行译文被判定为"可能不对应本行原文"的证据；不含字幕内容。 */
internal data class TranslationAlignmentSuspect(
    val sourceIndex: Int,
    val reasonCode: String,
    val reason: String,
)

/**
 * 内容无关的"译文是否对应本行原文"检查。
 *
 * 模型把相邻的碎句合并翻译时，内容会向前挤、批次尾部的行留空；前面的行仍然是非空中文，
 * 逐行校验（SubtitleTranslationQualityPolicy）发现不了，所以这里只看同一次响应里行与行之间的关系：
 * 尾部连续缺失、长度比例异常、不同原文得到相同译文、整行括号音效却对应普通原文。
 * 所有阈值集中在这里，便于按日志调整。
 */
internal object TranslationAlignmentPolicy {
    const val REASON_LENGTH_RATIO = "ALIGNMENT_LENGTH_RATIO"
    const val REASON_DUPLICATE = "ALIGNMENT_DUPLICATE"
    const val REASON_SOUND_CAPTION = "ALIGNMENT_SOUND_CAPTION"
    const val REASON_FRONT_LOADED_MERGE = "ALIGNMENT_FRONT_LOADED_MERGE"

    /** 批次尾部连续缺失至少这么多行，才视为"前移合并"特征。 */
    const val MIN_TAIL_MISSING_ROWS = 3

    /** 且尾部缺失行数至少占本次请求行数的这个百分比。 */
    const val MIN_TAIL_MISSING_PERCENT = 20

    /** 长度比规则至少需要这么多有效行，样本太少时中位数不可靠。 */
    const val MIN_ROWS_FOR_LENGTH_RATIO = 5

    /** 原文字母数字数低于该值的行不参与长度比（比值不稳定）。 */
    const val MIN_SOURCE_CHARS_FOR_LENGTH_RATIO = 3

    /** 某行"译文/原文"比值超过本批中位数的这个倍数，才可能是合并。 */
    const val LENGTH_RATIO_FACTOR = 2.0

    /** 且译文比"按中位数推算的长度"至少多出这么多字符。 */
    const val LENGTH_EXCESS_MIN_CHARS = 8

    /** 重复规则只看规范化后至少这么长的译文，避免"嗯。""谢谢。"这类短语被误伤。 */
    const val MIN_DUPLICATE_CHARS = 6

    /** 小批重译每块最多这么多行。 */
    const val CHUNK_RECOVERY_ROWS = 10

    private val wrappedCaptionRegex = Regex("""^\s*[(（\[【][^)）\]】]*[)）\]】]\s*[。.!！]?\s*$""")

    private fun contentLength(text: String): Int = text.count { it.isLetterOrDigit() }

    private fun normalizeForCompare(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    /**
     * 批次尾部是否出现连续缺失，而前面至少有一行已被接受。
     * 这是模型合并相邻碎句并把内容前移的典型特征：前面的行里已经装着后面句子的译文。
     */
    fun hasFrontLoadedMergeSignature(
        requestedIndices: List<Int>,
        missingIndices: Collection<Int>,
    ): Boolean {
        val size = requestedIndices.size
        if (size <= MIN_TAIL_MISSING_ROWS) return false
        val missing = missingIndices.toHashSet()
        var tail = 0
        for (position in size - 1 downTo 0) {
            if (requestedIndices[position] in missing) tail++ else break
        }
        if (tail < MIN_TAIL_MISSING_ROWS) return false
        if (tail * 100 < size * MIN_TAIL_MISSING_PERCENT) return false
        return tail < size
    }

    /** 返回本次响应里"译文可能不对应本行原文"的行，按请求顺序排列。 */
    fun suspectRows(
        requestedIndices: List<Int>,
        sourceTexts: Map<Int, String>,
        translations: Map<Int, String>,
    ): List<TranslationAlignmentSuspect> {
        val rows = requestedIndices.distinct().mapNotNull { index ->
            val source = sourceTexts[index]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val translation = translations[index]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(index, source, translation)
        }
        if (rows.isEmpty()) return emptyList()
        val suspects = LinkedHashMap<Int, TranslationAlignmentSuspect>()

        // 规则 1：长度比。某行译文远长于同批其他行按原文长度推算的结果，说明它很可能吞了相邻行。
        if (rows.size >= MIN_ROWS_FOR_LENGTH_RATIO) {
            val measured = rows.mapNotNull { (index, source, translation) ->
                val sourceChars = contentLength(source)
                if (sourceChars < MIN_SOURCE_CHARS_FOR_LENGTH_RATIO) return@mapNotNull null
                Triple(index, sourceChars, contentLength(translation))
            }
            if (measured.size >= MIN_ROWS_FOR_LENGTH_RATIO) {
                val ratios = measured.map { (_, sourceChars, translationChars) ->
                    translationChars.toDouble() / sourceChars
                }.sorted()
                val middle = ratios.size / 2
                val median = if (ratios.size % 2 == 1) {
                    ratios[middle]
                } else {
                    (ratios[middle - 1] + ratios[middle]) / 2.0
                }
                if (median > 0.0) {
                    measured.forEach { (index, sourceChars, translationChars) ->
                        val ratio = translationChars.toDouble() / sourceChars
                        val expectedChars = sourceChars * median
                        if (ratio > median * LENGTH_RATIO_FACTOR &&
                            translationChars - expectedChars >= LENGTH_EXCESS_MIN_CHARS
                        ) {
                            suspects.putIfAbsent(
                                index,
                                TranslationAlignmentSuspect(
                                    index,
                                    REASON_LENGTH_RATIO,
                                    "译文长度明显超过同批其他行按原文长度推算的结果，疑似合并了相邻字幕行" +
                                        "（译文字符=$translationChars，原文字符=$sourceChars）",
                                ),
                            )
                        }
                    }
                }
            }
        }

        // 规则 2：同一次响应里，不同原文得到了相同的较长译文，说明内容被挪到了别的行。
        rows.groupBy { (_, _, translation) -> normalizeForCompare(translation) }
            .forEach { (normalizedTranslation, group) ->
                if (normalizedTranslation.length < MIN_DUPLICATE_CHARS || group.size < 2) return@forEach
                val distinctSources = group.map { (_, source, _) -> normalizeForCompare(source) }.distinct()
                if (distinctSources.size < 2) return@forEach
                group.forEach { (index, _, _) ->
                    suspects.putIfAbsent(
                        index,
                        TranslationAlignmentSuspect(
                            index,
                            REASON_DUPLICATE,
                            "同一次响应里有 ${group.size} 行原文不同的字幕得到了相同译文，疑似译文错位",
                        ),
                    )
                }
            }

        // 规则 3：译文整行是括号音效，而原文既不是音效也没有被括号包裹。
        rows.forEach { (index, source, translation) ->
            if (!wrappedCaptionRegex.matches(translation)) return@forEach
            if (wrappedCaptionRegex.matches(source)) return@forEach
            if (SubtitleSoundDescriptionPolicy.isSoundDescription(source)) return@forEach
            suspects.putIfAbsent(
                index,
                TranslationAlignmentSuspect(
                    index,
                    REASON_SOUND_CAPTION,
                    "译文整行是括号音效说明，但原文是普通语句，疑似译文错位",
                ),
            )
        }

        return requestedIndices.distinct().mapNotNull { suspects[it] }
    }

    /** 把可疑行从已接受译文里移除并标记为缺失，让调用方按原有路径重试。 */
    fun applyAlignmentChecks(
        result: TranslationBatchResult,
        requestedIndices: List<Int>,
        sourceTexts: Map<Int, String>,
    ): TranslationBatchResult {
        val suspects = suspectRows(requestedIndices, sourceTexts, result.translations)
        if (suspects.isEmpty()) return result
        val suspectIndices = suspects.map { it.sourceIndex }.toHashSet()
        val missing = result.missingIndices.toHashSet().also { it.addAll(suspectIndices) }
        val rejections = suspects.map { suspect ->
            val output = result.translations[suspect.sourceIndex]
            TranslationRowRejection(
                sourceIndex = suspect.sourceIndex,
                reasonCode = suspect.reasonCode,
                sourceCharCount = sourceTexts[suspect.sourceIndex]?.length ?: 0,
                rawOutputCharCount = output?.length ?: 0,
                diagnosticReason = suspect.reason,
                rawOutputPreview = TranslationFailureDiagnosticPolicy.safeSnippet(output, 120),
            )
        }
        return result.copy(
            translations = result.translations - suspectIndices,
            missingIndices = requestedIndices.distinct().filter { it in missing },
            rejectedRows = result.rejectedRows.filterNot { it.sourceIndex in suspectIndices } + rejections,
        )
    }

    /** 检测到前移合并特征后，整体丢弃本次响应里已接受的行；它们很可能装着别的行的译文。 */
    fun discardAcceptedRows(
        result: TranslationBatchResult,
        requestedIndices: List<Int>,
        sourceTexts: Map<Int, String>,
    ): TranslationBatchResult {
        val discarded = result.translations.keys.toHashSet()
        val rejections = result.translations.map { (index, output) ->
            TranslationRowRejection(
                sourceIndex = index,
                reasonCode = REASON_FRONT_LOADED_MERGE,
                sourceCharCount = sourceTexts[index]?.length ?: 0,
                rawOutputCharCount = output.length,
                diagnosticReason = "批次尾部连续缺失，疑似模型把相邻碎句合并并前移；本次响应里已接受的译文被整体丢弃",
                rawOutputPreview = TranslationFailureDiagnosticPolicy.safeSnippet(output, 120),
            )
        }
        return result.copy(
            translations = emptyMap(),
            missingIndices = requestedIndices.filter { it >= 0 }.distinct(),
            rejectedRows = result.rejectedRows.filterNot { it.sourceIndex in discarded } + rejections,
        )
    }
}
