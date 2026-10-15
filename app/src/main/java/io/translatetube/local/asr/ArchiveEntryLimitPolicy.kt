package io.translatetube.local.asr

/** Bounds work spent walking archive metadata, including archives full of empty entries. */
internal object ArchiveEntryLimitPolicy {
    const val MAX_ENTRIES = 10_000

    fun isWithinLimit(entryCount: Int): Boolean = entryCount in 0..MAX_ENTRIES
}
