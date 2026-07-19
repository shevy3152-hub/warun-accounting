package com.warun.accounting.util

import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseCalculationsTest {

    @Test
    fun expenseTotalIncludesEveryPaymentMethodWhileCashTotalOnlyIncludesCash() {
        val expenses = listOf(
            expense("cash", 20_000, PaymentMethodCash),
            expense("credit", 30_000, PaymentMethodCredit),
            expense("electronic", 4_000, PaymentMethodElectronicMoney),
            expense("credit-purchase", 6_000, PaymentMethodCreditPurchase)
        )

        assertEquals(60_000, expenses.expenseAmount())
        assertEquals(20_000, expenses.cashExpenseAmount())
    }

    @Test
    fun nonCashOnlyDoesNotReduceCash() {
        val expenses = listOf(expense("credit", 30_000, PaymentMethodCredit))

        assertEquals(30_000, expenses.expenseAmount())
        assertEquals(0, expenses.cashExpenseAmount())
    }

    @Test
    fun changingPaymentMethodRecalculatesCashWithoutChangingExpenseTotal() {
        val cashExpense = expense("expense", 10_000, PaymentMethodCash)
        val changedToCredit = cashExpense.copy(paymentMethod = PaymentMethodCredit)

        assertEquals(10_000, listOf(cashExpense).expenseAmount())
        assertEquals(10_000, listOf(changedToCredit).expenseAmount())
        assertEquals(10_000, listOf(cashExpense).cashExpenseAmount())
        assertEquals(0, listOf(changedToCredit).cashExpenseAmount())
        assertEquals(10_000, listOf(changedToCredit.copy(paymentMethod = PaymentMethodCash)).cashExpenseAmount())
    }

    @Test
    fun periodFilteringExcludesCashExpensesOutsidePeriod() {
        val expenses = listOf(
            expense("inside", 5_000, PaymentMethodCash, "2026-07-20"),
            expense("outside", 9_000, PaymentMethodCash, "2026-08-01")
        )
        val julyExpenses = expenses.filter { it.expenseDate.startsWith("2026-07") }

        assertEquals(5_000, julyExpenses.cashExpenseAmount())
        assertEquals(5_000, julyExpenses.expenseAmount())
    }

    @Test
    fun nullBlankAndUnknownPaymentMethodsAreNotCash() {
        val expenses = listOf(
            expense("null", 1_000, null),
            expense("blank", 2_000, ""),
            expense("unknown", 3_000, "PayPay legacy")
        )

        assertEquals(6_000, expenses.expenseAmount())
        assertEquals(0, expenses.cashExpenseAmount())
    }

    @Test
    fun cashBalanceSubtractsOnlyTheProvidedCashExpense() {
        assertEquals(80_000, calculateCashBalance(0, 100_000, 20_000))
        assertEquals(100_000, calculateCashBalance(0, 100_000, 0))
    }

    private fun expense(
        id: String,
        amount: Long,
        paymentMethod: String?,
        expenseDate: String = "2026-07-20"
    ) = ExpenseRecord(
        id = id,
        expenseDate = expenseDate,
        category = "food_purchase",
        supplierName = null,
        amount = amount,
        paymentMethod = paymentMethod,
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1,
        updatedAt = 1
    )
}
