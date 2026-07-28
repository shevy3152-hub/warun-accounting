package com.warun.accounting.data.cancellation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.edit.ExpenseEditOperationExecutor
import com.warun.accounting.data.edit.ExpenseEditRepository
import com.warun.accounting.data.edit.CancelledExpenseEditException
import com.warun.accounting.data.edit.SavedExpenseEditRequest
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.util.PaymentMethodPrepaid
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
class ExpenseCancellationRepositoryTest {
    private lateinit var database: WarunDatabase
    private lateinit var repository: ExpenseCancellationRepository
    private var generatedId = 0

    @Before
    fun createDatabase() = runBlocking {
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
            idGenerator = ExpenseCancellationIdGenerator {
                "generated-reversal-${++generatedId}"
            }
        )
        insertAccount()
        insertCharge()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun cancellationAddsOneReversalAndRecordWithoutChangingExpenseLinkOrPurchase() =
        runBlocking {
            val fixture = insertFixture("normal")
            val result = repository.cancelExpense(fixture.request())

            assertFalse(result.idempotentReplay)
            assertEquals(fixture.expense, database.warunDao().getExpenseRecord(fixture.expense.id))
            assertEquals(
                fixture.link,
                database.expensePrepaidLinkDao().getByExpenseId(fixture.expense.id)
            )
            assertEquals(
                fixture.purchase,
                database.prepaidTransactionDao().getById(fixture.purchase.id)
            )
            val reversal = requireNotNull(
                database.prepaidTransactionDao().getById(result.reversalTransactionId)
            )
            assertEquals(PrepaidTransactionType.Reversal, reversal.transactionType)
            assertEquals(fixture.purchase.id, reversal.reversalOfTransactionId)
            assertEquals(500L, reversal.balanceDelta)
            assertEquals(1L, count("expense_cancellations"))
            assertEquals(2, transactionsFor(fixture.expense.id).size)
        }

    @Test
    fun identicalOperationReplayReturnsSameResultWithoutDuplicateRows() = runBlocking {
        val fixture = insertFixture("replay")
        val request = fixture.request()

        val first = repository.cancelExpense(request)
        val replay = repository.cancelExpense(request)

        assertFalse(first.idempotentReplay)
        assertTrue(replay.idempotentReplay)
        assertEquals(first.copy(idempotentReplay = true), replay)
        assertEquals(1L, count("expense_cancellations"))
        assertEquals(1, reversalsFor(fixture.expense.id).size)
    }

    @Test
    fun sameOperationWithDifferentFingerprintConflictsWithoutAdditionalRows() = runBlocking {
        val fixture = insertFixture("conflict")
        val request = fixture.request()
        repository.cancelExpense(request)

        assertEquals(
            ExpenseCancellationFailure.Conflict,
            failureOf {
                repository.cancelExpense(request.copy(reason = "different"))
            }
        )
        assertEquals(1L, count("expense_cancellations"))
        assertEquals(1, reversalsFor(fixture.expense.id).size)
    }

    @Test
    fun differentOperationForCancelledExpenseReturnsAlreadyCancelled() = runBlocking {
        val fixture = insertFixture("already")
        repository.cancelExpense(fixture.request())

        assertEquals(
            ExpenseCancellationFailure.AlreadyCancelled,
            failureOf {
                repository.cancelExpense(
                    fixture.request().copy(operationKey = operationKey("already-second"))
                )
            }
        )
        assertEquals(1L, count("expense_cancellations"))
        assertEquals(1, reversalsFor(fixture.expense.id).size)
    }

    @Test
    fun staleExpenseDoesNotChangeDatabase() = runBlocking {
        val fixture = insertFixture("stale-expense")
        val before = transactionsFor(fixture.expense.id)

        assertEquals(
            ExpenseCancellationFailure.StaleState,
            failureOf {
                repository.cancelExpense(
                    fixture.request().copy(expectedExpenseUpdatedAt = 11L)
                )
            }
        )
        assertEquals(before, transactionsFor(fixture.expense.id))
        assertEquals(0L, count("expense_cancellations"))
    }

    @Test
    fun staleLinkToNewPurchaseDoesNotCancelOldOrNewPurchase() = runBlocking {
        val fixture = insertFixture("stale-link")
        val newPurchase = fixture.purchase.copy(
            id = "purchase-stale-link-new",
            operationKey = "purchase-operation-stale-link-new"
        )
        database.prepaidTransactionDao().insert(newPurchase)
        database.expensePrepaidLinkDao().deleteByExpenseId(fixture.expense.id)
        database.expensePrepaidLinkDao().insert(
            fixture.link.copy(purchaseTransactionId = newPurchase.id)
        )
        val before = transactionsFor(fixture.expense.id)

        assertEquals(
            ExpenseCancellationFailure.StaleState,
            failureOf { repository.cancelExpense(fixture.request()) }
        )
        assertEquals(before, transactionsFor(fixture.expense.id))
        assertEquals(0L, count("expense_cancellations"))
    }

