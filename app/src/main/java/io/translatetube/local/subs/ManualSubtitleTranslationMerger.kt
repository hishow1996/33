package io.translatetube.local.subs

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Matches an externally translated SRT/VTT track back to the original transcript timeline.
 * Cue order alone is not trusted: translation tools can merge, reorder, or drop entries.
 */
internal object ManualSubtitleTranslationMerger {
    private const val MAX_START_DRIFT_MS = 1_000L
    private const val MAX_END_DRIFT_MS = 2_500L
    private const val MINIMUM_MATCH_RATIO = 0.70

    fun merge(original: List<SubLine>, translated: List<SubLine>): List<SubLine> {
        require(original.isNotEmpty()) { "当前没有可匹配的原文字幕" }
        val candidates = translated
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<SubLine> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
        require(candidates.isNotEmpty()) { "导入文件中没有有效的带时间轴译文" }

        val used = BooleanArray(candidates.size)
        var matched = 0
        val merged = original.mapIndexed { index, source ->
            var bestIndex = -1
            var bestScore = Long.MAX_VALUE
            for (candidateIndex in candidates.indices) {
                if (used[candidateIndex]) continue
                val candidate = candidates[candidateIndex]
                val startDrift = abs(candidate.startMs - source.startMs)
                if (startDrift > MAX_START_DRIFT_MS) continue
                val endDrift = abs(candidate.endMs - source.endMs)
                if (endDrift > MAX_END_DRIFT_MS) continue
                val score = startDrift * 2L + endDrift
                if (score < bestScore) {
                    bestScore = score
                    bestIndex = candidateIndex
                }
            }

            if (bestIndex < 0) {
                source.copy(index = index, zh = null)
            } else {
                used[bestIndex] = true
                val text = ImportedSubtitlePolicy.splitBilingualCue(candidates[bestIndex].text)
                    .translationText
                    .trim()
                if (text.isNotEmpty()) {
                    matched++
                    source.copy(index = index, zh = text)
                } else {
                    source.copy(index = index, zh = null)
                }
            }
        }

        val minimumMatches = ceil(original.size * MINIMUM_MATCH_RATIO).toInt().coerceAtLeast(1)
        require(matched >= minimumMatches) {
            "译文与原文时间轴匹配不足（匹配 " + matched + "/" + original.size +
                " 条）。请使用原文字幕文件翻译，并尽量保留每条字幕的时间戳。"
        }
        return merged
    }
}
