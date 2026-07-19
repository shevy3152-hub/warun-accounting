package com.warun.accounting.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionSaveTest {
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
    fun tearDown() {
        database.close()
    }

    @Test
    fun receiptAndExpenseRollBackWhenExpenseInsertFails() = runBlocking {
        val receipt = receipt("receipt-failure")
        val invalidExpense = expense("expense-failure", receiptId = "missing-receipt")

        assertTransactionFails {
            dao.saveReceiptWithExpense(receipt, invalidExpense)
        }

        assertEquals(0L, count("receipts"))
        assertEquals(0L, count("expense_records"))
    }

    @Test
    fun dailyReportAndExpenseRollBackWhenExpenseInsertFails() = runBlocking {
        val report = report("2026-07-20")
        val invalidExpense = expense("daily-expense-failure", receiptId = "missing-receipt")

        assertTransactionFails {
            dao.saveDailyReportWithExpense(report, invalidExpense)
        }

        assertEquals(0L, count("daily_reports"))
        assertEquals(0L, count("expense_records"))
    }

    @Test
    fun receiptAndExpenseSaveTogether() = runBlocking {
        val receipt = receipt("receipt-success")
        val expense = expense("receipt-expense-success", receiptId = receipt.id)

        dao.saveReceiptWithExpense(receipt, expense)

        assertEquals(1L, count("receipts"))
        assertEquals(1L, count("expense_records"))
    }

    @Test
    fun retryWithStableIdsDoesNotCreateDuplicateExpense() = runBlocking {
        val report = report("2026-07-21")
        val expense = expense("stable-expense-id", receiptId = null)

        dao.saveDailyReportWithExpense(report, expense)
        dao.saveDailyReportWithExpense(report.copy(updatedAt = 2L), expense.copy(updatedAt = 2L))

        assertEquals(1L, count("daily_reports"))
        assertEquals(1L, count("expense_records"))
        assertEquals(1L, count("expense_records", "id = 'stable-expense-id'"))
    }

    private suspend fun assertTransactionFails(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try {
            block()
        } catch (throwable: Throwable) {
            failure = throwable
        }
        assertNotNull("Expected transaction to fail", failure)
    }
    private fun count(table: String, where: String? = null): Long {
        val sql = buildString {
            append("SELECT COUNT(*) FROM ")
            append(table)
            if (where != null) append(" WHERE ").append(where)
        }
        return database.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private fun receipt(id: String) = ReceiptRecord(
        id = id,
        purchaseDate = "2026-07-20",
        capturedDate = "2026-07-20",
        registeredAt = 1L,
        storeName = "テスト店舗",
        totalAmount = 1_000L,
        taxAmount = 0L,
        registrationNumber = null,
        expenseCategory = ExpenseCategory.FoodPurchase,
        isConfirmed = true,
        memo = null,
        updatedAt = 1L
    )

    private fun expense(id: String, receiptId: String?) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-20",
        category = ExpenseCategory.FoodPurchase,
        supplierName = "テスト店舗",
        amount = 1_000L,
        paymentMethod = "現金",
        memo = null,
        receiptId = receiptId,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 1L
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