package com.warun.accounting.data.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.ExpenseCancellationRecord
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.WarunDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonthlyExportSourceSnapshotTest {
    private lateinit var database: WarunDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WarunDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun transactionSnapshotContainsOnlyRequestedMonthAndStoredEvidenceLinks() = runBlocking {
        val dao = database.warunDao()
        dao.insertDailyReport(report("june-report", "2026-06-01"))
        dao.insertDailyReport(report("july-report", "2026-07-01"))
        val juneActive = expense("june-active", "2026-06-02")
        val juneCancelled = expense("june-cancelled", "2026-06-03")
        val july = expense("july", "2026-07-02")
        dao.insertExpenseRecord(juneActive)
        dao.insertExpenseRecord(juneCancelled)
        dao.insertExpenseRecord(july)
        database.expenseCancellationDao().insert(
            ExpenseCancellationRecord(
                expenseId = juneCancelled.id,
                operationKey = "cancel-operation",
                requestFingerprint = "fingerprint",
                originalPurchaseTransactionId = null,
                reversalTransactionId = null,
                cancellationDate = "2026-06-04",
                cancelledAt = 20L,
                reason = null
            )
        )
        saveEvidence(juneActive, "june-stored", EvidenceRecordState.Stored, 30L)
        saveEvidence(juneCancelled, "cancelled-stored", EvidenceRecordState.Stored, 31L)
        saveEvidence(juneActive, "june-pending", EvidenceRecordState.Pending, null)
        saveEvidence(july, "july-stored", EvidenceRecordState.Stored, 32L)

        val snapshot = dao.getMonthlyExportSourceSnapshot(
            targetMonth = "2026-06",
            from = "2026-06-01",
            to = "2026-06-30"
        )

        assertEquals(listOf("june-report"), snapshot.dailyReports.map { it.id })
        assertEquals(
            listOf("june-active", "june-cancelled"),
            snapshot.expenseVisibility.map { it.expense.id }
        )
        assertEquals(
            mapOf("june-active" to false, "june-cancelled" to true),
            snapshot.expenseVisibility.associate { it.expense.id to it.isCancelled }
        )
        assertEquals(
            setOf("june-stored", "cancelled-stored"),
            snapshot.storedEvidence.map { it.evidenceId }.toSet()
        )
    }

    private suspend fun saveEvidence(
        expense: ExpenseRecord,
        id: String,
        state: String,
        storedAt: Long?
    ) {
        database.warunDao().saveExpenseWithEvidence(
            expense = expense,
            evidence = EvidenceRecord(
                id = id,
                captureId = "capture-$id",
                storedUri = "file:/stored/$id.jpg",
                byteSize = 100L,
                sha256 = "sha-$id",
                state = state,
                createdAt = 10L,
                storedAt = storedAt,
                updatedAt = storedAt ?: 10L
            ),
            link = ExpenseEvidenceLinkRecord(expense.id, id, 11L)
        )
    }

    private fun expense(id: String, date: String) = ExpenseRecord(
        id = id,
        expenseDate = date,
        category = ExpenseCategory.OtherExpense,
        supplierName = "支出先-$id",
        amount = 100L,
        paymentMethod = "現金",
        memo = "用途-$id",
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun report(id: String, date: String) = DailyReport(
        id = id,
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = 100L,
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
