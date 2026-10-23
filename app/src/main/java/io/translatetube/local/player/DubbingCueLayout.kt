package io.translatetube.local.player

/**
 * 配音句子在时间轴上的排版（纯逻辑，JVM 可测）。
 *
 * 只有一个旁白在说话：句子按字幕顺序依次开口，上一句没说完下一句就排在它后面，
 * 绝不与它叠在一起；视频不暂停，延迟由调用方通过追赶压缩逐步还掉。
 *
 * 规则：
 * - 已经开始播放的句子（frozen）位置固定，并作为障碍；
 * - 其余句子按期望开口时间排序，开口时间 = max(期望时间, 自身下限, 全局下限, 上一句结束 + 间隔)；
 * - 全局下限用来处理"准备晚了"的句子：从当前播放头之后整句播出，而不是从中间切入、丢掉开头。
 */
internal object DubbingCueLayout {

    internal class Item(
        /** 期望开口的帧位置（字幕时间 - 提前量）。 */
        val desiredFrame: Long,
        val lengthFrames: Long,
        /** 该句自己的开口下限（挂载时的播放头），没有则 [Long.MIN_VALUE]。 */
        val notBeforeFrame: Long = Long.MIN_VALUE,
        /** 非空表示已开始播放：位置固定。 */
        val frozenStartFrame: Long? = null,
    )

    /**
     * 返回与 [items] 顺序一致的实际开口帧位置。
     * @param gapFrames 相邻两句之间保留的最小静音（自然停顿）。
     * @param floorFrame 全局下限（通常是当前渲染头 + 安全余量），没有则 [Long.MIN_VALUE]。
     */
    fun layout(items: List<Item>, gapFrames: Long, floorFrame: Long): LongArray {
        val starts = LongArray(items.size)
        var cursor = Long.MIN_VALUE // 已排好的最后一句的结束位置
        for (i in items.indices) {
            val frozen = items[i].frozenStartFrame ?: continue
            starts[i] = frozen
            cursor = maxOf(cursor, frozen + items[i].lengthFrames)
        }
        val order = items.indices
            .filter { items[it].frozenStartFrame == null }
            .sortedWith(compareBy<Int>({ items[it].desiredFrame }, { it }))
        val safeGap = gapFrames.coerceAtLeast(0L)
        for (i in order) {
            val item = items[i]
            var start = maxOf(item.desiredFrame, item.notBeforeFrame, floorFrame)
            if (cursor != Long.MIN_VALUE) start = maxOf(start, cursor + safeGap)
            starts[i] = start
            cursor = start + item.lengthFrames
        }
        return starts
    }
}
