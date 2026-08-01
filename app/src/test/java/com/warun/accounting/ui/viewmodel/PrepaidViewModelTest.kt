package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.PrepaidAccountBalance
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.prepaid.PrepaidAdjustmentInput
import com.warun.accounting.data.prepaid.PrepaidAccountCreateInput
import com.warun.accounting.data.prepaid.PrepaidChargeInput
import com.warun.accounting.data.prepaid.PrepaidRepository
import com.warun.accounting.data.prepaid.PrepaidReversalInput
import com.warun.accounting.data.prepaid.PrepaidWriteResult
import com.warun.accounting.data.prepaid.PrepaidExpensePurchaseInput
import com.warun.accounting.data.prepaid.PrepaidExpenseWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrepaidViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun inputAndOperationKeySurviveSavedStateRecreation() {
        val handle = SavedStateHandle()
        val first = PrepaidViewModel(FakePrepaidRepository(), handle)
        first.selectAccount(PrepaidAccountId.AuPayPrepaid)
        first.showCharge()
        first.setDate("2026-07-27")
        first.setAmount("5,000")
        first.setChargeSource(PrepaidChargeSource.CreditCard)
        first.setMemo("TEST_C2_AUPAY_CREDIT")
        val operationKey = first.form.value.operationKey

        val restored = PrepaidViewModel(FakePrepaidRepository(), handle)

        assertEquals(PrepaidAccountId.AuPayPrepaid, restored.form.value.accountId)
        assertEquals("5,000", restored.form.value.amount)
        assertEquals(PrepaidChargeSource.CreditCard, restored.form.value.chargeSource)
        assertEquals("TEST_C2_AUPAY_CREDIT", restored.form.value.memo)
        assertEquals(operationKey, restored.form.value.operationKey)
        assertTrue(restored.form.value.hasUnsavedChanges)
    }

    @Test
    fun doubleTapWritesOnceAndSuccessResetsInputAndOperationKey() = runTest(dispatcher) {
        val repository = FakePrepaidRepository()
        val viewModel = PrepaidViewModel(repository, SavedStateHandle())
        viewModel.showCharge()
        viewModel.setAmount("１０，０００円")
        viewModel.setMemo("TEST_C2_MAJICA_CASH")
        val operationKey = viewModel.form.value.operationKey

        viewModel.saveCharge()
        viewModel.saveCharge()
        advanceUntilIdle()

        assertEquals(1, repository.chargeInputs.size)
        assertEquals(10_000L, repository.chargeInputs.single().amount)
        assertEquals(operationKey, repository.chargeInputs.single().operationKey)
        assertEquals("", viewModel.form.value.amount)
        assertEquals(PrepaidInputMode.History, viewModel.form.value.inputMode)
        assertNotEquals(operationKey, viewModel.form.value.operationKey)
        assertFalse(viewModel.form.value.hasUnsavedChanges)
    }

    @Test
    fun navigationCancellationKeepsFormUntilExplicitDiscard() {
        val viewModel = PrepaidViewModel(FakePrepaidRepository(), SavedStateHandle())
        viewModel.showAdjustment()
        viewModel.setAmount("500")
        viewModel.setMemo("初期残高")
        val operationKey = viewModel.form.value.operationKey

        assertTrue(viewModel.form.value.hasUnsavedChanges)
        assertEquals("500", viewModel.form.value.amount)
        assertEquals(operationKey, viewModel.form.value.operationKey)

        viewModel.discardInput()

        assertFalse(viewModel.form.value.hasUnsavedChanges)
        assertEquals("", viewModel.form.value.amount)
        assertNotEquals(operationKey, viewModel.form.value.operationKey)
    }

    @Test
    fun negativeAndOverflowAmountsAreRejectedBeforeRepository() = runTest(dispatcher) {
        val repository = FakePrepaidRepository()
        val viewModel = PrepaidViewModel(repository, SavedStateHandle())
        viewModel.showCharge()

        viewModel.setAmount("-100")
        viewModel.saveCharge()
        advanceUntilIdle()
        viewModel.setAmount("999999999999999999999999")
        viewModel.saveCharge()
        advanceUntilIdle()

        assertTrue(repository.chargeInputs.isEmpty())
        assertTrue(viewModel.feedback.value?.isError == true)
    }

    @Test
    fun accountCreationInputSurvivesSavedStateRecreation() {
        val handle = SavedStateHandle()
        val first = PrepaidViewModel(FakePrepaidRepository(), handle)

        first.beginAccountCreation()
        first.setNewAccountName("交通系 IC")

        val restored = PrepaidViewModel(FakePrepaidRepository(), handle)
        assertTrue(restored.form.value.isAccountCreationOpen)
        assertEquals("交通系 IC", restored.form.value.newAccountName)
        assertTrue(restored.form.value.hasUnsavedChanges)
    }

    @Test
    fun doubleTapCreatesOneZeroBalanceAccountAndSelectsIt() = runTest(dispatcher) {
        val repository = FakePrepaidRepository()
        val viewModel = PrepaidViewModel(repository, SavedStateHandle())
        viewModel.beginAccountCreation()
        viewModel.setNewAccountName("  Ｔｅｓｔ　Ｐａｙ  ")

        viewModel.saveAccount()
        viewModel.saveAccount()
        advanceUntilIdle()

        assertEquals(1, repository.accountInputs.size)
        assertEquals("  Ｔｅｓｔ　Ｐａｙ  ", repository.accountInputs.single().name)
        assertEquals("Test Pay", repository.createdAccount?.name)
        assertEquals(repository.createdAccount?.id, viewModel.form.value.accountId)
        assertFalse(viewModel.form.value.isAccountCreationOpen)
        assertFalse(viewModel.form.value.hasUnsavedChanges)
        assertTrue(viewModel.feedback.value?.message?.contains("初期残高0円") == true)
    }
}

