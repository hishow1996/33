package io.translatetube.local.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Safe, non-identifying snapshot of Android's media-output state.
 *
 * Connected output-device types are candidates reported by AudioManager; they do not prove
 * which route the system selected for a particular TextToSpeech or Media3 AudioTrack.
 */
object AudioOutputDiagnostics {
    fun snapshot(context: Context): Map<String, String> {
        val audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return mapOf("系统音频状态" to "AudioManager 不可用")

        val details = linkedMapOf<String, String>()
        runCatching {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            details["STREAM_MUSIC 音量"] = "$current/$maximum"
            details["STREAM_MUSIC 是否静音"] =
                runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC).toString() }
                    .getOrDefault("读取失败")
            details["系统是否检测到音乐活动"] = audioManager.isMusicActive.toString()
            details["AudioManager 模式"] = audioManager.mode.toString()
        }.onFailure {
            details["系统媒体音量读取异常"] = it.javaClass.name + ": " + (it.message ?: "无异常消息")
        }

        runCatching {
            val outputTypes = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_UNKNOWN }
                .map { deviceTypeName(it.type) }
                .distinct()
                .sorted()
            details["已连接输出设备类型（候选，不代表实际选中路由）"] =
                outputTypes.joinToString(", ").ifBlank { "未检测到或系统未提供" }
        }.onFailure {
            details["输出设备枚举异常"] = it.javaClass.name + ": " + (it.message ?: "无异常消息")
        }
        return details
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话设备"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙媒体设备"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
        AudioDeviceInfo.TYPE_DOCK -> "底座音频设备"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路输出"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路输出"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
        AudioDeviceInfo.TYPE_IP -> "IP 音频设备"
        AudioDeviceInfo.TYPE_BUS -> "内部音频总线"
        else -> "设备类型 $type"
    }
}
