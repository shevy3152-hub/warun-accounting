package com.warun.accounting.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.OfflineAccountingRepository
import com.warun.accounting.util.PaymentMethodPrepaid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseCancellationVisibilityTest {
    private lateinit var database: WarunDatabase
    private lateinit var repository: OfflineAccountingRepository
    private var sequence = 0

    @Before
    fun createDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, WarunDatabase::class.java).build()
        repository = OfflineAccountingRepository(
            dao = database.warunDao(),
            expensePrepaidLinkDao = database.expensePrepaidLinkDao()
        )
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
        database.prepaidTransactionDao().insert(
            PrepaidTransactionRecord(
                id = "initial-charge",
                accountId = PrepaidAccountId.Majica,
                transactionDate = TestDate,
                transactionType = PrepaidTransactionType.Charge,
                balanceDelta = 1_000L,
                expenseId = null,
                chargeSource = PrepaidChargeSource.Cash,
                reversalOfTransactionId = null,
                operationKey = "initial-charge-operation",
                memo = "",
                createdAt = 1L
            )
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun cancellationReemitsActiveFlowWhileAuditAndRecoveryKeepImmutableRecords() = runBlocking {
        val fixture = insertFixture("visibility", amount = 400L, withEvidence = true)
        val expenseBefore = requireNotNull(database.warunDao().getExpenseRecord(fixture.expense.id))
        val purchaseBefore = requireNotNull(
            database.prepaidTransactionDao().getById(fixture.purchase.id)
        )
        val linkBefore = requireNotNull(
            database.expensePrepaidLinkDao().getByExpenseId(fixture.expense.id)
        )
        val evidenceBefore = requireNotNull(
            database.warunDao().getEvidenceRecord(fixture.evidenceId!!)
        )

        val firstEmission = CompletableDeferred<List<ExpenseRecord>>()
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                repository.observeExpenseRecords()
                    .onEach { expenses ->
                        if (!firstEmission.isCompleted) {
                            firstEmission.complete(expenses)
                        }
                    }
                    .take(2)
                    .toList()
            }
        }

        assertEquals(
            listOf(fixture.expense),
            withTimeout(5_000L) { firstEmission.await() }
        )
        cancel(fixture)

        val activeValues = emissions.await()
        assertEquals(listOf(fixture.expense), activeValues.first())
        assertEquals(emptyList<ExpenseRecord>(), activeValues.last())
        assertNull(repository.getActiveExpenseRecord(fixture.expense.id))
        assertEquals(fixture.expense, repository.getExpenseRecordForAudit(fixture.expense.id))
        assertEquals(
            listOf(fixture.expense),
            repository.observeCancelledExpenseRecordsForAuditByDate(TestDate).first()
        )
        assertEquals(
            listOf(fixture.expense),
            repository.getAllExpenseRecordsForEvidenceRecovery()
        )

        assertEquals(expenseBefore, database.warunDao().getExpenseRecord(fixture.expense.id))
        assertEquals(
            purchaseBefore,
            database.prepaidTransactionDao().getById(fixture.purchase.id)
        )
        assertEquals(
            linkBefore,
            database.expensePrepaidLinkDao().getByExpenseId(fixture.expense.id)
        )
        assertEquals(
            evidenceBefore,
            database.warunDao().getEvidenceRecord(fixture.evidenceId)
        )
        assertEquals(
            listOf(fixture.evidenceId),
            database.warunDao().observeStoredExpenseEvidence().first().map { it.evidenceId }
        )
        assertEquals(1_000L, database.prepaidTransactionDao().getBalance(PrepaidAccountId.Majica))
    }

    @Test
    fun dateCategoryAndTotalQueriesExcludeCancelledExpensesAndKeepZeroContract() = runBlocking {
        val first = insertFixture("first", amount = 400L)
        val second = insertFixture("second", amount = 600L)

        assertEquals(
            listOf(second.expense, first.expense),
            repository.observeExpenseRecordsByDateAndCategory(TestDate, TestCategory).first()
        )
        assertEquals(
            1_000L,
            repository.observeExpenseTotalByDateAndCategory(TestDate, TestCategory).first()
        )

        cancel(first)

        assertEquals(
            listOf(second.expense),
            repository.observeExpenseRecordsByDateAndCategory(TestDate, TestCategory).first()
        )
        assertEquals(
            600L,
            repository.observeExpenseTotalByDateAndCategory(TestDate, TestCategory).first()
        )

        cancel(second)

        assertEquals(
            emptyList<ExpenseRecord>(),
            repository.observeExpenseRecordsByDateAndCategory(TestDate, TestCategory).first()
        )
        assertEquals(
            0L,
            repository.observeExpenseTotalByDateAndCategory(TestDate, TestCategory).first()
        )
        assertEquals(
            setOf(first.expense.id, second.expense.id),
            repository.getAllExpenseRecordsForEvidenceRecovery().map { it.id }.toSet()
        )
        assertEquals(1_000L, database.prepaidTransactionDao().getBalance(PrepaidAccountId.Majica))
    }

    private suspend fun insertFixture(
        suffix: String,
        amount: Long,
        withEvidence: Boolean = false
    ): Fixture {
        val createdAt = 10L + sequence++
        val expense = ExpenseRecord(
            id = "expense-$suffix",
            expenseDate = TestDate,
            category = TestCategory,
            supplierName = "supplier-$suffix",
            amount = amount,
            paymentMethod = PaymentMethodPrepaid,
            memo = "memo-$suffix",
            receiptId = null,
            sourceType = ExpenseSourceType.Manual,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        database.warunDao().insertExpenseRecord(expense)
        val purchase = PrepaidTransactionRecord(
            id = "purchase-$suffix",
            accountId = PrepaidAccountId.Majica,
            transactionDate = TestDate,
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = -amount,
            expenseId = expense.id,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = "purchase-operation-$suffix",
            memo = expense.memo.orEmpty(),
            createdAt = createdAt
        )
        database.prepaidTransactionDao().insert(purchase)
        database.expensePrepaidLinkDao().insert(
            ExpensePrepaidLinkRecord(
                expenseId = expense.id,
                purchaseTransactionId = purchase.id,
                linkedAt = createdAt,
                updatedAt = createdAt
            )
        )
        val evidenceId = if (withEvidence) {
            val evidence = EvidenceRecord(
                id = "evidence-$suffix",
                captureId = "capture-$suffix",
                storedUri = "file:/stored-$suffix.jpg",
                byteSize = 123L,
                sha256 = "a".repeat(64),
                state = EvidenceRecordState.Stored,
                createdAt = createdAt,
                storedAt = createdAt,
                updatedAt = createdAt
            )
            database.warunDao().addEvidenceToExpense(
                expenseId = expense.id,
                evidence = evidence,
                link = ExpenseEvidenceLinkRecord(
                    expenseId = expense.id,
                    evidenceId = evidence.id,
                    linkedAt = createdAt
                )
            )
            evidence.id
        } else {
            null
        }
        return Fixture(expense, purchase, evidenceId)
    }

    private suspend fun cancel(fixture: Fixture) {
        val index = ++sequence
        val reversal = PrepaidTransactionRecord(
            id = "reversal-${fixture.expense.id}",
            accountId = fixture.purchase.accountId,
            transactionDate = TestDate,
            transactionType = PrepaidTransactionType.Reversal,
            balanceDelta = fixture.expense.amount,
            expenseId = fixture.expense.id,
            chargeSource = null,
            reversalOfTransactionId = fixture.purchase.id,
            operationKey = "reversal-operation-${fixture.expense.id}",
            memo = "",
            createdAt = 100L + index
        )
        database.prepaidTransactionDao().insert(reversal)
        database.expenseCancellationDao().insert(
            ExpenseCancellationRecord(
                expenseId = fixture.expense.id,
                operationKey = "expense-cancel:00000000-0000-0000-0000-" +
                    index.toString().padStart(12, '0'),
                requestFingerprint = index.toString().padStart(64, '0'),
                originalPurchaseTransactionId = fixture.purchase.id,
                reversalTransactionId = reversal.id,
                cancellationDate = TestDate,
                cancelledAt = 100L + index,
                reason = null
            )
        )
    }

    private data class Fixture(
        val expense: ExpenseRecord,
        val purchase: PrepaidTransactionRecord,
        val evidenceId: String?
    )

    private companion object {
        const val TestDate = "2026-07-29"
        const val TestCategory = ExpenseCategory.OtherExpense
    }
}
