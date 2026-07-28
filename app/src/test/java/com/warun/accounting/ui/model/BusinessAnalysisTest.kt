package com.warun.accounting.ui.model

import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.util.PaymentMethodPrepaid
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusinessAnalysisTest {
    @Test
    fun calculatesRequestedEstimatedIndicators() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 100_000,
            expenseTotal = 50_000,
            periodExpenses = listOf(
                expense("food", ExpenseCategory.FoodPurchase, 20_000),
                expense("alcohol", ExpenseCategory.AlcoholPurchase, 10_000),
                expense("other", ExpenseCategory.OtherExpense, 20_000)
            )
        )

        assertEquals(30_000L, summary.estimatedCost)
        assertEquals(0, BigDecimal("30.0").compareTo(summary.estimatedCostRate))
        assertEquals(70_000L, summary.estimatedGrossProfit)
        assertEquals(0, BigDecimal("70.0").compareTo(summary.estimatedGrossMargin))
        assertEquals(20_000L, summary.simpleFixedCost)
        assertEquals(28_571L, summary.estimatedBreakEvenSales)
        assertEquals(71_429L, summary.breakEvenGap)
        assertEquals(BusinessAnalysisCalculationStatus.Available, summary.calculationStatus)
    }

    @Test
    fun excludesNonPurchaseCategoriesAndDeduplicatesExpenseIds() {
        val food = expense("same", ExpenseCategory.FoodPurchase, 15_000)
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 100_000,
            expenseTotal = 35_000,
            periodExpenses = listOf(
                food,
                food,
                expense("other", ExpenseCategory.OtherExpense, 20_000)
            )
        )

        assertEquals(15_000L, summary.estimatedCost)
        assertEquals(20_000L, summary.simpleFixedCost)
    }

    @Test
    fun prepaidPurchaseIsCountedOnceFromExpenseRecord() {
        val prepaidFood = expense(
            "prepaid-food",
            ExpenseCategory.FoodPurchase,
            1_500
        ).copy(paymentMethod = PaymentMethodPrepaid)

        val summary = buildBusinessAnalysisSummary(
            salesTotal = 10_000,
            expenseTotal = 1_500,
            periodExpenses = listOf(prepaidFood)
        )

        assertEquals(1_500L, summary.estimatedCost)
        assertEquals(8_500L, summary.estimatedGrossProfit)
    }

    @Test
    fun zeroSalesIsReportedWithoutDivisionByZero() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 0,
            expenseTotal = 20_000,
            periodExpenses = listOf(expense("food", ExpenseCategory.FoodPurchase, 5_000))
        )

        assertNull(summary.estimatedCostRate)
        assertNull(summary.estimatedGrossMargin)
        assertNull(summary.estimatedBreakEvenSales)
        assertEquals(BusinessAnalysisCalculationStatus.NoSales, summary.calculationStatus)
    }

    @Test
    fun zeroCostAndZeroFixedCostRemainCalculable() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 100_000,
            expenseTotal = 0,
            periodExpenses = emptyList()
        )

        assertEquals(0L, summary.estimatedCost)
        assertEquals(0L, summary.simpleFixedCost)
        assertEquals(0L, summary.estimatedBreakEvenSales)
        assertEquals(100_000L, summary.breakEvenGap)
    }

    @Test
    fun costEqualToOrAboveSalesDoesNotProduceBreakEvenAmount() {
        val equal = buildBusinessAnalysisSummary(
            salesTotal = 10_000,
            expenseTotal = 10_000,
            periodExpenses = listOf(expense("equal", ExpenseCategory.FoodPurchase, 10_000))
        )
        val above = buildBusinessAnalysisSummary(
            salesTotal = 10_000,
            expenseTotal = 15_000,
            periodExpenses = listOf(expense("above", ExpenseCategory.FoodPurchase, 12_000))
        )

        assertNull(equal.estimatedBreakEvenSales)
        assertNull(above.estimatedBreakEvenSales)
        assertEquals(0L, equal.estimatedGrossProfit)
        assertNull(above.estimatedGrossProfit)
        assertNull(above.estimatedGrossMargin)
        assertEquals(BusinessAnalysisCalculationStatus.NonPositiveContributionMargin, equal.calculationStatus)
        assertEquals(BusinessAnalysisCalculationStatus.NonPositiveContributionMargin, above.calculationStatus)
    }

    @Test
    fun negativeInputAmountsAreNotExposedAsBusinessIndicators() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 100_000,
            expenseTotal = 10_000,
            periodExpenses = listOf(
                expense("invalid", ExpenseCategory.FoodPurchase, -1)
            )
        )

        assertNull(summary.estimatedCost)
        assertNull(summary.estimatedGrossProfit)
        assertNull(summary.simpleFixedCost)
        assertNull(summary.estimatedBreakEvenSales)
        assertEquals(BusinessAnalysisCalculationStatus.InsufficientData, summary.calculationStatus)
    }

    @Test
    fun percentageDisplayRoundsToOneDecimalPlace() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 3,
            expenseTotal = 1,
            periodExpenses = listOf(expense("food", ExpenseCategory.FoodPurchase, 1))
        )

        assertEquals("33.3%", formatBusinessRate(summary.estimatedCostRate))
        assertEquals("66.7%", formatBusinessRate(summary.estimatedGrossMargin))
    }

    @Test
    fun reportsHowFarSalesAreBelowBreakEven() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = 100_000,
            expenseTotal = 110_000,
            periodExpenses = listOf(
                expense("food", ExpenseCategory.FoodPurchase, 80_000),
                expense("other", ExpenseCategory.OtherExpense, 30_000)
            )
        )

        assertEquals(150_000L, summary.estimatedBreakEvenSales)
        assertEquals(-50_000L, summary.breakEvenGap)
        assertEquals("損益分岐点まであと 50,000円です", summary.breakEvenStatusMessage())
    }

    @Test
    fun overflowDoesNotCrashOrReturnMisleadingBreakEvenValue() {
        val summary = buildBusinessAnalysisSummary(
            salesTotal = Long.MAX_VALUE,
            expenseTotal = Long.MAX_VALUE,
            periodExpenses = listOf(
                expense("one", ExpenseCategory.FoodPurchase, Long.MAX_VALUE),
                expense("two", ExpenseCategory.AlcoholPurchase, 1)
            )
        )

        assertNull(summary.estimatedCost)
        assertNull(summary.estimatedBreakEvenSales)
        assertEquals(BusinessAnalysisCalculationStatus.AmountOverflow, summary.calculationStatus)
    }

    private fun expense(id: String, category: String, amount: Long) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-24",
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
