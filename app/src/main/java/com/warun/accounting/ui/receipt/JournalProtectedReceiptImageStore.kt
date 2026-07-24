package com.warun.accounting.ui.receipt

import android.content.Context
import com.warun.accounting.camera.PendingImageDeletionPolicyProvider
import com.warun.accounting.camera.ReceiptImageStore
import com.warun.accounting.evidence.EvidenceFinalizationJournal
import java.io.File

internal fun journalProtectedReceiptImageStore(context: Context): ReceiptImageStore {
    val journal = EvidenceFinalizationJournal(
        File(context.filesDir, "accounting-evidence/finalization-journal")
    )
    return ReceiptImageStore(
        pendingDirectory = File(context.filesDir, "receipt-images/pending"),
        deletionPolicyProvider = PendingImageDeletionPolicyProvider {
            journal.pendingImageDeletionPolicy()
        }
    )
}
