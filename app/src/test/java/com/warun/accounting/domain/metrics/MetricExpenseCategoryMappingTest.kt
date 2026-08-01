package com.warun.accounting.domain.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricExpenseCategoryMappingTest {
    @Test
    fun mapsFoodPurchaseExactly() {
        assertSuccess("food_purchase", MetricExpenseCategory.FOOD_PURCHASE)
    }

    @Test
    fun mapsAlcoholPurchaseExactly() {
        assertSuccess("alcohol_purchase", MetricExpenseCategory.ALCOHOL_PURCHASE)
    }

    @Test
    fun mapsConsumablesExactly() {
        assertSuccess("consumables", MetricExpenseCategory.CONSUMABLES)
    }

    @Test
    fun mapsOtherExpenseExactly() {
        assertSuccess("other_expense", MetricExpenseCategory.OTHER_EXPENSE)
    }

    @Test
    fun mapsVehicleTransportExactly() {
        assertSuccess("vehicle_transport", MetricExpenseCategory.VEHICLE_TRANSPORT)
    }

    @Test
    fun unknownValueReturnsExplicitFailure() {
        val result = MetricExpenseCategoryMapper.map("legacy_food", expenseId = "expense-1")

        assertFailure(result, "legacy_food", "expense-1")
    }

    @Test
    fun emptyValueIsNotAccepted() {
        assertFailure(MetricExpenseCategoryMapper.map(""), "", null)
    }

    @Test
    fun uppercaseValueIsNotNormalized() {
        assertFailure(MetricExpenseCategoryMapper.map("FOOD_PURCHASE"), "FOOD_PURCHASE", null)
    }

    @Test
    fun surroundingWhitespaceIsNotNormalized() {
        assertFailure(
            MetricExpenseCategoryMapper.map(" food_purchase "),
            " food_purchase ",
            null,
        )
    }

    @Test
    fun unknownValueIsNotRoundedToOtherExpense() {
        val result = MetricExpenseCategoryMapper.map("other")

        assertTrue(result is MetricExpenseCategoryMappingResult.Failure)
        assertEquals(
            MetricExpenseCategoryMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY,
            (result as MetricExpenseCategoryMappingResult.Failure).reason,
        )
    }

    @Test
    fun failureRetainsOriginalValueAndExpenseId() {
        val result = MetricExpenseCategoryMapper.map(
            persistedCategory = "unknown_category",
            expenseId = "expense-42",
        )

        assertFailure(result, "unknown_category", "expense-42")
    }

    private fun assertSuccess(
        persistedCategory: String,
        expected: MetricExpenseCategory,
    ) {
        val result = MetricExpenseCategoryMapper.map(persistedCategory)

        assertEquals(
            MetricExpenseCategoryMappingResult.Success(expected),
            result,
        )
    }

    private fun assertFailure(
        result: MetricExpenseCategoryMappingResult,
        originalCategory: String,
        expenseId: String?,
    ) {
        assertEquals(
            MetricExpenseCategoryMappingResult.Failure(
                reason = MetricExpenseCategoryMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY,
                originalCategory = originalCategory,
                expenseId = expenseId,
            ),
            result,
        )
    }
}
