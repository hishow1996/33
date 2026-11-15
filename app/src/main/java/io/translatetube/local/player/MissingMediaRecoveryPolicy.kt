package io.translatetube.local.player

/**
 * 播放页里"播放器没有挂任何视频"时该怎么办。
 *
 * 恢复里的整条重开（open）会取消正在跑的处理任务、关掉配音引擎，合成到一半会被打断重来。
 * 所以先看处理管线在不在跑、视频是不是本来就还没到挂载那一步：
 * - 管线在跑、流装配还没做完：视频晚点会被正常挂上，不是死角，继续等；
 * - 管线在跑、流装配已做完却没挂上（油管）：只重新解析直链并挂上视频，不碰字幕、配音和进度；
 * - 其他（管线已结束，或非油管源）：沿用原来的整条重开。
 */
internal object MissingMediaRecoveryPolicy {
    enum class Action { WAIT, REMOUNT_ONLY, REOPEN }

    fun decide(
        pipelineRunning: Boolean,
        streamAssemblyPending: Boolean,
        isYouTube: Boolean,
    ): Action = when {
        pipelineRunning && streamAssemblyPending -> Action.WAIT
        pipelineRunning && isYouTube -> Action.REMOUNT_ONLY
        else -> Action.REOPEN
    }
}
