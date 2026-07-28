package com.warun.accounting.data.edit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.data.prepaid.PrepaidValidationException
import com.warun.accounting.data.prepaid.PrepaidValidationFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedExpenseEditRepositoryTest {
    private lateinit var database: WarunDatabase
    private lateinit var repository: ExpenseEditRepository

    @Before
    fun createDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, WarunDatabase::class.java).build()
        repository = ExpenseEditRepository(
            operationExecutor = ExpenseEditOperationExecutor(
                database,
                database.expenseEditOperationDao()
            ),
            warunDao = database.warunDao(),
            accountDao = database.prepaidAccountDao(),
            transactionDao = database.prepaidTransactionDao(),
            prepaidLinkDao = database.expensePrepaidLinkDao()
        )
        insertAccount(PrepaidAccountId.Majica, PrepaidAccountType.Majica)
        insertAccount(PrepaidAccountId.AuPayPrepaid, PrepaidAccountType.AuPayPrepaid)
        insertCharge(PrepaidAccountId.Majica, 10_000L, "charge-majica")
        insertCharge(PrepaidAccountId.AuPayPrepaid, 10_000L, "charge-au-pay")
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun nonPrepaidAmountChangeUpdatesOnlyExpense() = runBlocking {
        val original = expense("cash-expense", 500L, PaymentMethodCash)
        database.warunDao().insertExpenseRecord(original)

        repository.editExpense(
            request("edit-cash", original.copy(amount = 700L, memo = "changed"))
        )

        val saved = requireNotNull(database.warunDao().getExpenseRecord(original.id))
        assertEquals(700L, saved.amount)
        assertEquals("changed", saved.memo)
        assertNull(database.expensePrepaidLinkDao().getByExpenseId(original.id))
        assertTrue(transactionsFor(original.id).isEmpty())
    }

    @Test
    fun prepaidAmountChangeCreatesReversalAndNewPurchaseAndSwitchesLink() = runBlocking {
        val original = insertPrepaidExpense("prepaid-amount", 500L, PrepaidAccountId.Majica)
        val oldLink = requireNotNull(database.expensePrepaidLinkDao().getByExpenseId(original.id))
        val oldPurchase = requireNotNull(
            database.prepaidTransactionDao().getById(oldLink.purchaseTransactionId)
        )

        repository.editExpense(
            request(
                "edit-prepaid-amount",
                original.copy(amount = 700L),
                PrepaidAccountId.Majica
            )
        )

        val transactions = transactionsFor(original.id)
        val reversal = transactions.single {
            it.transactionType == PrepaidTransactionType.Reversal
        }
        val newPurchase = transactions.single {
            it.transactionType == PrepaidTransactionType.Purchase && it.id != oldPurchase.id
        }
        assertEquals(500L, reversal.balanceDelta)
        assertEquals(oldPurchase.id, reversal.reversalOfTransactionId)
        assertEquals(-700L, newPurchase.balanceDelta)
        assertEquals(PrepaidAccountId.Majica, newPurchase.accountId)
        assertEquals(
            newPurchase.id,
            database.expensePrepaidLinkDao().getByExpenseId(original.id)?.purchaseTransactionId
        )
        assertEquals(-500L, database.prepaidTransactionDao().getById(oldPurchase.id)?.balanceDelta)
    }

    @Test
    fun prepaidAccountChangeReversesOldAccountAndPurchasesFromNewAccount() = runBlocking {
        val original = insertPrepaidExpense("prepaid-account", 500L, PrepaidAccountId.Majica)
        val oldPurchaseId = requireNotNull(
            database.expensePrepaidLinkDao().getByExpenseId(original.id)
        ).purchaseTransactionId

        repository.editExpense(
            request(
                "edit-prepaid-account",
                original,
                PrepaidAccountId.AuPayPrepaid
            )
        )

        val transactions = transactionsFor(original.id)
        assertEquals(
            PrepaidAccountId.Majica,
            transactions.single {
                it.transactionType == PrepaidTransactionType.Reversal
            }.accountId
        )
        val newPurchase = transactions.single {
            it.transactionType == PrepaidTransactionType.Purchase && it.id != oldPurchaseId
        }
        assertEquals(PrepaidAccountId.AuPayPrepaid, newPurchase.accountId)
        assertEquals(
            newPurchase.id,
            database.expensePrepaidLinkDao().getByExpenseId(original.id)?.purchaseTransactionId
        )
    }

    @Test
    fun prepaidToCashCreatesOnlyReversalAndRemovesLink() = runBlocking {
        val original = insertPrepaidExpense("prepaid-to-cash", 500L, PrepaidAccountId.Majica)

        repository.editExpense(
            request(
                "edit-prepaid-to-cash",
                original.copy(paymentMethod = PaymentMethodCash),
                null
            )
        )

        assertNull(database.expensePrepaidLinkDao().getByExpenseId(original.id))
        assertEquals(PaymentMethodCash, database.warunDao().getExpenseRecord(original.id)?.paymentMethod)
        assertEquals(
            1,
            transactionsFor(original.id).count {
                it.transactionType == PrepaidTransactionType.Reversal
            }
        )
        assertEquals(
            1,
            transactionsFor(original.id).count {
                it.transactionType == PrepaidTransactionType.Purchase
            }
        )
    }

    @Test
    fun cashToPrepaidCreatesPurchaseAndLinkWithoutReversal() = runBlocking {
        val original = expense("cash-to-prepaid", 500L, PaymentMethodCash)
        database.warunDao().insertExpenseRecord(original)

        repository.editExpense(
            request(
                "edit-cash-to-prepaid",
                original.copy(paymentMethod = PaymentMethodPrepaid),
                PrepaidAccountId.Majica
            )
        )

        val transactions = transactionsFor(original.id)
        assertEquals(1, transactions.count { it.transactionType == PrepaidTransactionType.Purchase })
        assertEquals(0, transactions.count { it.transactionType == PrepaidTransactionType.Reversal })
        assertEquals(
            transactions.single().id,
            database.expensePrepaidLinkDao().getByExpenseId(original.id)?.purchaseTransactionId
        )
    }

    @Test
    fun unchangedPrepaidAttributesKeepExistingLedgerAndLink() = runBlocking {
        val original = insertPrepaidExpense("prepaid-unchanged", 500L, PrepaidAccountId.Majica)
        val oldLink = requireNotNull(database.expensePrepaidLinkDao().getByExpenseId(original.id))

        repository.editExpense(
            request(
                "edit-prepaid-memo",
                original.copy(
                    expenseDate = "2026-07-29",
                    supplierName = "changed supplier",
                    memo = "changed memo"
                ),
                PrepaidAccountId.Majica
            )
        )

        assertEquals(oldLink, database.expensePrepaidLinkDao().getByExpenseId(original.id))
        assertEquals(1, transactionsFor(original.id).size)
        assertEquals("2026-07-29", database.warunDao().getExpenseRecord(original.id)?.expenseDate)
    }

    @Test
    fun identicalRetryIsNoOpAndDifferentRequestConflicts() = runBlocking {
        val original = insertPrepaidExpense("idempotent-edit", 500L, PrepaidAccountId.Majica)
        val edit = request(
            "idempotent-operation",
            original.copy(amount = 700L),
            PrepaidAccountId.Majica
        )

        val first = repository.editExpense(edit)
        val retry = repository.editExpense(edit)
        val beforeConflict = transactionsFor(original.id)
        val conflict = runCatching {
            repository.editExpense(
                edit.copy(expense = edit.expense.copy(amount = 701L))
            )
        }.exceptionOrNull()

        assertFalse(first.wasAlreadyApplied)
        assertTrue(retry.wasAlreadyApplied)
        assertEquals(beforeConflict, transactionsFor(original.id))
        assertEquals(
            ExpenseEditOperationFailure.OperationConflict,
            (conflict as? ExpenseEditOperationException)?.failure
        )
        assertEquals(700L, database.warunDao().getExpenseRecord(original.id)?.amount)
    }

    @Test
    fun evidenceAdditionKeepsExistingEvidenceAndRetryDoesNotDuplicate() = runBlocking {
        val original = expense("evidence-edit", 500L, PaymentMethodCash)
        database.warunDao().insertExpenseRecord(original)
        val existingEvidence = evidence("existing-evidence", "a".repeat(64))
        database.warunDao().addEvidenceToExpense(
            original.id,
            existingEvidence,
            ExpenseEvidenceLinkRecord(original.id, existingEvidence.id, 1L)
        )
        val newEvidence = evidence("new-evidence", "b".repeat(64))
        val edit = request(
            operationKey = "edit-add-evidence",
            expense = original.copy(memo = "with evidence"),
            evidence = newEvidence
        )

        repository.editExpense(edit)
        repository.editExpense(edit)

        val links = database.warunDao().getEvidenceLinksForExpense(original.id)
        assertEquals(setOf(existingEvidence.id, newEvidence.id), links.map { it.evidenceId }.toSet())
        assertEquals(2, links.size)
    }

    @Test
    fun failureAfterLedgerWritesRollsBackExpenseLedgerLinkEvidenceAndOperation() = runBlocking {
        val original = insertPrepaidExpense("rollback-edit", 500L, PrepaidAccountId.Majica)
        val other = expense("other-owner", 100L, PaymentMethodCash)
        database.warunDao().insertExpenseRecord(other)
        val conflictingEvidence = evidence("shared-evidence", "a".repeat(64))
        database.warunDao().addEvidenceToExpense(
            other.id,
            conflictingEvidence,
            ExpenseEvidenceLinkRecord(other.id, conflictingEvidence.id, 1L)
        )
        val beforeTransactions = transactionsFor(original.id)
        val beforeLink = database.expensePrepaidLinkDao().getByExpenseId(original.id)
        val operationKey = "edit-rollback"

        assertTrue(
            runCatching {
                repository.editExpense(
                    request(
                        operationKey = operationKey,
                        expense = original.copy(amount = 700L),
                        prepaidAccountId = PrepaidAccountId.Majica,
                        evidence = conflictingEvidence.copy(sha256 = "b".repeat(64))
                    )
                )
            }.isFailure
        )

        assertEquals(original, database.warunDao().getExpenseRecord(original.id))
        assertEquals(beforeTransactions, transactionsFor(original.id))
        assertEquals(beforeLink, database.expensePrepaidLinkDao().getByExpenseId(original.id))
        assertEquals(1, database.warunDao().getEvidenceLinksForExpense(other.id).size)
        assertTrue(database.warunDao().getEvidenceLinksForExpense(original.id).isEmpty())
        assertNull(database.expenseEditOperationDao().getByOperationKey(operationKey))
    }

    @Test
    fun missingExpenseInvalidInputAndCorruptPrepaidStateFailWithoutSideEffects() = runBlocking {
        val missing = expense("missing", 500L, PaymentMethodCash)
        assertEquals(
            SavedExpenseEditFailure.ExpenseNotFound,
            failureOf { repository.editExpense(request("edit-missing", missing)) }
        )
        val invalid = expense("invalid", 0L, PaymentMethodCash)
        assertEquals(
            SavedExpenseEditFailure.InvalidRequest,
            failureOf { repository.editExpense(request("edit-invalid", invalid)) }
        )

        val corrupt = expense("corrupt", 500L, PaymentMethodPrepaid)
        database.warunDao().insertExpenseRecord(corrupt)
        assertEquals(
            SavedExpenseEditFailure.ExistingPrepaidStateInconsistent,
            failureOf {
                repository.editExpense(
                    request("edit-corrupt", corrupt, PrepaidAccountId.Majica)
                )
            }
        )
        assertTrue(transactionsFor(corrupt.id).isEmpty())
        assertNull(database.expenseEditOperationDao().getByOperationKey("edit-corrupt"))

        val cash = expense("invalid-account", 500L, PaymentMethodCash)
        database.warunDao().insertExpenseRecord(cash)
        val accountError = runCatching {
            repository.editExpense(
                request(
                    "edit-invalid-account",
                    cash.copy(paymentMethod = PaymentMethodPrepaid),
                    "missing-account"
                )
            )
        }.exceptionOrNull()
        assertEquals(
            PrepaidValidationFailure.AccountNotFound,
            (accountError as? PrepaidValidationException)?.failure
        )
        assertEquals(cash, database.warunDao().getExpenseRecord(cash.id))
        assertNull(database.expenseEditOperationDao().getByOperationKey("edit-invalid-account"))
    }

    private suspend fun insertPrepaidExpense(
        id: String,
        amount: Long,
        accountId: String
    ): ExpenseRecord {
        val expense = expense(id, amount, PaymentMethodPrepaid)
        database.warunDao().insertExpenseRecord(expense)
        val purchase = PrepaidTransactionRecord(
            id = "purchase-$id",
            accountId = accountId,
            transactionDate = expense.expenseDate,
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = -amount,
            expenseId = id,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = "purchase-operation-$id",
            memo = expense.memo.orEmpty(),
            createdAt = 2L
        )
        database.prepaidTransactionDao().insert(purchase)
        database.expensePrepaidLinkDao().insert(
            ExpensePrepaidLinkRecord(id, purchase.id, 2L, 2L)
        )
        return expense
    }

    private suspend fun insertAccount(id: String, type: String) {
        database.prepaidAccountDao().insert(
            PrepaidAccountRecord(id, type, id, true, 1L, 1L)
        )
    }

    private suspend fun insertCharge(accountId: String, amount: Long, operationKey: String) {
        database.prepaidTransactionDao().insert(
            PrepaidTransactionRecord(
                id = operationKey,
                accountId = accountId,
                transactionDate = "2026-07-28",
                transactionType = PrepaidTransactionType.Charge,
                balanceDelta = amount,
                expenseId = null,
                chargeSource = "CASH",
                reversalOfTransactionId = null,
                operationKey = operationKey,
                createdAt = 1L
            )
        )
    }

    private fun request(
        operationKey: String,
        expense: ExpenseRecord,
        prepaidAccountId: String? = null,
        evidence: EvidenceRecord? = null
    ) = SavedExpenseEditRequest(
        operationKey = operationKey,
        expense = expense,
        prepaidAccountId = prepaidAccountId,
        requestedAt = 10L,
        newEvidence = evidence,
        newEvidenceLink = evidence?.let {
            ExpenseEvidenceLinkRecord(expense.id, it.id, 10L)
        }
    )

    private fun expense(id: String, amount: Long, paymentMethod: String) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-28",
        category = ExpenseCategory.OtherExpense,
        supplierName = "test supplier",
        amount = amount,
        paymentMethod = paymentMethod,
        memo = "test memo",
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun evidence(id: String, sha256: String) = EvidenceRecord(
        id = id,
        captureId = id,
        storedUri = "file:/$id.jpg",
        byteSize = 100L,
        sha256 = sha256,
        state = EvidenceRecordState.Pending,
        createdAt = 1L,
        storedAt = null,
        updatedAt = 1L
    )

    private suspend fun transactionsFor(expenseId: String): List<PrepaidTransactionRecord> =
        database.prepaidTransactionDao().observeByExpense(expenseId).first()

    private suspend fun failureOf(block: suspend () -> Unit): SavedExpenseEditFailure? =
        (runCatching { block() }.exceptionOrNull() as? SavedExpenseEditException)?.failure
}