private class FakePrepaidRepository : PrepaidRepository {
    val chargeInputs = mutableListOf<PrepaidChargeInput>()
    val accountInputs = mutableListOf<PrepaidAccountCreateInput>()
    var createdAccount: PrepaidAccountRecord? = null
    private val accounts = mutableListOf(
        PrepaidAccountRecord(
            PrepaidAccountId.Majica,
            PrepaidAccountType.Majica,
            "majica",
            true,
            0,
            0
        ),
        PrepaidAccountRecord(
            PrepaidAccountId.AuPayPrepaid,
            PrepaidAccountType.AuPayPrepaid,
            "au PAY プリペイド",
            true,
            0,
            0
        )
    )
    private val accountsFlow = MutableStateFlow(accounts.toList())
    private val transactionFlow = MutableStateFlow<List<PrepaidTransactionRecord>>(emptyList())

    override fun observeActiveAccounts(): Flow<List<PrepaidAccountRecord>> =
        accountsFlow
    override fun observeAllAccounts(): Flow<List<PrepaidAccountRecord>> =
        accountsFlow
    override fun observeTransactions(accountId: String): Flow<List<PrepaidTransactionRecord>> =
        transactionFlow
    override fun observeTransactionsBetween(
        accountId: String,
        from: String,
        to: String
    ): Flow<List<PrepaidTransactionRecord>> = transactionFlow
    override fun observeTransactionsByExpense(
        expenseId: String
    ): Flow<List<PrepaidTransactionRecord>> = transactionFlow
    override fun observeAllAccountBalances(): Flow<List<PrepaidAccountBalance>> =
        MutableStateFlow(
            accounts.map { PrepaidAccountBalance(it.id, 0) }
        )
    override fun observeAllTransactions(): Flow<List<PrepaidTransactionRecord>> = transactionFlow
    override fun observeAllExpenseLinks(): Flow<List<ExpensePrepaidLinkRecord>> =
        MutableStateFlow(emptyList())
    override suspend fun getAccount(accountId: String): PrepaidAccountRecord? =
        accounts.firstOrNull { it.id == accountId }
    override suspend fun getExpenseLink(expenseId: String): ExpensePrepaidLinkRecord? = null
    override suspend fun getMajicaAccount(): PrepaidAccountRecord =
        accounts.first { it.id == PrepaidAccountId.Majica }
    override suspend fun getAuPayPrepaidAccount(): PrepaidAccountRecord =
        accounts.first { it.id == PrepaidAccountId.AuPayPrepaid }
    override suspend fun getBalance(accountId: String): Long = 0
    override suspend fun createAccount(input: PrepaidAccountCreateInput): PrepaidAccountRecord {
        accountInputs += input
        return PrepaidAccountRecord(
            id = "prepaid-user-test",
            type = PrepaidAccountType.userDefined("test"),
            name = "Test Pay",
            isActive = true,
            createdAt = 1L,
            updatedAt = 1L
        ).also {
            createdAccount = it
            accounts += it
            accountsFlow.value = accounts.toList()
        }
    }
    override suspend fun validateTransactionForInsert(transaction: PrepaidTransactionRecord) = Unit
    override suspend fun validateLinkForInsert(
        link: ExpensePrepaidLinkRecord,
        expectedAccountId: String
    ) = Unit
    override suspend fun createCharge(input: PrepaidChargeInput): PrepaidWriteResult {
        chargeInputs += input
        val transaction = transaction(
            type = PrepaidTransactionType.Charge,
            delta = input.amount,
            operationKey = input.operationKey,
            source = input.chargeSource
        )
        return PrepaidWriteResult(transaction, input.amount, false)
    }
    override suspend fun createAdjustment(input: PrepaidAdjustmentInput): PrepaidWriteResult =
        PrepaidWriteResult(
            transaction(
                PrepaidTransactionType.Adjustment,
                input.balanceDelta,
                input.operationKey
            ),
            input.balanceDelta,
            false
        )
    override suspend fun reverseTransaction(input: PrepaidReversalInput): PrepaidWriteResult =
        PrepaidWriteResult(
            transaction(PrepaidTransactionType.Reversal, -1, input.operationKey),
            0,
            false
        )
    override suspend fun savePurchaseExpense(
        input: PrepaidExpensePurchaseInput
    ): PrepaidExpenseWriteResult = error("Not used by PrepaidViewModel")

    private fun transaction(
        type: String,
        delta: Long,
        operationKey: String,
        source: String? = null
    ) = PrepaidTransactionRecord(
        id = "transaction-$operationKey",
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-27",
        transactionType = type,
        balanceDelta = delta,
        expenseId = null,
        chargeSource = source,
        reversalOfTransactionId = null,
        operationKey = operationKey,
        memo = "",
        createdAt = 1
    )
}
