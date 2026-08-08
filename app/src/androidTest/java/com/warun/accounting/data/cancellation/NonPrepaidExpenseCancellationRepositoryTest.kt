package com.warun.accounting.data.cancellation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.edit.CancelledExpenseEditException
import com.warun.accounting.data.edit.ExpenseEditOperationExecutor
import com.warun.accounting.data.edit.ExpenseEditRepository
import com.warun.accounting.data.edit.SavedExpenseEditRequest
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodCredit
import com.warun.accounting.util.PaymentMethodCreditPurchase
import com.warun.accounting.util.PaymentMethodElectronicMoney
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NonPrepaidExpenseCancellationRepositoryTest {
    private lateinit var database: WarunDatabase
    private lateinit var repository: ExpenseCancellationRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, WarunDatabase::class.java).build()
        repository = ExpenseCancellationRepository(
            database = database,
            warunDao = database.warunDao(),
            accountDao = database.prepaidAccountDao(),
            transactionDao = database.prepaidTransactionDao(),
            prepaidLinkDao = database.expensePrepaidLinkDao(),
            cancellationDao = database.expenseCancellationDao(),
            clock = ExpenseCancellationClock { 20L },
            idGenerator = ExpenseCancellationIdGenerator { error("must not create reversal") }
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun cashCancellationPreservesExpenseEvidenceAndLedger() = runBlocking {
        assertCancellationContract(PaymentMethodCash)
    }

    @Test
    fun creditCancellationPreservesExpenseEvidenceAndLedger() = runBlocking {
        assertCancellationContract(PaymentMethodCredit)
    }

    @Test
    fun electronicMoneyCancellationPreservesExpenseEvidenceAndLedger() = runBlocking {
        assertCancellationContract(PaymentMethodElectronicMoney)
    }

    @Test
    fun creditPurchaseCancellationPreservesExpenseEvidenceAndLedger() = runBlocking {
        assertCancellationContract(PaymentMethodCreditPurchase)
    }

    @Test
    fun identicalReplayIsIdempotentAndDifferentOperationIsRejected() = runBlocking {
        val expense = insertExpense("double", PaymentMethodCash, withEvidence = false)
        val request = request(expense)

        val first = repository.cancelExpense(request)
        val replay = repository.cancelExpense(request)

        assertFalse(first.idempotentReplay)
        assertTrue(replay.idempotentReplay)
        assertEquals(first.copy(idempotentReplay = true), replay)
        assertEquals(
            ExpenseCancellationFailure.AlreadyCancelled,
            failureOf {
                repository.cancelExpense(
                    request.copy(
                        operationKey =
                            "expense-cancel:123e4567-e89b-42d3-a456-426614174099"
                    )
                )
            }
        )
        assertEquals(1L, count("expense_cancellations"))
        assertTrue(database.prepaidTransactionDao().observeAll().first().isEmpty())
    }

    @Test
    fun cancelledNonPrepaidExpenseCannotBeEdited() = runBlocking {
        val expense = insertExpense("edit", PaymentMethodCredit, withEvidence = false)
        repository.cancelExpense(request(expense))
        val editRepository = ExpenseEditRepository(
            operationExecutor = ExpenseEditOperationExecutor(
                database,
                database.expenseEditOperationDao()
            ),
            warunDao = database.warunDao(),
            accountDao = database.prepaidAccountDao(),
            transactionDao = database.prepaidTransactionDao(),
            prepaidLinkDao = database.expensePrepaidLinkDao(),
            cancellationDao = database.expenseCancellationDao()
        )

        val error = runCatching {
            editRepository.editExpense(
                SavedExpenseEditRequest(
                    operationKey = "edit-cancelled-non-prepaid",
                    expense = expense.copy(amount = 999L),
                    prepaidAccountId = null,
                    requestedAt = 30L
                )
            )
        }.exceptionOrNull()

        assertEquals(expense.id, (error as? CancelledExpenseEditException)?.expenseId)
        assertEquals(expense, database.warunDao().getExpenseRecord(expense.id))
        assertNull(
            database.expenseEditOperationDao()
                .getByOperationKey("edit-cancelled-non-prepaid")
        )
    }

    @Test
    fun cancellationInsertFailureRollsBackWithoutLedgerOrExpenseChanges() = runBlocking {
        val expense = insertExpense("rollback", PaymentMethodElectronicMoney, false)
        val beforeLedger = database.prepaidTransactionDao().observeAll().first()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_non_prepaid_cancellation
            BEFORE INSERT ON expense_cancellations
            BEGIN
                SELECT RAISE(ABORT, 'forced cancellation failure');
            END
            """.trimIndent()
        )

        assertEquals(
            ExpenseCancellationFailure.DatabaseFailure,
            failureOf { repository.cancelExpense(request(expense)) }
        )
        assertEquals(expense, database.warunDao().getExpenseRecord(expense.id))
        assertEquals(beforeLedger, database.prepaidTransactionDao().observeAll().first())
        assertEquals(0L, count("expense_cancellations"))
    }

    private suspend fun assertCancellationContract(paymentMethod: String) {
        val suffix = paymentMethod.hashCode().toUInt().toString(16)
        val expense = insertExpense(suffix, paymentMethod, withEvidence = true)
        val beforeLedger = database.prepaidTransactionDao().observeAll().first()
        val beforeEvidence = database.warunDao().getEvidenceLinksForExpense(expense.id)

        val snapshot = repository.loadCancellationSnapshot(expense.id)
        assertEquals(paymentMethod, snapshot.paymentMethod)
        assertNull(snapshot.originalPurchaseTransactionId)
        assertNull(snapshot.prepaidAccountId)

        val result = repository.cancelExpense(request(expense))
        val cancellation = database.expenseCancellationDao().getByExpenseId(expense.id)

        assertEquals(paymentMethod, result.paymentMethod)
        assertNull(result.originalPurchaseTransactionId)
        assertNull(result.reversalTransactionId)
        assertNull(result.prepaidAccountId)
        assertEquals(expense, database.warunDao().getExpenseRecord(expense.id))
        assertTrue(database.warunDao().observeExpenseRecords().first().isEmpty())
        assertTrue(
            database.warunDao().observeExpenseRecordsByDateAndCategory(
                expense.expenseDate,
                expense.category
            ).first().isEmpty()
        )
        assertEquals(
            0L,
            database.warunDao().observeExpenseTotalByDateAndCategory(
                expense.expenseDate,
                expense.category
            ).first()
        )
        assertEquals(expense.id, cancellation?.expenseId)
        assertNull(cancellation?.originalPurchaseTransactionId)
        assertNull(cancellation?.reversalTransactionId)
        assertEquals(beforeEvidence, database.warunDao().getEvidenceLinksForExpense(expense.id))
        assertEquals(
            "evidence-$suffix",
            database.warunDao().getEvidenceRecord("evidence-$suffix")?.id
        )
        assertEquals(beforeLedger, database.prepaidTransactionDao().observeAll().first())
        assertNull(database.expensePrepaidLinkDao().getByExpenseId(expense.id))
    }

    private suspend fun insertExpense(
        suffix: String,
        paymentMethod: String,
        withEvidence: Boolean
    ): ExpenseRecord {
        val expense = ExpenseRecord(
            id = "non-prepaid-$suffix",
            expenseDate = "2026-08-08",
            category = ExpenseCategory.OtherExpense,
            supplierName = "test supplier",
            amount = 500L,
            paymentMethod = paymentMethod,
            memo = "memo",
            receiptId = null,
            sourceType = ExpenseSourceType.Manual,
            createdAt = 1L,
            updatedAt = 10L
        )
        database.warunDao().insertExpenseRecord(expense)
        if (withEvidence) {
            val evidence = EvidenceRecord(
                id = "evidence-$suffix",
                captureId = "capture-$suffix",
                storedUri = "file:/stored-$suffix.jpg",
                byteSize = 123L,
                sha256 = "a".repeat(64),
                state = EvidenceRecordState.Stored,
                createdAt = 2L,
                storedAt = 3L,
                updatedAt = 3L
            )
            database.warunDao().addEvidenceToExpense(
                expense.id,
                evidence,
                ExpenseEvidenceLinkRecord(expense.id, evidence.id, 3L)
            )
        }
        return expense
    }

    private fun request(expense: ExpenseRecord) = ExpenseCancellationRequest(
        operationKey = "expense-cancel:123e4567-e89b-42d3-a456-" +
            expense.id.hashCode().toUInt().toString(16).padStart(12, '0'),
        expenseId = expense.id,
        expectedExpenseUpdatedAt = expense.updatedAt,
        expectedOriginalPurchaseTransactionId = null,
        expectedPrepaidAccountId = null,
        expectedAmount = expense.amount,
        expectedPurchaseDate = expense.expenseDate,
        cancellationDate = "2026-08-08",
        reason = "cancel test",
        expectedPaymentMethod = requireNotNull(expense.paymentMethod)
    )

    private suspend fun failureOf(block: suspend () -> Unit): ExpenseCancellationFailure? =
        (runCatching { block() }.exceptionOrNull() as? ExpenseCancellationException)?.failure

    private fun count(table: String): Long =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
}
