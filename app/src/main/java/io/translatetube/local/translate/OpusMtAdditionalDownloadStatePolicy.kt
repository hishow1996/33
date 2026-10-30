package io.translatetube.local.translate

/** Prevents complete model packages from being mislabeled as paused downloads. */
internal object OpusMtAdditionalDownloadStatePolicy {
    fun afterCancellation(filesComplete: Boolean, resumableData: Boolean): OpusMtStatus = when {
        filesComplete -> OpusMtStatus.FAILED
        resumableData -> OpusMtStatus.PAUSED
        else -> OpusMtStatus.NOT_DOWNLOADED
    }

    fun afterFailure(filesComplete: Boolean, resumableData: Boolean): OpusMtStatus = when {
        filesComplete -> OpusMtStatus.FAILED
        resumableData -> OpusMtStatus.PAUSED
        else -> OpusMtStatus.FAILED
    }
}