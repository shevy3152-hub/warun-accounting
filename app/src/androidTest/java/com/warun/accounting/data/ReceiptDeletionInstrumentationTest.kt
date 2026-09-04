package com.warun.accounting.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.FixedCostEvidenceLinkRecord
import com.warun.accounting.data.local.FixedCostReceiptApplicationRecord
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptDeletionInstrumentationTest {
    private lateinit var database: WarunDatabase
    private lateinit var repository: OfflineAccountingRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WarunDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = OfflineAccountingRepository(database.warunDao(), database.expensePrepaidLinkDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun unconfirmedReceiptIsDeletedAndMissingFieldsAreAllowed() = runBlocking {
        val receipt = receipt("eligible", confirmed = false, amount = 0L)
        database.warunDao().insertReceipt(receipt)

        assertEquals(ReceiptDeletionResult.Deleted, repository.deleteUnconfirmedReceipt(receipt.id))
        assertEquals(null, database.warunDao().getReceipt(receipt.id))
    }

    @Test
    fun confirmedOrMissingReceiptCannotBeDeleted() = runBlocking {
        val confirmed = receipt("confirmed", confirmed = true, amount = 1_000L)
        database.warunDao().insertReceipt(confirmed)

        assertEquals(ReceiptDeletionResult.AlreadyConfirmed, repository.deleteUnconfirmedReceipt(confirmed.id))
        assertEquals(ReceiptDeletionResult.NotFound, repository.deleteUnconfirmedReceipt("missing"))
        assertNotNull(database.warunDao().getReceipt(confirmed.id))
    }

    @Test
    fun unrelatedConfirmedReceiptCanBeDeletedWithoutTouchingOtherRecords() = runBlocking {
        val confirmed = receipt("confirmed-unrelated", confirmed = true, amount = 0L)
        database.warunDao().insertReceipt(confirmed)
        val beforeExpenses = count("expense_records")
        val beforeEvidence = count("evidence_records")

        assertEquals(ReceiptDeletionResult.Deleted, repository.deleteConfirmedReceipt(confirmed.id))
        assertEquals(null, database.warunDao().getReceipt(confirmed.id))
        assertEquals(beforeExpenses, count("expense_records"))
        assertEquals(beforeEvidence, count("evidence_records"))
    }

    @Test
    fun relatedConfirmedReceiptRemainsProtected() = runBlocking {
        val report = report("confirmed-related-report")
        val confirmed = receipt("confirmed-related", confirmed = true, amount = 1_000L)
        val dao = database.warunDao()
        dao.insertDailyReport(report)
        dao.insertReceipt(confirmed)
        dao.insertFixedCostReceiptApplication(
            FixedCostReceiptApplicationRecord("confirmed-related-app", confirmed.id, report.id, "electricity", "現金", 1L, 1L)
        )

        assertEquals(ReceiptDeletionResult.Protected, repository.deleteConfirmedReceipt(confirmed.id))
        assertNotNull(dao.getReceipt(confirmed.id))
    }

    @Test
    fun fixedCostApplicationAndExpenseReferenceProtectReceipt() = runBlocking {
        val report = report("report")
        val applied = receipt("applied", confirmed = false, amount = 2_000L)
        val referenced = receipt("referenced", confirmed = false, amount = 3_000L)
        val dao = database.warunDao()
        dao.insertDailyReport(report)
        dao.insertReceipt(applied)
        dao.insertReceipt(referenced)
        dao.insertFixedCostReceiptApplication(
            FixedCostReceiptApplicationRecord("app", applied.id, report.id, "electricity", "現金", 1L, 1L)
        )
        dao.insertExpenseRecord(com.warun.accounting.data.local.ExpenseRecord(
            "expense", "2026-09-01", "other_expense", "store", 3_000L, "現金", null,
            referenced.id, "receipt", 1L, 1L
        ))

        assertEquals(ReceiptDeletionResult.Protected, repository.deleteUnconfirmedReceipt(applied.id))
        assertEquals(ReceiptDeletionResult.Protected, repository.deleteUnconfirmedReceipt(referenced.id))
        assertNotNull(dao.getReceipt(applied.id))
        assertNotNull(dao.getReceipt(referenced.id))
    }

    @Test
    fun fixedCostEvidenceLinkProtectsReceiptAndEvidenceFileIsUntouched() = runBlocking {
        val report = report("report-evidence")
        val receipt = receipt("evidence-receipt", confirmed = false, amount = 4_000L)
        val dao = database.warunDao()
        dao.insertDailyReport(report)
        dao.insertReceipt(receipt)
        dao.insertFixedCostReceiptApplication(
            FixedCostReceiptApplicationRecord("app-evidence", receipt.id, report.id, "gas", "銀行振込", 1L, 1L)
        )
        val bytes = byteArrayOf(1, 2, 3, 4)
        dao.insertEvidenceRecord(EvidenceRecord("evidence", "capture", "file:///test.jpg", bytes.size.toLong(), "sha", EvidenceRecordState.Stored, 1L, 1L, 1L))
        dao.insertFixedCostEvidenceLink(FixedCostEvidenceLinkRecord("app-evidence", "evidence", 0, 1L))

        assertEquals(ReceiptDeletionResult.Protected, repository.deleteUnconfirmedReceipt(receipt.id))
        assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(1L, count("evidence_records"))
    }

    @Test
    fun repeatedDeleteDoesNotCreateOrphanOrDeleteOtherRecords() = runBlocking {
        val receipt = receipt("once", confirmed = false, amount = 100L)
        database.warunDao().insertReceipt(receipt)
        assertEquals(ReceiptDeletionResult.Deleted, repository.deleteUnconfirmedReceipt(receipt.id))
        assertEquals(ReceiptDeletionResult.NotFound, repository.deleteUnconfirmedReceipt(receipt.id))
        assertEquals(0L, count("expense_records"))
        assertEquals(0L, count("evidence_records"))
        assertFalse(database.warunDao().hasExpenseReference(receipt.id))
    }

    private fun count(table: String): Long = database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getLong(0)
    }

    private fun receipt(id: String, confirmed: Boolean, amount: Long) = ReceiptRecord(
        id, null, null, 1L, null, amount, 0L, null, null, confirmed, null, 1L
    )

    private fun report(id: String) = DailyReport(
        id = id,
        reportDate = "2026-09-01",
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
