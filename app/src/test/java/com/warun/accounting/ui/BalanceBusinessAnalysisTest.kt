package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceBusinessAnalysisTest {
    @Test
    fun analysisUsesTheSameSelectedPeriodAsBalanceSummary() {
        val insideDate = "2026-07-24"
        val outsideDate = "2026-07-25"
        val summary = buildBalanceSummary(
            reports = listOf(
                report(insideDate, sales = 100_000, rent = 10_000),
                report(outsideDate, sales = 900_000, rent = 90_000)
            ),
            expenses = listOf(
                expense("inside-food", insideDate, ExpenseCategory.FoodPurchase, 20_000),
                expense("outside-alcohol", outsideDate, ExpenseCategory.AlcoholPurchase, 50_000)
            ),
            period = BalancePeriod(
                start = LocalDate.parse(insideDate),
                end = LocalDate.parse(insideDate)
            )
        )

        assertEquals(100_000L, summary.salesTotal)
        assertEquals(30_000L, summary.expenseTotal)
        assertEquals(20_000L, summary.businessAnalysis.estimatedCost)
        assertEquals(10_000L, summary.businessAnalysis.simpleFixedCost)
    }

    @Test
    fun reportlessPurchaseIsIncludedOnceInAnalysisAndExpenseTotal() {
        val date = "2026-07-24"
        val summary = buildBalanceSummary(
            reports = emptyList(),
            expenses = listOf(
                expense("food", date, ExpenseCategory.FoodPurchase, 12_000),
                expense("other", date, ExpenseCategory.OtherExpense, 3_000)
            ),
            period = BalancePeriod(LocalDate.parse(date), LocalDate.parse(date))
        )

        assertEquals(15_000L, summary.expenseTotal)
        assertEquals(12_000L, summary.businessAnalysis.estimatedCost)
        assertEquals(3_000L, summary.businessAnalysis.simpleFixedCost)
    }

    @Test
    fun legacyFoodAmountIsNotTreatedAsEstimatedCostOrDoubleCounted() {
        val date = "2026-07-24"
        val summary = buildBalanceSummary(
            reports = listOf(report(date, sales = 50_000, legacyFood = 99_000)),
            expenses = listOf(
                expense("food", date, ExpenseCategory.FoodPurchase, 10_000)
            ),
            period = BalancePeriod(LocalDate.parse(date), LocalDate.parse(date))
        )

        assertEquals(10_000L, summary.expenseTotal)
        assertEquals(10_000L, summary.businessAnalysis.estimatedCost)
    }

    @Test
    fun prepaidCashChargeChangesCashOnlyAndNotBusinessAnalysis() {
        val date = "2026-07-24"
        val summary = buildBalanceSummary(
            reports = listOf(report(date, sales = 50_000, rent = 10_000)),
            expenses = listOf(
                expense("food", date, ExpenseCategory.FoodPurchase, 20_000)
            ),
            period = BalancePeriod(LocalDate.parse(date), LocalDate.parse(date)),
            prepaidTransactions = listOf(
                PrepaidTransactionRecord(
                    id = "charge",
                    accountId = PrepaidAccountId.Majica,
                    transactionDate = date,
                    transactionType = PrepaidTransactionType.Charge,
                    balanceDelta = 5_000,
                    expenseId = null,
                    chargeSource = PrepaidChargeSource.Cash,
                    reversalOfTransactionId = null,
                    operationKey = "operation-charge",
                    memo = "",
                    createdAt = 1
                )
            )
        )

        assertEquals(30_000L, summary.expenseTotal)
        assertEquals(20_000L, summary.businessAnalysis.estimatedCost)
        assertEquals(30_000L, summary.businessAnalysis.estimatedGrossProfit)
        assertEquals(5_000L, summary.cashCharge)
        assertEquals(35_000L, summary.cashFlow)
    }

    private fun report(
        date: String,
        sales: Long,
        rent: Long = 0,
        legacyFood: Long = 0
    ) = DailyReport(
        id = "report-$date",
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = sales,
        cardSales = 0,
        qrSales = 0,
        accountsReceivableSales = 0,
        otherSales = 0,
        foodPurchases = legacyFood,
        alcoholPurchases = 0,
        consumablesExpense = 0,
        utilitiesExpense = 0,
        electricityExpense = 0,
        gasExpense = 0,
        waterExpense = 0,
        communicationExpense = 0,
        rentExpense = rent,
        accountantFeeExpense = 0,
        miscellaneousExpense = 0,
        otherExpense = 0,
        openingCash = 0,
        actualClosingCash = 0,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1,
        updatedAt = 1,
        hasActualClosingCash = false
    )

    private fun expense(
        id: String,
        date: String,
        category: String,
        amount: Long
    ) = ExpenseRecord(
        id = id,
        expenseDate = date,
        category = category,
        supplierName = null,
        amount = amount,
        paymentMethod = null,
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1,
        updatedAt = 1
    )
}
