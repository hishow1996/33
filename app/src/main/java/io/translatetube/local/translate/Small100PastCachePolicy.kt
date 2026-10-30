package io.translatetube.local.translate

/** Describes which previous SMaLL-100 KV caches can be released before copying new outputs. */
internal object Small100PastCachePolicy {
    fun keysToReleaseBeforeCopy(layerCount: Int, wasCache: Boolean): List<String> {
        require(layerCount >= 0) { "layerCount must not be negative" }
        return buildList {
            for (layer in 0 until layerCount) {
                for (kind in listOf("key", "value")) {
                    add("past_key_values.$layer.decoder.$kind")
                    if (!wasCache) add("past_key_values.$layer.encoder.$kind")
                }
            }
        }
    }
}
