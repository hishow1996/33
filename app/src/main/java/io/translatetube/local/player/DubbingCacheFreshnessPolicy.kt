package io.translatetube.local.player

/**
 * A forced synthesis run cannot trust an existing cache just because its filename matches.
 * Cache reads become eligible again only after this run atomically publishes that exact file.
 */
internal object DubbingCacheFreshnessPolicy {
    fun canReadCache(
        forceFreshSynthesis: Boolean,
        wasWrittenByCurrentRun: Boolean,
    ): Boolean = !forceFreshSynthesis || wasWrittenByCurrentRun
}
