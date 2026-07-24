package com.warun.accounting.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidencePersistenceTest {
    private lateinit var database: WarunDatabase
    private lateinit var dao: WarunDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WarunDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.warunDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun expenseAndEvidenceCanBePersistedAndReloaded() = runBlocking {
        val expense = expense("expense-linked")
        val pending = evidence("evidence-linked", EvidenceRecordState.Pending, storedAt = null)
        dao.saveExpenseWithEvidence(expense, pending, link(expense.id, pending.id))
        dao.finalizeExpenseEvidence(
            expense.id,
            pending.copy(state = EvidenceRecordState.Stored, storedAt = 30L, updatedAt = 30L),
            link(expense.id, pending.id)
        )

        val reloaded = dao.observeStoredExpenseEvidence().first()
        assertEquals(1, reloaded.size)
        assertEquals(expense.id, reloaded.single().expenseId)
        assertEquals("file:/stored/evidence-linked.jpg", reloaded.single().storedUri)
        assertEquals("sha-evidence-linked", reloaded.single().sha256)
        assertTrue(dao.hasExpenseEvidenceLink(expense.id, pending.id, pending.captureId))
        assertTrue(!dao.hasExpenseEvidenceLink("another-expense", pending.id, pending.captureId))
        assertTrue(!dao.hasExpenseEvidenceLink(expense.id, "another-evidence", pending.captureId))
        assertTrue(!dao.hasExpenseEvidenceLink(expense.id, pending.id, "another-capture"))
    }

    @Test
    fun expenseWithoutEvidenceRemainsReadable() = runBlocking {
        val expense = expense("expense-without-evidence")
        dao.insertExpenseRecord(expense)

        assertNotNull(dao.getExpenseRecord(expense.id))
        assertTrue(dao.observeStoredExpenseEvidence().first().isEmpty())
    }

    @Test
    fun sameEvidenceRetryIsIdempotentAndEditingExpenseKeepsLink() = runBlocking {
        val expense = expense("expense-idempotent")
        val evidence = evidence("evidence-idempotent", EvidenceRecordState.Pending, null)
        val link = link(expense.id, evidence.id)
        dao.saveExpenseWithEvidence(expense, evidence, link)
        dao.saveExpenseWithEvidence(expense.copy(amount = 1_600L), evidence, link)

        assertEquals(1L, count("evidence_records"))
        assertEquals(1L, count("expense_evidence_links"))
        assertEquals(1_600L, dao.getExpenseRecord(expense.id)?.amount)
    }

    @Test
    fun differentContentForSameEvidenceIsRejectedWithoutPartialExpense() = runBlocking {
        val firstExpense = expense("expense-first")
        val secondExpense = expense("expense-second")
        val original = evidence("shared-evidence", EvidenceRecordState.Pending, null)
        dao.saveExpenseWithEvidence(firstExpense, original, link(firstExpense.id, original.id))

        val result = runCatching {
            dao.saveExpenseWithEvidence(
                secondExpense,
                original.copy(sha256 = "different-sha"),
                link(secondExpense.id, original.id)
            )
        }

        assertTrue(result.isFailure)
        assertNull(dao.getExpenseRecord(secondExpense.id))
        assertEquals(firstExpense.id, dao.getExpenseIdForEvidence(original.id))
        assertEquals(1L, count("expense_evidence_links"))
    }

    @Test
    fun unlinkedStoredEvidenceIsNotExposedByExpenseQuery() = runBlocking {
        val evidence = evidence("unlinked-evidence", EvidenceRecordState.Stored, 30L)
        dao.insertEvidenceRecord(evidence)

        assertNotNull(dao.getEvidenceRecord(evidence.id))
        assertTrue(dao.observeStoredExpenseEvidence().first().isEmpty())
    }

    @Test
    fun dailyReportExpenseAndEvidenceRollBackTogetherOnLinkConflict() = runBlocking {
        val owner = expense("daily-owner")
        val shared = evidence("daily-shared", EvidenceRecordState.Pending, null)
        dao.saveExpenseWithEvidence(owner, shared, link(owner.id, shared.id))
        val conflictingExpense = expense("daily-conflict")
        val report = report("2026-07-23")

        val result = runCatching {
            dao.saveDailyReportWithExpenseAndEvidence(
                report,
                conflictingExpense,
                shared,
                link(conflictingExpense.id, shared.id)
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0L, count("daily_reports"))
        assertNull(dao.getExpenseRecord(conflictingExpense.id))
        assertEquals(owner.id, dao.getExpenseIdForEvidence(shared.id))
    }

    private fun count(table: String): Long =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun expense(id: String) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-22",
        category = ExpenseCategory.FoodPurchase,
        supplierName = "テスト商店",
        amount = 1_540L,
        paymentMethod = "現金",
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun evidence(id: String, state: String, storedAt: Long?) = EvidenceRecord(
        id = id,
        captureId = id,
        storedUri = "file:/stored/$id.jpg",
        byteSize = 100L,
        sha256 = "sha-$id",
        state = state,
        createdAt = 10L,
        storedAt = storedAt,
        updatedAt = storedAt ?: 10L
    )

    private fun link(expenseId: String, evidenceId: String) = ExpenseEvidenceLinkRecord(
        expenseId = expenseId,
        evidenceId = evidenceId,
        linkedAt = 20L
    )

    private fun report(date: String) = DailyReport(
        id = date,
        reportDate = date,
        status = DailyReportStatus.Draft,
        authorName = null,
        cashSales = 0L,
        cardSales = 0L,
        qrSales = 0L,
        accountsReceivableSales = 0L,
        otherSales = 0L,
        foodPurchases = 0L,
        alcoholPurchases = 0L,
        consumablesExpense = 0L,
        utilitiesExpense = 0L,
        electricityExpense = 0L,
        gasExpense = 0L,
        waterExpense = 0L,
        communicationExpense = 0L,
        rentExpense = 0L,
        accountantFeeExpense = 0L,
        miscellaneousExpense = 0L,
        otherExpense = 0L,
        openingCash = 0L,
        actualClosingCash = 0L,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1L,
        updatedAt = 1L,
        hasActualClosingCash = false
    )
}
