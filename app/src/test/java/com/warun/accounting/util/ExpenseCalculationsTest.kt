package com.warun.accounting.util

import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseCategory
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
            expense("credit-purchase", 6_000, PaymentMethodCreditPurchase),
            expense("prepaid", 1_500, PaymentMethodPrepaid)
        )

        assertEquals(61_500, expenses.expenseAmount())
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

    @Test
    fun cashFlowIsCashSalesMinusCashExpenseWithoutOpeningCash() {
        assertEquals(-2_174, calculateCashFlow(0, 2_174))
        assertEquals(7_500, calculateCashFlow(10_000, 2_500))
    }

    @Test
    fun preferredAmountUsesLegacyWhenNoActiveOrCancelledExpenseExists() {
        assertEquals(
            3_000L,
            emptyList<ExpenseRecord>().preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L
            )
        )
    }

    @Test
    fun preferredAmountUsesActiveExpensesWhenNoCancellationExists() {
        val expenses = listOf(
            expense(
                id = "active-first",
                amount = 800L,
                paymentMethod = PaymentMethodCash,
                category = ExpenseCategory.Consumables
            ),
            expense(
                id = "active-second",
                amount = 1_200L,
                paymentMethod = PaymentMethodCash,
                category = ExpenseCategory.Consumables
            )
        )

        assertEquals(
            2_000L,
            expenses.preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L
            )
        )
    }

    @Test
    fun preferredAmountTreatsZeroAmountActiveRecordAsExisting() {
        val expenses = listOf(
            expense(
                id = "zero-active",
                amount = 0L,
                paymentMethod = PaymentMethodCash,
                category = ExpenseCategory.Consumables
            )
        )

        assertEquals(
            0L,
            expenses.preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L
            )
        )
    }

    @Test
    fun preferredCashAmountTreatsNonCashActiveRecordAsExisting() {
        val expenses = listOf(
            expense(
                id = "credit-active",
                amount = 2_000L,
                paymentMethod = PaymentMethodCredit,
                category = ExpenseCategory.Consumables
            )
        )

        assertEquals(
            0L,
            expenses.preferredCashExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L
            )
        )
    }

    @Test
    fun preferredAmountReturnsZeroWhenOnlyCancelledExpensesExist() {
        val cancelled = setOf(
            ExpenseDateCategoryKey("2026-07-20", ExpenseCategory.Consumables)
        )

        assertEquals(
            0L,
            emptyList<ExpenseRecord>().preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L,
                cancelled
            )
        )
    }

    @Test
    fun preferredAmountUsesOnlyActiveExpensesWhenActiveAndCancelledBothExist() {
        val expenses = listOf(
            expense(
                id = "active",
                amount = 2_000L,
                paymentMethod = PaymentMethodCash,
                category = ExpenseCategory.Consumables
            )
        )
        val cancelled = setOf(
            ExpenseDateCategoryKey("2026-07-20", ExpenseCategory.Consumables)
        )

        assertEquals(
            2_000L,
            expenses.preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L,
                cancelled
            )
        )
    }

    @Test
    fun cancellationForDifferentCategoryDoesNotSuppressLegacyFallback() {
        val cancelled = setOf(
            ExpenseDateCategoryKey("2026-07-20", ExpenseCategory.FoodPurchase)
        )

        assertEquals(
            3_000L,
            emptyList<ExpenseRecord>().preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L,
                cancelled
            )
        )
    }

    @Test
    fun cancellationForDifferentDateDoesNotSuppressLegacyFallback() {
        val cancelled = setOf(
            ExpenseDateCategoryKey("2026-07-21", ExpenseCategory.Consumables)
        )

        assertEquals(
            3_000L,
            emptyList<ExpenseRecord>().preferredExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L,
                cancelled
            )
        )
    }

    @Test
    fun multipleCancelledExpensesForSameDateAndCategoryStillResolveToZero() {
        val cancelledKeysFromMultipleRecords = listOf(
            ExpenseDateCategoryKey("2026-07-20", ExpenseCategory.Consumables),
            ExpenseDateCategoryKey("2026-07-20", ExpenseCategory.Consumables)
        ).toSet()

        assertEquals(
            0L,
            emptyList<ExpenseRecord>().preferredCashExpenseAmount(
                "2026-07-20",
                ExpenseCategory.Consumables,
                3_000L,
                cancelledKeysFromMultipleRecords
            )
        )
    }

    private fun expense(
        id: String,
        amount: Long,
        paymentMethod: String?,
        expenseDate: String = "2026-07-20",
        category: String = "food_purchase"
    ) = ExpenseRecord(
        id = id,
        expenseDate = expenseDate,
        category = category,
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
