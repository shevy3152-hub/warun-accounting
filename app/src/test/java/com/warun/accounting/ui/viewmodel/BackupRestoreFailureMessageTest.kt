package com.warun.accounting.ui.viewmodel

import com.warun.accounting.backup.BackupFailure
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupRestoreFailureMessageTest {
    @Test
    fun everyFailureHasUserVisibleMessageWithoutClaimingDataWasDeleted() {
        BackupFailure.entries.forEach { failure ->
            val message = backupFailureMessage(failure)
            assertFalse(message.isBlank())
            assertFalse(message.contains("削除しました"))
        }
    }
}
