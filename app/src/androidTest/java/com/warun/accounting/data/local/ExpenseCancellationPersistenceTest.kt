package com.warun.accounting.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.di.DatabaseModule
import com.warun.accounting.util.PaymentMethodPrepaid
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseCancellationPersistenceTest {
    private lateinit var database: WarunDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            WarunDatabase::class.java,
            "expense-cancellation-${UUID.randomUUID()}"
        )
            .addCallback(DatabaseModule.PREPAID_DATABASE_CALLBACK)
            .build()
        database.openHelper.writableDatabase
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertsAndReadsCancellationWithoutChangingOriginalRecords() = runBlocking {
        val fixture = insertFixture("one")
        val cancellation = fixture.cancellation()

        database.expenseCancellationDao().insert(cancellation)

        assertEquals(
            cancellation,
            database.expenseCancellationDao().getByExpenseId(fixture.expense.id)
        )
        assertEquals(
            cancellation,
            database.expenseCancellationDao().getByOperationKey(cancellation.operationKey)
        )
        assertEquals(
            cancellation,
            database.expenseCancellationDao().getByOriginalPurchaseTransactionId(
                fixture.purchase.id
            )
        )
        assertTrue(database.expenseCancellationDao().existsByExpenseId(fixture.expense.id))
        assertTrue(
            database.expenseCancellationDao().existsByOperationKey(cancellation.operationKey)
        )
        assertEquals(fixture.expense, database.warunDao().getExpenseRecord(fixture.expense.id))
        assertEquals(
            fixture.purchase,
            database.prepaidTransactionDao().getById(fixture.purchase.id)
        )
        assertEquals(
            fixture.link,
            database.expensePrepaidLinkDao().getByExpenseId(fixture.expense.id)
        )
    }

    @Test
    fun rejectsDuplicateExpenseOperationPurchaseAndReversalKeys() = runBlocking {
        val first = insertFixture("first")
        val second = insertFixture("second")
        val cancellation = first.cancellation()
        database.expenseCancellationDao().insert(cancellation)

        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                cancellation.copy(
                    operationKey = operationKey("duplicate-expense"),
                    originalPurchaseTransactionId = second.purchase.id,
                    reversalTransactionId = second.reversal.id
                )
            )
        }
        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                second.cancellation().copy(operationKey = cancellation.operationKey)
            )
        }
        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                second.cancellation().copy(
                    originalPurchaseTransactionId = first.purchase.id
                )
            )
        }
        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                second.cancellation().copy(reversalTransactionId = first.reversal.id)
            )
        }
    }

    @Test
    fun rejectsMissingExpensePurchaseAndReversalForeignKeys() = runBlocking {
        val fixture = insertFixture("foreign-key")

        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                fixture.cancellation().copy(expenseId = "missing-expense")
            )
        }
        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                fixture.cancellation().copy(
                    originalPurchaseTransactionId = "missing-purchase"
                )
            )
        }
        assertConstraintFailure {
            database.expenseCancellationDao().insert(
                fixture.cancellation().copy(reversalTransactionId = "missing-reversal")
            )
        }
    }

    @Test
    fun cancellationRestrictsPhysicalDeletionOfExpensePurchaseAndReversal() = runBlocking {
        val fixture = insertFixture("restrict")
        database.expenseCancellationDao().insert(fixture.cancellation())

        assertConstraintFailure {
            database.warunDao().deleteExpenseRecord(fixture.expense)
        }
        assertConstraintFailure {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM prepaid_transactions WHERE id = ?",
                arrayOf(fixture.purchase.id)
            )
        }
        assertConstraintFailure {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM prepaid_transactions WHERE id = ?",
                arrayOf(fixture.reversal.id)
            )
        }

        assertNotNull(database.warunDao().getExpenseRecord(fixture.expense.id))
        assertNotNull(database.prepaidTransactionDao().getById(fixture.purchase.id))
        assertNotNull(database.prepaidTransactionDao().getById(fixture.reversal.id))
    }

    private suspend fun insertFixture(suffix: String): Fixture {
        val expense = ExpenseRecord(
            id = "expense-$suffix",
            expenseDate = "2026-07-28",
            category = "other_expense",
            supplierName = "store",
            amount = 500L,
            paymentMethod = PaymentMethodPrepaid,
            memo = "memo",
            receiptId = null,
            sourceType = ExpenseSourceType.Manual,
            createdAt = 1L,
            updatedAt = 1L
        )
        database.warunDao().insertExpenseRecord(expense)
        val purchase = transaction(
            id = "purchase-$suffix",
            expenseId = expense.id,
            delta = -expense.amount
        )
        database.prepaidTransactionDao().insert(purchase)
        val reversal = transaction(
            id = "reversal-$suffix",
            expenseId = expense.id,
            delta = expense.amount,
            reversalOf = purchase.id
        )
        database.prepaidTransactionDao().insert(reversal)
        val link = ExpensePrepaidLinkRecord(
            expenseId = expense.id,
            purchaseTransactionId = purchase.id,
            linkedAt = 1L,
            updatedAt = 1L
        )
        database.expensePrepaidLinkDao().insert(link)
        return Fixture(expense, purchase, reversal, link)
    }

    private fun transaction(
        id: String,
        expenseId: String,
        delta: Long,
        reversalOf: String? = null
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-28",
        transactionType = if (reversalOf == null) {
            PrepaidTransactionType.Purchase
        } else {
            PrepaidTransactionType.Reversal
        },
        balanceDelta = delta,
        expenseId = expenseId,
        chargeSource = null,
        reversalOfTransactionId = reversalOf,
        operationKey = "operation-$id",
        memo = "",
        createdAt = 1L
    )

    private fun operationKey(suffix: String) =
        "expense-cancel:123e4567-e89b-42d3-a456-${suffix.hashCode().toUInt().toString(16).padStart(12, '0')}"

    private suspend fun assertConstraintFailure(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(
            "Expected SQLiteConstraintException but was $error",
            generateSequence(error) { it.cause }
                .any { it is SQLiteConstraintException }
        )
    }

    private data class Fixture(
        val expense: ExpenseRecord,
        val purchase: PrepaidTransactionRecord,
        val reversal: PrepaidTransactionRecord,
        val link: ExpensePrepaidLinkRecord
    ) {
        fun cancellation() = ExpenseCancellationRecord(
            expenseId = expense.id,
            operationKey = "expense-cancel:123e4567-e89b-42d3-a456-" +
                expense.id.hashCode().toUInt().toString(16).padStart(12, '0'),
            requestFingerprint = "a".repeat(64),
            originalPurchaseTransactionId = purchase.id,
            reversalTransactionId = reversal.id,
            cancellationDate = "2026-07-29",
            cancelledAt = 2L,
            reason = null
        )
    }
}
