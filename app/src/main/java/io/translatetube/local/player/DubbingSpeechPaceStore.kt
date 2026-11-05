package io.translatetube.local.player

import android.content.Context

/**
 * 把各引擎/音色的配音语速校准系数（见 [DubbingSpeechPaceCalibration]）存在本机，
 * 这样换一个视频也能从上次学到的语速开始预判。读写失败一律按"没有数据"处理，绝不影响配音。
 */
internal object DubbingSpeechPaceStore {
    private const val PREFS_NAME = "dubbing_speech_pace"

    fun load(context: Context, engineKey: String): Double =
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(engineKey)) {
                DubbingSpeechPaceCalibration.DEFAULT_SCALE
            } else {
                DubbingSpeechPaceCalibration.sanitize(prefs.getFloat(engineKey, 1f).toDouble())
            }
        }.getOrDefault(DubbingSpeechPaceCalibration.DEFAULT_SCALE)

    fun save(context: Context, engineKey: String, scale: Double) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(engineKey, DubbingSpeechPaceCalibration.sanitize(scale).toFloat())
                .apply()
        }
    }
}
