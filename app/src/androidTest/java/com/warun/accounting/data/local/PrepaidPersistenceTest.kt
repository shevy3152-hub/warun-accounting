package com.warun.accounting.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.prepaid.OfflinePrepaidRepository
import com.warun.accounting.data.prepaid.PrepaidAdjustmentInput
import com.warun.accounting.data.prepaid.PrepaidChargeInput
import com.warun.accounting.data.prepaid.PrepaidReversalInput
import com.warun.accounting.data.prepaid.PrepaidValidationException
import com.warun.accounting.data.prepaid.PrepaidValidationFailure
import com.warun.accounting.di.DatabaseModule
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepaidPersistenceTest {
    private lateinit var database: WarunDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            WarunDatabase::class.java,
            "prepaid-persistence-${UUID.randomUUID()}"
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
    fun freshDatabaseHasStableAccountsAndZeroBalances() = runBlocking {
        val accounts = database.prepaidAccountDao().observeAllAccounts().first()
        assertEquals(2, accounts.size)
        assertEquals(
            PrepaidAccountType.Majica,
            database.prepaidAccountDao().getById(PrepaidAccountId.Majica)?.type
        )
        assertEquals(
            PrepaidAccountType.AuPayPrepaid,
            database.prepaidAccountDao().getById(PrepaidAccountId.AuPayPrepaid)?.type
        )
        assertEquals(0L, database.prepaidTransactionDao().getBalance(PrepaidAccountId.Majica))
        assertEquals(
            listOf(0L, 0L),
            database.prepaidTransactionDao()
                .observeAllAccountBalances()
                .first()
                .map { it.balance }
        )
    }

    @Test
    fun daoCalculatesBalancesPerAccountAndPreservesImmutableHistory() = runBlocking {
        val transactionDao = database.prepaidTransactionDao()
        transactionDao.insert(
            transaction(
                id = "majica-charge",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Charge,
                delta = 10_000L,
                chargeSource = PrepaidChargeSource.Cash
            )
        )
        transactionDao.insert(
            transaction(
                id = "majica-purchase",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Purchase,
                delta = -1_846L,
                expenseId = null
            )
        )
        transactionDao.insert(
            transaction(
                id = "au-charge",
                accountId = PrepaidAccountId.AuPayPrepaid,
                type = PrepaidTransactionType.Charge,
                delta = 5_000L,
                chargeSource = PrepaidChargeSource.CreditCard
            )
        )

        assertEquals(8_154L, transactionDao.getBalance(PrepaidAccountId.Majica))
        assertEquals(5_000L, transactionDao.getBalance(PrepaidAccountId.AuPayPrepaid))
        assertEquals(
            2,
            transactionDao.observeByAccount(PrepaidAccountId.Majica).first().size
        )
    }

    @Test
    fun deletingExpenseRemovesOnlyLinkAndNullsLedgerExpenseReference() = runBlocking {
        val expense = expense("expense-1")
        database.warunDao().insertExpenseRecord(expense)
        val purchase = transaction(
            id = "purchase-1",
            accountId = PrepaidAccountId.Majica,
            type = PrepaidTransactionType.Purchase,
            delta = -100L,
            expenseId = expense.id
        )
        database.prepaidTransactionDao().insert(purchase)
        database.expensePrepaidLinkDao().insert(
            ExpensePrepaidLinkRecord(
                expenseId = expense.id,
                purchaseTransactionId = purchase.id,
                linkedAt = 2L,
                updatedAt = 2L
            )
        )

        database.warunDao().deleteExpenseRecord(expense)

        assertNull(database.expensePrepaidLinkDao().getByExpenseId(expense.id))
        val retainedTransaction = database.prepaidTransactionDao().getById(purchase.id)
        assertEquals(purchase.id, retainedTransaction?.id)
        assertNull(retainedTransaction?.expenseId)
    }

    @Test
    fun databaseRejectsDuplicateOperationsReversalsAndPurchaseLinks() = runBlocking {
        val transactionDao = database.prepaidTransactionDao()
        val charge = transaction(
            id = "charge",
            accountId = PrepaidAccountId.Majica,
            type = PrepaidTransactionType.Charge,
            delta = 1_000L,
            chargeSource = PrepaidChargeSource.Cash
        )
        transactionDao.insert(charge)
        assertFailure {
            transactionDao.insert(charge.copy(id = "duplicate-operation"))
        }

        val reversal = transaction(
            id = "reversal-1",
            accountId = PrepaidAccountId.Majica,
            type = PrepaidTransactionType.Reversal,
            delta = -1_000L,
            reversalOf = charge.id
        )
        transactionDao.insert(reversal)
        assertFailure {
            transactionDao.insert(
                reversal.copy(
                    id = "reversal-2",
                    operationKey = "operation-reversal-2"
                )
            )
        }

        val expense1 = expense("expense-1")
        val expense2 = expense("expense-2")
        database.warunDao().insertExpenseRecord(expense1)
        database.warunDao().insertExpenseRecord(expense2)
        val purchase = transaction(
            id = "purchase",
            accountId = PrepaidAccountId.Majica,
            type = PrepaidTransactionType.Purchase,
            delta = -100L,
            expenseId = expense1.id
        )
        transactionDao.insert(purchase)
        database.expensePrepaidLinkDao().insert(
            ExpensePrepaidLinkRecord(expense1.id, purchase.id, 2L, 2L)
        )
        assertFailure {
            database.expensePrepaidLinkDao().insert(
                ExpensePrepaidLinkRecord(expense2.id, purchase.id, 3L, 3L)
            )
        }
    }

    @Test
    fun repositoryValidationUsesCurrentDatabaseOwnershipAndBalance() = runBlocking {
        val repository = repository()
        assertValidationFailure(PrepaidValidationFailure.AccountNotFound) {
            repository.getBalance("missing-account")
        }

        val charge = transaction(
            id = "charge",
            accountId = PrepaidAccountId.Majica,
            type = PrepaidTransactionType.Charge,
            delta = 1_000L,
            chargeSource = PrepaidChargeSource.Cash
        )
        repository.validateTransactionForInsert(charge)
        database.prepaidTransactionDao().insert(charge)
        assertValidationFailure(PrepaidValidationFailure.DuplicateOperationKey) {
            repository.validateTransactionForInsert(
                charge.copy(id = "duplicate-operation")
            )
        }

        val expense = expense("expense-1")
        database.warunDao().insertExpenseRecord(expense)
        val purchase = transaction(
            id = "purchase",
            accountId = PrepaidAccountId.Majica,
            type = PrepaidTransactionType.Purchase,
            delta = -1_001L,
            expenseId = expense.id
        )
        assertValidationFailure(PrepaidValidationFailure.InsufficientBalance) {
            repository.validateTransactionForInsert(purchase)
        }

        val affordablePurchase = purchase.copy(
            id = "affordable-purchase",
            balanceDelta = -100L,
            operationKey = "operation-affordable-purchase"
        )
        repository.validateTransactionForInsert(affordablePurchase)
        database.prepaidTransactionDao().insert(affordablePurchase)
        val link = ExpensePrepaidLinkRecord(
            expenseId = expense.id,
            purchaseTransactionId = affordablePurchase.id,
            linkedAt = 2L,
            updatedAt = 2L
        )
        repository.validateLinkForInsert(link, PrepaidAccountId.Majica)
        assertValidationFailure(PrepaidValidationFailure.LinkAccountMismatch) {
            repository.validateLinkForInsert(link, PrepaidAccountId.AuPayPrepaid)
        }
    }

    @Test
    fun businessWritesAreTransactionalIdempotentAndKeepImmutableHistory() = runBlocking {
        val repository = repository()
        val charge = PrepaidChargeInput(
            accountId = PrepaidAccountId.Majica,
            transactionDate = "2026-07-27",
            amount = 10_000,
            chargeSource = PrepaidChargeSource.Cash,
            memo = "TEST_C2_MAJICA_CASH",
            operationKey = "charge-operation"
        )

        val first = repository.createCharge(charge)
        val retry = repository.createCharge(charge)
        assertEquals(false, first.wasAlreadyApplied)
        assertEquals(true, retry.wasAlreadyApplied)
        assertEquals(first.transaction.id, retry.transaction.id)
        assertEquals(1, database.prepaidTransactionDao().observeAll().first().size)
        assertValidationFailure(PrepaidValidationFailure.DuplicateOperationKey) {
            repository.createCharge(charge.copy(amount = 20_000))
        }

        val adjustment = repository.createAdjustment(
            PrepaidAdjustmentInput(
                accountId = PrepaidAccountId.Majica,
                transactionDate = "2026-07-27",
                balanceDelta = -300,
                memo = "TEST_C2_ADJUST_MINUS",
                operationKey = "adjust-operation"
            )
        )
        assertEquals(9_700L, adjustment.balance)

        val reversal = repository.reverseTransaction(
            PrepaidReversalInput(
                accountId = PrepaidAccountId.Majica,
                targetTransactionId = adjustment.transaction.id,
                transactionDate = "2026-07-27",
                memo = "取消",
                operationKey = "reverse-operation"
            )
        )
        assertEquals(10_000L, reversal.balance)
        assertValidationFailure(PrepaidValidationFailure.DuplicateReversal) {
            repository.reverseTransaction(
                PrepaidReversalInput(
                    accountId = PrepaidAccountId.Majica,
                    targetTransactionId = adjustment.transaction.id,
                    transactionDate = "2026-07-27",
                    memo = "再取消",
                    operationKey = "reverse-operation-2"
                )
            )
        }
    }

    @Test
    fun failedNegativeAdjustmentLeavesNoPartialTransaction() = runBlocking {
        val repository = repository()
        assertValidationFailure(PrepaidValidationFailure.InsufficientBalance) {
            repository.createAdjustment(
                PrepaidAdjustmentInput(
                    accountId = PrepaidAccountId.Majica,
                    transactionDate = "2026-07-27",
                    balanceDelta = -1,
                    memo = "残高不足",
                    operationKey = "failed-adjustment"
                )
            )
        }

        assertEquals(0, database.prepaidTransactionDao().observeAll().first().size)
    }

    private fun repository() = OfflinePrepaidRepository(
        database = database,
        accountDao = database.prepaidAccountDao(),
        transactionDao = database.prepaidTransactionDao(),
        linkDao = database.expensePrepaidLinkDao(),
        warunDao = database.warunDao()
    )

    private fun transaction(
        id: String,
        accountId: String,
        type: String,
        delta: Long,
        expenseId: String? = null,
        chargeSource: String? = null,
        reversalOf: String? = null
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = accountId,
        transactionDate = "2026-07-26",
        transactionType = type,
        balanceDelta = delta,
        expenseId = expenseId,
        chargeSource = chargeSource,
        reversalOfTransactionId = reversalOf,
        operationKey = "operation-$id",
        memo = "",
        createdAt = 1L
    )

    private fun expense(id: String) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-26",
        category = ExpenseCategory.OtherExpense,
        supplierName = "test",
        amount = 100L,
        paymentMethod = "現金",
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 1L
    )

    private suspend fun assertFailure(block: suspend () -> Unit) {
        assertTrue("Expected database constraint failure", runCatching { block() }.isFailure)
    }

    private suspend fun assertValidationFailure(
        expected: PrepaidValidationFailure,
        block: suspend () -> Unit
    ) {
        val exception = runCatching { block() }.exceptionOrNull()
            as? PrepaidValidationException
            ?: throw AssertionError("Expected PrepaidValidationException($expected)")
        assertEquals(expected, exception.failure)
    }
}
