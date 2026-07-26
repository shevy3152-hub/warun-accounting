package com.warun.accounting.ui.model

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodCredit
import com.warun.accounting.ui.util.todayString
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardUiStateCashTest {

    @Test
    fun mixedPaymentsKeepExpenseTotalButOnlyCashReducesClosingCash() {
        val state = DashboardUiState(
            reports = listOf(report(cashSales = 100_000)),
            expenses = listOf(
                expense("cash", 20_000, PaymentMethodCash),
                expense("credit", 30_000, PaymentMethodCredit)
            )
        )

        assertEquals(50_000, state.expenseTotal)
        assertEquals(20_000, state.cashExpenses)
        assertEquals(80_000, state.closingCash)
    }

    @Test
    fun creditOnlyDoesNotReduceClosingCash() {
        val state = DashboardUiState(
            reports = listOf(report(cashSales = 100_000)),
            expenses = listOf(expense("credit", 30_000, PaymentMethodCredit))
        )

        assertEquals(30_000, state.expenseTotal)
        assertEquals(0, state.cashExpenses)
        assertEquals(100_000, state.closingCash)
    }

    @Test
    fun changingPaymentMethodRecalculatesCashButNotExpenseTotal() {
        val report = report(cashSales = 100_000)
        val cashExpense = expense("expense", 30_000, PaymentMethodCash)
        val cashState = DashboardUiState(listOf(report), expenses = listOf(cashExpense))
        val creditState = DashboardUiState(
            listOf(report),
            expenses = listOf(cashExpense.copy(paymentMethod = PaymentMethodCredit))
        )

        assertEquals(cashState.expenseTotal, creditState.expenseTotal)
        assertEquals(30_000, cashState.cashExpenses)
        assertEquals(0, creditState.cashExpenses)
        assertEquals(70_000, cashState.closingCash)
        assertEquals(100_000, creditState.closingCash)
    }

    @Test
    fun todayCashFlowExcludesOpeningCashAndNonCashExpenses() {
        val state = DashboardUiState(
            reports = listOf(report(cashSales = 10_000).copy(reportDate = todayString())),
            expenses = listOf(
                expense("cash", 2_500, PaymentMethodCash).copy(expenseDate = todayString()),
                expense("credit", 4_000, PaymentMethodCredit).copy(expenseDate = todayString())
            )
        )

        assertEquals(7_500, state.todayCashFlow)
    }

    @Test
    fun cashChargeIsSeparatedFromExpenseAndReducesCashFlow() {
        val today = todayString()
        val state = DashboardUiState(
            reports = listOf(report(cashSales = 10_000).copy(reportDate = today)),
            expenses = listOf(
                expense("cash", 2_000, PaymentMethodCash).copy(expenseDate = today)
            ),
            prepaidTransactions = listOf(
                prepaid("cash-charge", today, 3_000, PrepaidChargeSource.Cash),
                prepaid("credit-charge", today, 5_000, PrepaidChargeSource.CreditCard)
            )
        )

        assertEquals(2_000L, state.todayCashExpenses)
        assertEquals(3_000L, state.todayCashCharges)
        assertEquals(5_000L, state.todayCashOutflow)
        assertEquals(5_000L, state.todayCashFlow)
        assertEquals(2_000L, state.todayExpensesTotal)
    }

    private fun prepaid(id: String, date: String, amount: Long, source: String) =
        PrepaidTransactionRecord(
            id = id,
            accountId = PrepaidAccountId.Majica,
            transactionDate = date,
            transactionType = PrepaidTransactionType.Charge,
            balanceDelta = amount,
            expenseId = null,
            chargeSource = source,
            reversalOfTransactionId = null,
            operationKey = "operation-$id",
            memo = "",
            createdAt = 1
        )

    private fun report(cashSales: Long) = DailyReport(
        id = "report",
        reportDate = "2026-07-20",
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = cashSales,
        cardSales = 0,
        qrSales = 0,
        accountsReceivableSales = 0,
        otherSales = 0,
        foodPurchases = 0,
        alcoholPurchases = 0,
        consumablesExpense = 0,
        utilitiesExpense = 0,
        electricityExpense = 0,
        gasExpense = 0,
        waterExpense = 0,
        communicationExpense = 0,
        rentExpense = 0,
        accountantFeeExpense = 0,
        miscellaneousExpense = 0,
        otherExpense = 0,
        openingCash = 0,
        actualClosingCash = 0,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1,
        updatedAt = 1
    )

    private fun expense(id: String, amount: Long, paymentMethod: String?) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-20",
        category = ExpenseCategory.FoodPurchase,
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
