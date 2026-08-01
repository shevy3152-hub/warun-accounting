package com.warun.accounting.domain.metrics

/**
 * Result of converting the persisted ExpenseRecord category value into the metrics domain value.
 *
 * The input is deliberately a non-null, unnormalized String. Persisted values are matched
 * exactly; callers must not trim, case-fold, or otherwise reinterpret them before mapping.
 */
sealed interface MetricExpenseCategoryMappingResult {
    data class Success(
        val category: MetricExpenseCategory,
    ) : MetricExpenseCategoryMappingResult

    data class Failure(
        val reason: MetricExpenseCategoryMappingFailureReason,
        val originalCategory: String,
        val expenseId: String?,
    ) : MetricExpenseCategoryMappingResult
}

enum class MetricExpenseCategoryMappingFailureReason {
    UNKNOWN_EXPENSE_CATEGORY,
}

object MetricExpenseCategoryMapper {
    /**
     * Maps only the currently confirmed persisted category values.
     *
     * `expenseId` is optional context retained only when a caller already has it; this mapper
     * does not depend on or expose the Room ExpenseRecord type.
     */
    fun map(
        persistedCategory: String,
        expenseId: String? = null,
    ): MetricExpenseCategoryMappingResult = when (persistedCategory) {
        "food_purchase" -> MetricExpenseCategoryMappingResult.Success(
            MetricExpenseCategory.FOOD_PURCHASE,
        )
        "alcohol_purchase" -> MetricExpenseCategoryMappingResult.Success(
            MetricExpenseCategory.ALCOHOL_PURCHASE,
        )
        "consumables" -> MetricExpenseCategoryMappingResult.Success(
            MetricExpenseCategory.CONSUMABLES,
        )
        "other_expense" -> MetricExpenseCategoryMappingResult.Success(
            MetricExpenseCategory.OTHER_EXPENSE,
        )
        "vehicle_transport" -> MetricExpenseCategoryMappingResult.Success(
            MetricExpenseCategory.VEHICLE_TRANSPORT,
        )
        else -> MetricExpenseCategoryMappingResult.Failure(
            reason = MetricExpenseCategoryMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY,
            originalCategory = persistedCategory,
            expenseId = expenseId,
        )
    }
}
