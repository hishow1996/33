package io.translatetube.local.data

/** Converts persisted filesystem readiness into the UI/notification status after process restart. */
internal object ModelAvailabilityPolicy {
    fun initialStatus(isReady: Boolean): ModelStatus =
        if (isReady) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
}