    @Test
    fun purchaseWithExistingReversalIsRejectedWithoutNewRows() = runBlocking {
        val fixture = insertFixture("inconsistent")
        val existingReversal = reversal(
            id = "existing-reversal",
            purchase = fixture.purchase,
            operationKey = "existing-reversal-operation",
            amount = fixture.expense.amount
        )
        database.prepaidTransactionDao().insert(existingReversal)
        val before = transactionsFor(fixture.expense.id)

        assertEquals(
            ExpenseCancellationFailure.PrepaidStateInconsistent,
            failureOf { repository.cancelExpense(fixture.request()) }
        )
        assertEquals(before, transactionsFor(fixture.expense.id))
        assertEquals(0L, count("expense_cancellations"))
    }

    @Test
    fun cancellationInsertFailureRollsBackReversal() = runBlocking {
        val fixture = insertFixture("rollback")
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_expense_cancellation
            BEFORE INSERT ON expense_cancellations
            BEGIN
                SELECT RAISE(ABORT, 'forced cancellation failure');
            END
            """.trimIndent()
        )

        assertEquals(
            ExpenseCancellationFailure.DatabaseFailure,
            failureOf { repository.cancelExpense(fixture.request()) }
        )
        assertEquals(0L, count("expense_cancellations"))
        assertEquals(0, reversalsFor(fixture.expense.id).size)
        assertEquals(fixture.purchase, database.prepaidTransactionDao().getById(fixture.purchase.id))
    }

    @Test
    fun editedExpenseCancelsOnlyPurchaseReferencedByCurrentLink() = runBlocking {
        val fixture = insertFixture("edited")
        val oldReversal = reversal(
            id = "edited-old-reversal",
            purchase = fixture.purchase,
            operationKey = "edited-old-reversal-operation",
            amount = 500L
        )
        database.prepaidTransactionDao().insert(oldReversal)
        val editedExpense = fixture.expense.copy(amount = 700L, updatedAt = 11L)
        database.warunDao().insertExpenseRecord(editedExpense)
        val currentPurchase = fixture.purchase.copy(
            id = "edited-current-purchase",
            balanceDelta = -700L,
            operationKey = "edited-current-purchase-operation",
            createdAt = 11L
        )
        database.prepaidTransactionDao().insert(currentPurchase)
        database.expensePrepaidLinkDao().deleteByExpenseId(fixture.expense.id)
        database.expensePrepaidLinkDao().insert(
            fixture.link.copy(
                purchaseTransactionId = currentPurchase.id,
                updatedAt = 11L
            )
        )

        val result = repository.cancelExpense(
            fixture.request().copy(
                expectedExpenseUpdatedAt = 11L,
                expectedOriginalPurchaseTransactionId = currentPurchase.id,
                expectedAmount = 700L
            )
        )

        val newReversal = requireNotNull(
            database.prepaidTransactionDao().getById(result.reversalTransactionId)
        )
        assertEquals(currentPurchase.id, newReversal.reversalOfTransactionId)
        assertEquals(700L, newReversal.balanceDelta)
        assertEquals(oldReversal, database.prepaidTransactionDao().getById(oldReversal.id))
        assertEquals(fixture.purchase, database.prepaidTransactionDao().getById(fixture.purchase.id))
        assertEquals(
            currentPurchase.id,
            database.expensePrepaidLinkDao()
                .getByExpenseId(fixture.expense.id)
                ?.purchaseTransactionId
        )
    }

    @Test
    fun evidenceMetadataAndLinkRemainUnchanged() = runBlocking {
        val fixture = insertFixture("evidence", withEvidence = true)
        val beforeLinks = database.warunDao().getEvidenceLinksForExpense(fixture.expense.id)
        val beforeEvidence = requireNotNull(database.warunDao().getEvidenceRecord("evidence-evidence"))

        repository.cancelExpense(fixture.request())

        assertEquals(beforeLinks, database.warunDao().getEvidenceLinksForExpense(fixture.expense.id))
        assertEquals(
            beforeEvidence,
            database.warunDao().getEvidenceRecord(beforeEvidence.id)
        )
    }

    @Test
    fun cancelledExpenseIsRejectedByExpenseEditRepository() = runBlocking {
        val fixture = insertFixture("edit-block")
        repository.cancelExpense(fixture.request())
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
                    operationKey = "edit-cancelled-expense",
                    expense = fixture.expense.copy(memo = "must not change"),
                    prepaidAccountId = PrepaidAccountId.Majica,
                    requestedAt = 30L
                )
            )
        }.exceptionOrNull()

        assertEquals(fixture.expense.id, (error as? CancelledExpenseEditException)?.expenseId)
        assertEquals(fixture.expense, database.warunDao().getExpenseRecord(fixture.expense.id))
        assertNull(
            database.expenseEditOperationDao()
                .getByOperationKey("edit-cancelled-expense")
        )
        assertEquals(1, reversalsFor(fixture.expense.id).size)
    }

    private suspend fun insertFixture(
        suffix: String,
        withEvidence: Boolean = false
    ): Fixture {
        val expense = ExpenseRecord(
            id = "expense-$suffix",
            expenseDate = "2026-07-28",
            category = ExpenseCategory.OtherExpense,
            supplierName = "test supplier",
            amount = 500L,
            paymentMethod = PaymentMethodPrepaid,
            memo = "memo",
            receiptId = null,
            sourceType = ExpenseSourceType.Manual,
            createdAt = 1L,
            updatedAt = 10L
        )
        database.warunDao().insertExpenseRecord(expense)
        val purchase = PrepaidTransactionRecord(
            id = "purchase-$suffix",
            accountId = PrepaidAccountId.Majica,
            transactionDate = expense.expenseDate,
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = -expense.amount,
            expenseId = expense.id,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = "purchase-operation-$suffix",
            memo = expense.memo.orEmpty(),
            createdAt = 2L
        )
        database.prepaidTransactionDao().insert(purchase)
        val link = ExpensePrepaidLinkRecord(
            expenseId = expense.id,
            purchaseTransactionId = purchase.id,
            linkedAt = 2L,
            updatedAt = 2L
        )
        database.expensePrepaidLinkDao().insert(link)
        if (withEvidence) {
            val evidence = EvidenceRecord(
                id = "evidence-$suffix",
                captureId = "capture-$suffix",
                storedUri = "file:/stored-$suffix.jpg",
                byteSize = 123L,
                sha256 = "a".repeat(64),
                state = EvidenceRecordState.Stored,
                createdAt = 3L,
                storedAt = 4L,
                updatedAt = 4L
            )
            database.warunDao().addEvidenceToExpense(
                expense.id,
                evidence,
                ExpenseEvidenceLinkRecord(expense.id, evidence.id, 4L)
            )
        }
        return Fixture(expense, purchase, link)
    }

    private suspend fun insertAccount() {
        database.prepaidAccountDao().insert(
            PrepaidAccountRecord(
                id = PrepaidAccountId.Majica,
                type = PrepaidAccountType.Majica,
                name = "majica",
                isActive = true,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
    }

    private suspend fun insertCharge() {
        database.prepaidTransactionDao().insert(
            PrepaidTransactionRecord(
                id = "initial-charge",
                accountId = PrepaidAccountId.Majica,
                transactionDate = "2026-07-28",
                transactionType = PrepaidTransactionType.Charge,
                balanceDelta = 10_000L,
                expenseId = null,
                chargeSource = "CASH",
                reversalOfTransactionId = null,
                operationKey = "initial-charge-operation",
                memo = "",
                createdAt = 1L
            )
        )
    }

    private fun reversal(
        id: String,
        purchase: PrepaidTransactionRecord,
        operationKey: String,
        amount: Long
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = purchase.accountId,
        transactionDate = "2026-07-29",
        transactionType = PrepaidTransactionType.Reversal,
        balanceDelta = amount,
        expenseId = purchase.expenseId,
        chargeSource = null,
        reversalOfTransactionId = purchase.id,
        operationKey = operationKey,
        memo = "",
        createdAt = 12L
    )

    private suspend fun transactionsFor(expenseId: String): List<PrepaidTransactionRecord> =
        database.prepaidTransactionDao().observeByExpense(expenseId).first()

    private suspend fun reversalsFor(expenseId: String): List<PrepaidTransactionRecord> =
        transactionsFor(expenseId).filter {
            it.transactionType == PrepaidTransactionType.Reversal
        }

    private fun count(table: String): Long =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }

    private suspend fun failureOf(block: suspend () -> Unit): ExpenseCancellationFailure? =
        (runCatching { block() }.exceptionOrNull() as? ExpenseCancellationException)
            ?.failure

    private fun operationKey(suffix: String): String =
        "expense-cancel:123e4567-e89b-42d3-a456-" +
            suffix.hashCode().toUInt().toString(16).padStart(12, '0')

    private data class Fixture(
        val expense: ExpenseRecord,
        val purchase: PrepaidTransactionRecord,
        val link: ExpensePrepaidLinkRecord
    ) {
        fun request() = ExpenseCancellationRequest(
            operationKey = "expense-cancel:123e4567-e89b-42d3-a456-" +
                expense.id.hashCode().toUInt().toString(16).padStart(12, '0'),
            expenseId = expense.id,
            expectedExpenseUpdatedAt = expense.updatedAt,
            expectedOriginalPurchaseTransactionId = purchase.id,
            expectedPrepaidAccountId = purchase.accountId,
            expectedAmount = expense.amount,
            expectedPurchaseDate = purchase.transactionDate,
            cancellationDate = "2026-07-29",
            reason = "cancel test"
        )
    }
}
