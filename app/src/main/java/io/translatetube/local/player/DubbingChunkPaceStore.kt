package io.translatetube.local.player

import android.content.Context

/**
 * 每个视频把"合并相邻句"用的语速系数定住。
 *
 * 配音缓存文件名里带着合并后的整句文本和时间窗，而哪两句并成一块取决于语速系数。
 * 这个系数是按引擎全局学习的，每合成一句、每看一个别的视频都会变；重进视频时如果拿最新的
 * 系数重新分块，边界附近的句子合并结果就可能和上次不同，已合成的缓存文件名对不上，
 * 开头几句只能重新合成。所以：同一个视频 + 同一个引擎 + 同一个语速，第一次用哪个系数
 * 分块，以后就一直用它，直到明确要求重新生成配音。
 *
 * 读写失败一律按"没有记录"处理，绝不影响配音。
 */
internal object DubbingChunkPaceStore {
    private const val PREFS_NAME = "dubbing_chunk_pace"

    fun key(videoKey: String, engineKey: String, speed: Float): String =
        "${DubbingCachePath.directoryName(videoKey)}|$engineKey|$speed"

    /** 纯决策：有记录且不要求重新生成就沿用记录，否则用当前学到的系数。 */
    fun choose(pinned: Double?, current: Double, forceFresh: Boolean): Double =
        DubbingSpeechPaceCalibration.sanitize(if (forceFresh || pinned == null) current else pinned)

    /** 返回本次分块要用的系数，并在它与记录不同（含第一次）时写回记录。 */
    fun resolve(
        context: Context,
        videoKey: String,
        engineKey: String,
        speed: Float,
        current: Double,
        forceFresh: Boolean,
    ): Double {
        val storeKey = key(videoKey, engineKey, speed)
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return DubbingSpeechPaceCalibration.sanitize(current)
        val pinned = runCatching {
            if (prefs.contains(storeKey)) prefs.getFloat(storeKey, 1f).toDouble() else null
        }.getOrNull()
        val chosen = choose(pinned, current, forceFresh)
        if (pinned == null || chosen != pinned) {
            runCatching { prefs.edit().putFloat(storeKey, chosen.toFloat()).apply() }
        }
        return chosen
    }
}
