package io.translatetube.local.data

/** Safe recovery decisions for a previously interrupted directory-level model installation. */
internal object ModelInstallRecoveryPolicy {
    enum class Action {
        KEEP_DESTINATION,
        RESTORE_BACKUP,
        REMOVE_INVALID_BACKUP,
        INSTALL_FRESH,
    }

    fun decide(
        destinationExists: Boolean,
        destinationReady: Boolean,
        backupExists: Boolean,
        backupReady: Boolean,
    ): Action = when {
        !backupExists && destinationReady -> Action.KEEP_DESTINATION
        !backupExists && !destinationExists -> Action.INSTALL_FRESH
        !backupExists -> Action.INSTALL_FRESH
        destinationReady -> Action.KEEP_DESTINATION
        backupReady -> Action.RESTORE_BACKUP
        destinationExists -> Action.REMOVE_INVALID_BACKUP
        else -> Action.INSTALL_FRESH
    }
}
