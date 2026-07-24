package com.warun.accounting.camera

import com.warun.accounting.evidence.EvidenceFinalizationJournal
import com.warun.accounting.evidence.expenseFingerprint
import com.warun.accounting.data.local.ExpenseRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun captureIdReconstructsSameFileAndDeleteRemovesIt() {
        val directory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(directory)
        val file = store.prepareFile("capture-1")
        file.writeText("image")

        assertEquals(file.canonicalFile, store.fileFor("capture-1").canonicalFile)
        assertTrue(store.delete("capture-1"))
        assertFalse(file.exists())
    }

    @Test
    fun cleanupDeletesOnlyExpiredPendingReceiptImages() {
        val now = 10_000_000L
        val directory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(directory, now = { now })
        val expired = store.prepareFile("expired").apply {
            writeText("old")
            setLastModified(now - 1_001L)
        }
        val recent = store.prepareFile("recent").apply {
            writeText("new")
            setLastModified(now - 999L)
        }
        val unrelated = File(directory, "keep.txt").apply {
            writeText("keep")
            setLastModified(0L)
        }

        assertEquals(1, store.cleanupExpired(retentionMillis = 1_000L))
        assertFalse(expired.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun journalOwnedExpiredPendingImageIsProtectedUntilJournalCompletes() {
        val now = 10_000_000L
        val directory = temporaryFolder.newFolder("pending-protected")
        val journal = EvidenceFinalizationJournal(temporaryFolder.newFolder("journal-protected"))
        val store = ReceiptImageStore(
            pendingDirectory = directory,
            now = { now },
            deletionPolicyProvider = PendingImageDeletionPolicyProvider {
                journal.pendingImageDeletionPolicy()
            }
        )
        val expense = expense("expense-protected")
        val image = store.prepareFile("capture-protected").apply {
            writeText("old")
            setLastModified(now - 2_000L)
        }
        journal.prepare(
            captureId = "capture-protected",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )

        assertEquals(0, store.cleanupExpired(retentionMillis = 1_000L))
        assertTrue(image.exists())
        assertFalse(store.delete("capture-protected"))

        journal.markAccountingSaved(
            captureId = "capture-protected",
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        assertEquals(0, store.cleanupExpired(retentionMillis = 1_000L))
        assertTrue(image.exists())

        journal.complete("capture-protected", expense.id)
        assertEquals(1, store.cleanupExpired(retentionMillis = 1_000L))
        assertFalse(image.exists())
    }

    @Test
    fun corruptUnknownJournalBlocksCleanupOnTheSafeSide() {
        val now = 10_000_000L
        val directory = temporaryFolder.newFolder("pending-corrupt")
        val journalDirectory = temporaryFolder.newFolder("journal-corrupt")
        File(journalDirectory, "finalization_unknown!.journal").writeText("corrupt")
        val journal = EvidenceFinalizationJournal(journalDirectory)
        journal.loadAllWithDiagnostics()
        val store = ReceiptImageStore(
            pendingDirectory = directory,
            now = { now },
            deletionPolicyProvider = PendingImageDeletionPolicyProvider {
                journal.pendingImageDeletionPolicy()
            }
        )
        val image = store.prepareFile("unrelated-capture").apply {
            writeText("old")
            setLastModified(now - 2_000L)
        }

        assertEquals(0, store.cleanupExpired(retentionMillis = 1_000L))
        assertTrue(image.exists())
        assertEquals(1, journal.quarantinedFiles().size)
    }

    @Test
    fun unreadableJournalLocationBlocksCleanupOnTheSafeSide() {
        val now = 10_000_000L
        val directory = temporaryFolder.newFolder("pending-unreadable-journal")
        val journalPath = temporaryFolder.newFile("journal-is-a-file").apply {
            writeText("not a directory")
        }
        val journal = EvidenceFinalizationJournal(journalPath)
        val store = ReceiptImageStore(
            pendingDirectory = directory,
            now = { now },
            deletionPolicyProvider = PendingImageDeletionPolicyProvider {
                journal.pendingImageDeletionPolicy()
            }
        )
        val image = store.prepareFile("capture-unknown-owner").apply {
            writeText("old")
            setLastModified(now - 2_000L)
        }

        assertTrue(runCatching { journal.loadAllWithDiagnostics() }.isFailure)
        assertEquals(0, store.cleanupExpired(retentionMillis = 1_000L))
        assertTrue(image.exists())
    }

    private fun expense(id: String) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-23",
        category = "food_purchase",
        supplierName = null,
        amount = 1L,
        paymentMethod = "現金",
        memo = null,
        receiptId = null,
        sourceType = "manual",
        createdAt = 1L,
        updatedAt = 1L
    )
}
