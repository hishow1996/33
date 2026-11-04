package io.translatetube.local.subs

import kotlin.math.abs

/**
 * Matches an externally translated SRT/VTT track back to the original transcript timeline.
 * Cue order alone is not trusted: translation tools can merge, reorder, or drop entries.
 */
internal object ManualSubtitleTranslationMerger {
    private const val MAX_START_DRIFT_MS = 1_000L
    private const val MAX_END_DRIFT_MS = 2_500L

    fun merge(original: List<SubLine>, translated: List<SubLine>): List<SubLine> {
        require(original.isNotEmpty()) { "当前没有可匹配的原文字幕" }
        val candidates = translated
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<SubLine> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
        require(candidates.isNotEmpty()) { "导入文件中没有有效的带时间轴译文" }

        val used = BooleanArray(candidates.size)
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
                    source.copy(index = index, zh = text)
                } else {
                    source.copy(index = index, zh = null)
                }
            }
        }

        val missingIndices = merged.indices.filter { merged[it].zh.isNullOrBlank() }
        require(missingIndices.isEmpty()) {
            val shown = missingIndices.take(20).joinToString("、") { (it + 1).toString() }
            val remaining = missingIndices.size - minOf(missingIndices.size, 20)
            "外部译文不完整：缺少 " + missingIndices.size + "/" + original.size +
                " 条字幕（第 " + shown +
                (if (remaining > 0) " 行，另有 " + remaining + " 行未展开" else " 行") +
                "）。请补齐缺失译文并保留原字幕时间戳；未完成前不会进入配音。"
        }
        return merged
    }
}
