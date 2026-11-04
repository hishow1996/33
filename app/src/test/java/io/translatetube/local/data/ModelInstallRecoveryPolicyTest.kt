package io.translatetube.local.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInstallRecoveryPolicyTest {
    @Test
    fun preservesCompleteDestinationAndDeletesOnlyStaleBackup() {
        assertEquals(
            ModelInstallRecoveryPolicy.Action.KEEP_DESTINATION,
            ModelInstallRecoveryPolicy.decide(
                destinationExists = true,
                destinationReady = true,
                backupExists = true,
                backupReady = true,
            ),
        )
    }

    @Test
    fun restoresValidatedBackupWhenDestinationIsMissingOrIncomplete() {
        assertEquals(
            ModelInstallRecoveryPolicy.Action.RESTORE_BACKUP,
            ModelInstallRecoveryPolicy.decide(
                destinationExists = false,
                destinationReady = false,
                backupExists = true,
                backupReady = true,
            ),
        )
        assertEquals(
            ModelInstallRecoveryPolicy.Action.RESTORE_BACKUP,
            ModelInstallRecoveryPolicy.decide(
                destinationExists = true,
                destinationReady = false,
                backupExists = true,
                backupReady = true,
            ),
        )
    }

    @Test
    fun neverPrefersInvalidBackupOverValidDestination() {
        assertEquals(
            ModelInstallRecoveryPolicy.Action.KEEP_DESTINATION,
            ModelInstallRecoveryPolicy.decide(
                destinationExists = true,
                destinationReady = true,
                backupExists = true,
                backupReady = false,
            ),
        )
    }

    @Test
    fun allowsFreshInstallIfNeitherDestinationNorBackupIsUsable() {
        assertEquals(
            ModelInstallRecoveryPolicy.Action.INSTALL_FRESH,
            ModelInstallRecoveryPolicy.decide(
                destinationExists = false,
                destinationReady = false,
                backupExists = false,
                backupReady = false,
            ),
        )
        assertEquals(
            ModelInstallRecoveryPolicy.Action.REMOVE_INVALID_BACKUP,
            ModelInstallRecoveryPolicy.decide(
                destinationExists = true,
                destinationReady = false,
                backupExists = true,
                backupReady = false,
            ),
        )
    }
}
