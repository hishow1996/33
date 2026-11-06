package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * 配音分块策略（移植自 VideoLingo 的 analyze_subtitle_timing_and_speed / calc_if_too_fast /
 * gen_dub_chunks 思想，适配实时播放）：
 *
 * 1. 句级时序预算：每行可用时长 = 行时长 + 可借用间隙（上限 [MAX_BORROW_MS]）。
 * 2. 时长预判：按中文字数估算语音时长（合成前决策，避免合成了才塞不下）。
 * 3. 相邻句合并：预算放不下的行与下一行合并成块一口气合成（上限 2 行/块），
 *    段内 TTS 自带韵律停顿——这是"上下文衔接自然"的关键。
 * 4. 超窗硬约束：合并后仍放不下的块交给 WSOLA 变速不变调压缩（上限见 [DubbingFitPolicy]），
 *    仍放不下的部分由总线排队吸收（不叠音、视频不暂停），"每一条都配完"。
 *    够放的块保持 TTS 自然语速，不再拉长去填满窗口。
 *
 * 纯逻辑、无 Android 依赖，JVM 可测。
 */
internal object DubbingChunkPolicy {

    /** 中文语速基准：普通朗读约 4.5 字/秒。预判宁可保守（估偏短），由 WSOLA 微调兜准。 */
    private const val SECONDS_PER_CHAR = 0.22f
    /** 两行之间的静音间隙小于它才允许合并成一块（VideoLingo TOLERANCE 同思想）。 */
    internal const val MAX_MERGE_GAP_MS = 2_000L

    /**
     * 合并预算使用的语速上限：必须与合成阶段 WSOLA 的压缩上限是同一个数，
     * 否则"预判能放下"和"合成时真能压下"会各说各话。只在 [DubbingFitPolicy] 里定义一次。
     */
    private const val MAX_SPEEDUP = DubbingFitPolicy.MAX_COMPRESS

    internal data class Chunk(
        /** 块首行索引（合成与播放的锚点）。 */
        val firstIndex: Int,
        /** 块内行索引列表（1 或 2 行）。 */
        val lineIndices: List<Int>,
        /** 合并后的合成文本（行间以句读相连，一口气念）。 */
        val text: String,
        /** 预算窗口 [startMs, endMs)：块首行 start 到末行 end+可借间隙。 */
        val windowStartMs: Long,
        val windowEndMs: Long,
    )

