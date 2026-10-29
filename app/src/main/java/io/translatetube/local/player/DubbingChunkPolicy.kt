package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * 配音分块策略（移植自 VideoLingo 的 analyze_subtitle_timing_and_speed / calc_if_too_fast /
 * gen_dub_chunks 思想，适配实时播放）：
 *
 * 1. 句级时序预算：每行可用时长 = 行时长 + 可借用间隙（上限 [MAX_BORROW_MS]）。
 * 2. 时长预判：按中文字数估算语音时长（合成前决策，避免合成了才塞不下）。
 * 3. 相邻句合并：
 *    a. 句子续接（[continuationRun]）：一句话被字幕拆成几截（原文这一截没有句末标点、
 *       停顿很短、同一个说话人）时，把这几截（最多 [MAX_CONTINUATION_LINES] 行）合成一块
 *       一口气念，避免一句话被念成几段、每段各自收尾、中间还有空档；
 *    b. 预算放不下的行与下一行合并成块一口气合成（上限 2 行/块），
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
    /** 相邻行可借用的最大间隙（VideoLingo TOLERANCE 同思想）。 */
    internal const val MAX_BORROW_MS = 2_000L
    /** 允许的语速上限：超过会明显机械；"配完"优先，取 1.5。 */
    internal const val MAX_SPEEDUP = 1.5f

    /** 一句话被拆开时，一块最多合并几行。 */
    internal const val MAX_CONTINUATION_LINES = 3
    /** 句内停顿的上限：超过它说明说话人真的停了，当作新的一句。 */
    internal const val MAX_CONTINUATION_PAUSE_MS = 600L
    /** 一块覆盖的原始时间跨度上限，限制起播等待和拖动进度后的错位。 */
    internal const val MAX_CHUNK_SPAN_MS = 10_000L
    /** 一块的中文字数上限，太长的块念起来也累、压缩比也难控制。 */
    internal const val MAX_CHUNK_CHARS = 70
    /** 原文里有多大比例的行以句末标点结尾，才认为标点可靠（自动字幕几乎没有标点）。 */
    private const val PUNCTUATED_RATIO = 0.25
    /** 前一截短于这个字数时，接下一截不加逗号（短语中间加停顿会很怪）。 */
    private const val SHORT_FRAGMENT_CHARS = 6

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
     * 估算一段中文文本的朗读时长（毫秒）。标点附加自然停顿。
     * 合成前预判用；宁可略偏短，剩余交给 WSOLA 对齐。
     */
    fun estimateSpeechMs(text: String): Long {
        val chars = text.count { !it.isWhitespace() }
        if (chars == 0) return 0L
        val pauses = text.count { it == '，' || it == '。' || it == '？' || it == '！' || it == '；' || it == '、' }
        val seconds = chars * SECONDS_PER_CHAR + pauses * 0.18f
        return (seconds * 1000f).toLong()
    }

    /**
     * 为整份字幕生成块表。行按顺序处理：
     * - 预判放得下 → 单行块（WSOLA 只做 ±10% 微调或不动）；
     * - 放不下且下一行间隙小 → 与下一行合并（合并窗口 = 跨两行时长+末行可借间隙），
     *   合并能放下（按 1/[MAX_SPEEDUP] 语速预算）→ 两行块；
     * - 合并也放不下 → 单行块，合成时压缩到上限，剩余超出由总线排队吸收。
     *
     * @param lines 已做过 extendCueWindows（借好间隙）的字幕行
     * @param pauseSource 与 [lines] 下标一一对应的**原始**时间（没借过间隙）：借完间隙后相邻两行
     *   几乎首尾相接，看不出说话人有没有真的停顿，判断"句内停顿"必须用原始时间。
     */
    fun buildChunks(lines: List<SubLine>, pauseSource: List<SubLine> = lines): List<Chunk> {
        val chunks = ArrayList<Chunk>(lines.size)
        val pauses = if (pauseSource.size == lines.size) pauseSource else lines
        val punctuated = sourceIsPunctuated(lines)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!DubbingTimeline.isPlayableCue(line)) {
                // 不可播行单独成块（合成层会写空标记，原声顶上），保持索引对齐
                chunks += Chunk(i, listOf(i), "", line.startMs, line.endMs)
                i++
                continue
            }
            val run = continuationRun(lines, pauses, i, punctuated)
            if (run.size > 1) {
                var mergedText = zhOf(lines[run.first()])
                for (k in 1 until run.size) {
                    // The sentence goes on in the next cue, so a full stop closing this piece would
                    // make the voice finish the sentence there and restart.
                    mergedText = joinContinuation(dropClosingFullStop(mergedText), zhOf(lines[run[k]]))
                }
                chunks += Chunk(i, run, mergedText, line.startMs, lines[run.last()].endMs)
                i += run.size
                continue
            }

            val text = line.zh?.trim().orEmpty().ifBlank { line.text.trim() }
            val windowEnd = line.endMs
            val est = estimateSpeechMs(text)
            val singleWindow = windowEnd - line.startMs

            val next = lines.getOrNull(i + 1)
            val mergeCandidate = next != null &&
                DubbingTimeline.isPlayableCue(next!!) &&
                // A merged PCM block is synthesized with the first cue's voice only. Never merge
                // across speaker assignments, otherwise the second cue silently loses its voice.
                next.speakerId == line.speakerId &&
                (next.startMs - line.endMs) < MAX_BORROW_MS

            if (est > singleWindow && mergeCandidate) {
                val nextText = next!!.zh?.trim().orEmpty().ifBlank { next.text.trim() }
                val mergedText = joinLines(text, nextText)
                val mergedWindowEnd = next.endMs
                val mergedEst = estimateSpeechMs(mergedText)
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

    private fun zhOf(line: SubLine): String = line.zh?.trim().orEmpty()

    private fun visibleChars(text: String): Int = text.count { !it.isWhitespace() }

    private val sentenceEnders = setOf('.', '!', '?', '…', '。', '！', '？', '；', ';')
    private val closers = setOf('"', '\'', ')', ']', '”', '’', '」', '』', '）', '】', '》')
    private val joinerPunctuation = setOf('。', '？', '！', '；', '，', '、', '：')

    /** 文本是否以句末标点结尾（忽略末尾的引号/括号）。 */
    internal fun endsSentence(text: String): Boolean {
        var end = text.length
        while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] in closers)) end--
        return end > 0 && text[end - 1] in sentenceEnders
    }

    /** 原文标点可靠吗：自动字幕几乎没有标点，不能靠"没写句号"判断句子没说完。 */
    internal fun sourceIsPunctuated(lines: List<SubLine>): Boolean {
        var playable = 0
        var withEnder = 0
        for (line in lines) {
            if (!DubbingTimeline.isPlayableCue(line)) continue
            playable++
            if (endsSentence(line.text)) withEnder++
        }
        return playable > 0 && withEnder.toDouble() / playable >= PUNCTUATED_RATIO
    }

    /** 这一截之后，句子是否还在继续。 */
    private fun continues(current: SubLine, next: SubLine, punctuated: Boolean): Boolean {
        if (punctuated) return !endsSentence(current.text)
        // 标点不可靠（自动字幕）：译文已经收尾就不并；英文等有大小写的文字，下一截以大写字母
        // 开头多半是新句子；没有大小写的文字（中日韩等）没有这个信号，只看译文是否收尾。
        if (endsSentence(zhOf(current))) return false
        val firstLetter = next.text.trimStart().firstOrNull { it.isLetter() } ?: return true
        return !firstLetter.isUpperCase()
    }

    /**
     * 从第 [start] 行开始，找出同一句话被拆开的连续几行（至少含自己）。
     * 条件：原文这一截没说完、同一说话人、两行都有译文、原始停顿很短，并且合成块不超过
     * 行数/时长/字数上限。
     */
    internal fun continuationRun(
        lines: List<SubLine>,
        pauses: List<SubLine>,
        start: Int,
        punctuated: Boolean,
    ): List<Int> {
        val run = arrayListOf(start)
        var chars = visibleChars(zhOf(lines[start]))
        var current = start
        while (run.size < MAX_CONTINUATION_LINES) {
            val next = current + 1
            val cur = lines[current]
            val nx = lines.getOrNull(next) ?: break
            if (!DubbingTimeline.isPlayableCue(nx) || nx.speakerId != cur.speakerId) break
            if (zhOf(cur).isEmpty() || zhOf(nx).isEmpty()) break
            if (!continues(cur, nx, punctuated)) break
            if (pauses[next].startMs - pauses[current].endMs > MAX_CONTINUATION_PAUSE_MS) break
            if (pauses[next].endMs - pauses[start].startMs > MAX_CHUNK_SPAN_MS) break
            val nextChars = visibleChars(zhOf(nx))
            if (chars + nextChars > MAX_CHUNK_CHARS) break
            run += next
            chars += nextChars
            current = next
        }
        return run
    }

    /** Remove a trailing 。 (or .) that closes a fragment whose sentence continues. */
    internal fun dropClosingFullStop(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith('。') || trimmed.endsWith('.')) trimmed.dropLast(1) else text
    }

    /** 接续同一句：前一截很短或已有标点就直接相连，否则补逗号给一个自然的换气点。 */
    internal fun joinContinuation(first: String, second: String): String {
        val tail = first.trimEnd().lastOrNull()
        return if (tail == null || tail in joinerPunctuation || visibleChars(first) < SHORT_FRAGMENT_CHARS) {
            "$first$second"
        } else {
            "$first，$second"
        }
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
