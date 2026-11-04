package io.translatetube.local.player

/**
 * A progress callback may update only the task that currently owns the session journal.
 * A new task is registered explicitly by begin(); a cleared or replaced task cannot reclaim it.
 */
internal object SpeakerRecognitionJournalTaskPolicy {
    fun acceptsUpdate(activeToken: Long?, incomingToken: Long): Boolean =
        incomingToken > 0L && activeToken != null && activeToken == incomingToken
}