    /**
     * 估算一段中文文本在 1 倍语速下的朗读时长（毫秒），标点附加自然停顿。
     * 合成前预判用；宁可略偏短，剩余交给 WSOLA 对齐。
     *
     * @param paceScale 实测校准系数（见 [DubbingSpeechPaceCalibration]）：实际朗读时长 ÷ 本公式估算。
     *   默认 1.0 即保持原有估算；不同引擎/音色的真实语速差别很大，按它校准后合并判断才准。
     * @param speechRate 用户设置的配音语速（>1 更快）；语速越快，同一段文本读得越短。
     */
    fun estimateSpeechMs(text: String, paceScale: Double = 1.0, speechRate: Float = 1f): Long {
        val chars = text.count { !it.isWhitespace() }
        if (chars == 0) return 0L
        val pauses = text.count { it == '，' || it == '。' || it == '？' || it == '！' || it == '；' || it == '、' }
        val seconds = chars * SECONDS_PER_CHAR + pauses * 0.18f
        val safeScale = DubbingSpeechPaceCalibration.sanitize(paceScale)
        val safeRate = speechRate.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 3.0f) ?: 1f
        return (seconds * 1000.0 * safeScale / safeRate).toLong()
    }

    /**
     * 为整份字幕生成块表。行按顺序处理：
     * - 预判放得下 → 单行块（WSOLA 只做 ±10% 微调或不动）；
     * - 放不下且下一行间隙小 → 与下一行合并（合并窗口 = 跨两行时长+末行可借间隙），
     *   合并能放下（按 1/[MAX_SPEEDUP] 语速预算）→ 两行块；
     * - 合并也放不下 → 单行块，合成时压缩到上限，剩余超出由总线排队吸收。
     *
     * @param lines 已做过 extendCueWindows（借好间隙）的字幕行
     * @param paceScale 当前引擎/音色的实测语速校准系数，默认 1.0（与旧行为一致）
     * @param speechRate 用户设置的配音语速，默认 1.0
     */
    fun buildChunks(
        lines: List<SubLine>,
        paceScale: Double = 1.0,
        speechRate: Float = 1f,
        shouldSpeakCue: (index: Int, line: SubLine) -> Boolean = { _, _ -> true },
    ): List<Chunk> {
        val chunks = ArrayList<Chunk>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!DubbingTimeline.isPlayableCue(line) || !shouldSpeakCue(i, line)) {
                // 不可播行或非朗读声音说明只写入空时间轴标记，保持索引对齐，不生成音频
                chunks += Chunk(i, listOf(i), "", line.startMs, line.endMs)
                i++
                continue
            }
            val text = line.zh?.trim().orEmpty()
            require(text.isNotBlank()) {
                "第 ${i + 1} 行缺少有效中文译文，无法生成中文配音"
            }
            val windowEnd = line.endMs
            val est = estimateSpeechMs(text, paceScale, speechRate)
            val singleWindow = windowEnd - line.startMs

            val next = lines.getOrNull(i + 1)
            val mergeCandidate = next != null &&
                DubbingTimeline.isPlayableCue(next!!) &&
                shouldSpeakCue(i + 1, next!!) &&
                // A merged PCM block is synthesized with the first cue's voice only. Never merge
                // across speaker assignments, otherwise the second cue silently loses its voice.
                next.speakerId == line.speakerId &&
                (next.startMs - line.endMs) < MAX_MERGE_GAP_MS

            if (est > singleWindow && mergeCandidate) {
                val nextText = next!!.zh?.trim().orEmpty()
                require(nextText.isNotBlank()) {
                    "第 ${i + 2} 行缺少有效中文译文，无法合并生成中文配音"
                }
                val mergedText = joinLines(text, nextText)
                val mergedWindowEnd = next.endMs
                val mergedEst = estimateSpeechMs(mergedText, paceScale, speechRate)
                val mergedWindow = mergedWindowEnd - line.startMs
                // 引擎输入上限守卫：合并文本超限会导致整块合成失败（无声），
                // 宁可不合并让 WSOLA 单行硬对齐，也不能让两行都没声音。
                val withinInputLimit =
                    mergedText.codePointCount(0, mergedText.length) <=
                        io.translatetube.local.tts.TtsTextPolicy.MAX_CODE_POINTS
                if (withinInputLimit && mergedEst <= mergedWindow * MAX_SPEEDUP.toDouble()) {
                    // 合并后放得下（含语速预算）：一口气合成两行
                    chunks += Chunk(i, listOf(i, i + 1), mergedText, line.startMs, mergedWindowEnd)
                    i += 2
                    continue
                }
            }
            // 单行块：合成时按窗口压缩（只压不拉）
            chunks += Chunk(i, listOf(i), text, line.startMs, windowEnd)
            i++
        }
        return chunks
    }

    /**
     * 整份字幕的块表（不可变）：引擎持有它的引用，重算时整体替换，读取方永远看到一份自洽的表，
     * 不会读到改了一半的数据。同时预先建好"块首行 → 次行"的反向表，
     * 判断"这一行是不是合并块"和"块里有哪几行"都是一次查表，不用每次扫全表。
     */
    internal class ChunkTable(
        /** 块首行 → 合成文本（不可播/不朗读的行是空串）。 */
        val textByFirst: Map<Int, String>,
        /** 块首行 → 窗口截止毫秒。 */
        val windowEndByFirst: Map<Int, Long>,
        /** 块次行 → 块首行。 */
        val redirect: Map<Int, Int>,
        /** 块首行 → 它的次行们（只有合并块才有）。 */
        val secondariesByFirst: Map<Int, List<Int>>,
    ) {
        fun firstOf(index: Int): Int = redirect[index] ?: index

        fun isMergedFirst(firstIndex: Int): Boolean = secondariesByFirst.containsKey(firstIndex)

        fun secondariesOf(firstIndex: Int): List<Int> = secondariesByFirst[firstIndex].orEmpty()

        /** 这一行所在整块的全部行（块首行在前）；行号为负返回空。 */
        fun membersOf(index: Int): List<Int> {
            if (index < 0) return emptyList()
            val first = firstOf(index)
            return listOf(first) + secondariesOf(first)
        }

        companion object {
            val EMPTY = ChunkTable(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    /** 按 [buildChunks] 的结果生成块表；参数含义与 [buildChunks] 相同。 */
    fun buildTable(
        lines: List<SubLine>,
        paceScale: Double = 1.0,
        speechRate: Float = 1f,
        shouldSpeakCue: (index: Int, line: SubLine) -> Boolean = { _, _ -> true },
    ): ChunkTable {
        val textByFirst = HashMap<Int, String>()
        val windowEndByFirst = HashMap<Int, Long>()
        val redirect = HashMap<Int, Int>()
        val secondaries = HashMap<Int, List<Int>>()
        for (chunk in buildChunks(lines, paceScale, speechRate, shouldSpeakCue)) {
            textByFirst[chunk.firstIndex] = chunk.text
            windowEndByFirst[chunk.firstIndex] = chunk.windowEndMs
            val rest = chunk.lineIndices.drop(1)
            if (rest.isNotEmpty()) {
                secondaries[chunk.firstIndex] = rest
                rest.forEach { redirect[it] = chunk.firstIndex }
            }
        }
        return ChunkTable(textByFirst, windowEndByFirst, redirect, secondaries)
    }

    /** 行间连接：句读结尾直接相连，否则补一个逗号让 TTS 有自然停顿。 */
    private fun joinLines(first: String, second: String): String {
        val tail = first.lastOrNull()
        return if (tail == '。' || tail == '？' || tail == '！' || tail == '；' || tail == '，' || tail == '、') {
            "$first$second"
        } else {
            "$first，$second"
        }
    }
}
