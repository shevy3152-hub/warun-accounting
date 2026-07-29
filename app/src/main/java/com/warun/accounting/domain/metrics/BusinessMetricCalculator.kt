package com.warun.accounting.domain.metrics

import java.math.BigDecimal
import java.math.RoundingMode

object BusinessMetricCalculator {
    private const val RATIO_SCALE = 12

    fun calculate(input: BusinessMetricInput): BusinessMetricReport {
        val recordedSales = recordedAmount(
            input = input,
            type = BusinessMetricType.RECORDED_SALES,
            value = input.recordedSalesYen,
            sourceCount = input.sourceSummary.salesSourceCount,
            missingSource = MissingMetricInput.SALES_SOURCE,
            basis = MetricCalculationBasis.RECORDED_SALES_TOTAL,
        )
        val recordedExpenses = recordedAmount(
            input = input,
            type = BusinessMetricType.RECORDED_EXPENSES,
            value = input.recordedExpensesYen,
            sourceCount = input.sourceSummary.recordedExpenseSourceCount,
            missingSource = MissingMetricInput.EXPENSE_SOURCE,
            basis = MetricCalculationBasis.RECORDED_EXPENSE_TOTAL,
        )
        val referenceCost = referenceCost(input)
        val referenceCostRate = ratioFromAmounts(
            input = input,
            type = BusinessMetricType.REFERENCE_COST_RATE,
            numerator = referenceCost,
            denominator = recordedSales,
            denominatorMissing = MissingMetricInput.POSITIVE_SALES,
            basis = MetricCalculationBasis.REFERENCE_COST_DIVIDED_BY_SALES,
            warnings = referenceWarnings(),
        )
        val approximateGrossProfit = subtractAmounts(
            input = input,
            type = BusinessMetricType.APPROXIMATE_GROSS_PROFIT,
            minuend = recordedSales,
            subtrahend = referenceCost,
            basis = MetricCalculationBasis.SALES_MINUS_REFERENCE_COST,
            warnings = referenceWarnings(),
        )
        val fixedCostEquivalent = fixedCostEquivalent(input)
        val fixedCostRecoveryGap = fixedCostRecoveryGap(
            input = input,
            grossProfit = approximateGrossProfit,
            fixedCost = fixedCostEquivalent,
        )
        val fixedCostRecoveryRate = ratioFromAmounts(
            input = input,
            type = BusinessMetricType.FIXED_COST_RECOVERY_RATE,
            numerator = approximateGrossProfit,
            denominator = fixedCostEquivalent,
            denominatorMissing = MissingMetricInput.POSITIVE_FIXED_COST,
            basis = MetricCalculationBasis.GROSS_PROFIT_DIVIDED_BY_FIXED_COST,
            warnings = fixedWarnings() + referenceWarnings(),
        )
        val referenceBreakEvenSales = referenceBreakEvenSales(
            input = input,
            sales = recordedSales,
            referenceCost = referenceCost,
            fixedCost = fixedCostEquivalent,
        )
        val breakEvenRemaining = breakEvenRemaining(
            input = input,
            sales = recordedSales,
            breakEvenSales = referenceBreakEvenSales,
        )
        val averageSpendPerCustomer = averageSpendPerCustomer(input, recordedSales)

        return BusinessMetricReport(
            period = input.period,
            recordedSales = recordedSales,
            recordedExpenses = recordedExpenses,
            referenceCost = referenceCost,
            referenceCostRate = referenceCostRate,
            approximateGrossProfit = approximateGrossProfit,
            recordedFixedCostEquivalent = fixedCostEquivalent,
            fixedCostRecoveryGap = fixedCostRecoveryGap,
            fixedCostRecoveryRate = fixedCostRecoveryRate,
            referenceBreakEvenSales = referenceBreakEvenSales,
            breakEvenRemaining = breakEvenRemaining,
            averageSpendPerCustomer = averageSpendPerCustomer,
            calculatedAt = input.calculatedAt,
        )
    }

    private fun recordedAmount(
        input: BusinessMetricInput,
        type: BusinessMetricType,
        value: Long?,
        sourceCount: Int,
        missingSource: MissingMetricInput,
        basis: MetricCalculationBasis,
    ): AmountMetricResult {
        val missing = linkedSetOf<MissingMetricInput>()
        val warnings = commonWarnings(input)
        if (sourceCount == 0 || value == null) {
            missing += missingSource
        }
        if ((value != null && value < 0) || (sourceCount == 0 && value != null)) {
            missing += MissingMetricInput.NON_NEGATIVE_INPUT
        }
        val usable = missing.isEmpty()
        return amountResult(
            input = input,
            type = type,
            value = value?.takeIf { usable },
            availability = if (usable) valueAvailability(input) else MetricAvailability.INSUFFICIENT_DATA,
            basis = setOf(basis),
            missing = missing,
            warnings = warnings,
        )
    }

    private fun referenceCost(input: BusinessMetricInput): AmountMetricResult {
        val foodSources = input.sourceSummary.foodPurchaseSourceCount
        val alcoholSources = input.sourceSummary.alcoholPurchaseSourceCount
        val missing = linkedSetOf<MissingMetricInput>()
        val warnings = commonWarnings(input) + referenceWarnings()

        val food = sourceBackedCategoryAmount(input.foodPurchasesYen, foodSources, missing)
        val alcohol = sourceBackedCategoryAmount(input.alcoholPurchasesYen, alcoholSources, missing)
        if (foodSources + alcoholSources == 0) {
            missing += MissingMetricInput.REFERENCE_COST_SOURCE
        }

        val value = if (missing.isEmpty()) {
            safeAdd(listOf(food, alcohol), missing)
        } else {
            null
        }
        return amountResult(
            input = input,
            type = BusinessMetricType.REFERENCE_COST,
            value = value,
            availability = if (value != null) {
                valueAvailability(input)
            } else {
                MetricAvailability.INSUFFICIENT_DATA
            },
            basis = setOf(MetricCalculationBasis.FOOD_AND_ALCOHOL_PURCHASES),
            missing = missing,
            warnings = warnings,
        )
    }

    private fun fixedCostEquivalent(input: BusinessMetricInput): AmountMetricResult {
        val values = listOf(
            input.rentYen,
            input.communicationYen,
            input.accountantFeeYen,
            input.electricityYen,
            input.gasYen,
            input.waterYen,
            input.unallocatedUtilitiesYen,
        )
        val missing = linkedSetOf<MissingMetricInput>()
        val warnings = linkedSetOf<MetricWarning>().apply {
            addAll(commonWarnings(input))
            addAll(fixedWarnings())
        }
        if (input.sourceSummary.fixedCostSourceCount == 0) {
            missing += MissingMetricInput.FIXED_COST_SOURCE
            if (values.any { it != null }) {
                missing += MissingMetricInput.NON_NEGATIVE_INPUT
            }
        } else if (values.all { it == null }) {
            missing += MissingMetricInput.FIXED_COST_SOURCE
        }
        if (values.any { it != null && it < 0 }) {
            missing += MissingMetricInput.NON_NEGATIVE_INPUT
        }
        val missingFixedCostSource =
            listOf(input.rentYen, input.communicationYen, input.accountantFeeYen).any {
                it == null
            } ||
                (
                    input.unallocatedUtilitiesYen == null &&
                        listOf(input.electricityYen, input.gasYen, input.waterYen).any {
                            it == null
                        }
                    )
        if (input.sourceSummary.fixedCostSourceCount > 0 && missingFixedCostSource) {
            warnings += MetricWarning.SOURCE_DATA_PARTIAL
        }
        if (input.sourceSummary.fixedCostSourcePartial) {
            warnings += MetricWarning.SOURCE_DATA_PARTIAL
        }
        if (input.unallocatedUtilitiesYen != null) {
            warnings += MetricWarning.UNALLOCATED_UTILITIES_USED
        }

        val value = if (missing.isEmpty()) {
            safeAdd(values.map { it ?: 0L }, missing)
        } else {
            null
        }
        val availability = when {
            value == null -> MetricAvailability.INSUFFICIENT_DATA
            MetricWarning.SOURCE_DATA_PARTIAL in warnings -> MetricAvailability.PARTIAL_DATA
            else -> valueAvailability(input)
        }
        return amountResult(
            input = input,
            type = BusinessMetricType.RECORDED_FIXED_COST_EQUIVALENT,
            value = value,
            availability = availability,
            basis = setOf(MetricCalculationBasis.MANAGEMENT_FIXED_COST_CATEGORIES),
            missing = missing,
            warnings = warnings,
        )
    }

    private fun subtractAmounts(
        input: BusinessMetricInput,
        type: BusinessMetricType,
        minuend: AmountMetricResult,
        subtrahend: AmountMetricResult,
        basis: MetricCalculationBasis,
        warnings: Set<MetricWarning>,
    ): AmountMetricResult {
        val missing = linkedSetOf<MissingMetricInput>().apply {
            addAll(minuend.missingInputs)
            addAll(subtrahend.missingInputs)
        }
        val value = if (minuend.value != null && subtrahend.value != null && missing.isEmpty()) {
            try {
                Math.subtractExact(minuend.value.yen, subtrahend.value.yen)
            } catch (_: ArithmeticException) {
                missing += MissingMetricInput.VALUE_WITHIN_LONG_RANGE
                null
            }
        } else {
            null
        }
        return amountResult(
            input = input,
            type = type,
            value = value,
            availability = derivedAvailability(input, value, listOf(minuend, subtrahend)),
            basis = setOf(basis),
            missing = missing,
            warnings = commonWarnings(input) + warnings + minuend.warnings + subtrahend.warnings,
        )
    }

    private fun fixedCostRecoveryGap(
        input: BusinessMetricInput,
        grossProfit: AmountMetricResult,
        fixedCost: AmountMetricResult,
    ): AmountMetricResult {
        if (fixedCost.value?.yen == 0L) {
            return amountResult(
                input = input,
                type = BusinessMetricType.FIXED_COST_RECOVERY_GAP,
                value = null,
                availability = MetricAvailability.NOT_AVAILABLE,
                basis = setOf(MetricCalculationBasis.GROSS_PROFIT_MINUS_FIXED_COST),
                missing = fixedCost.missingInputs + MissingMetricInput.POSITIVE_FIXED_COST,
                warnings = commonWarnings(input) + fixedWarnings() + referenceWarnings(),
            )
        }
        return subtractAmounts(
            input = input,
            type = BusinessMetricType.FIXED_COST_RECOVERY_GAP,
            minuend = grossProfit,
            subtrahend = fixedCost,
            basis = MetricCalculationBasis.GROSS_PROFIT_MINUS_FIXED_COST,
            warnings = fixedWarnings() + referenceWarnings(),
        )
    }

    private fun ratioFromAmounts(
        input: BusinessMetricInput,
        type: BusinessMetricType,
        numerator: AmountMetricResult,
        denominator: AmountMetricResult,
        denominatorMissing: MissingMetricInput,
        basis: MetricCalculationBasis,
        warnings: Set<MetricWarning>,
    ): RatioMetricResult {
        val missing = linkedSetOf<MissingMetricInput>().apply {
            addAll(numerator.missingInputs)
            addAll(denominator.missingInputs)
        }
        val denominatorYen = denominator.value?.yen
        val value = if (numerator.value != null && denominatorYen != null && denominatorYen > 0L) {
            BigDecimal.valueOf(numerator.value.yen).divide(
                BigDecimal.valueOf(denominatorYen),
                RATIO_SCALE,
                RoundingMode.HALF_UP,
            )
        } else {
            if (denominatorYen != null && denominatorYen <= 0L) {
                missing += denominatorMissing
            }
            null
        }
        val availability = when {
            value != null -> combinedValueAvailability(input, listOf(numerator, denominator))
            denominatorYen != null && denominatorYen <= 0L -> MetricAvailability.NOT_AVAILABLE
            else -> MetricAvailability.INSUFFICIENT_DATA
        }
        return ratioResult(
            input = input,
            type = type,
            value = value,
            availability = availability,
            basis = setOf(basis),
            missing = missing,
            warnings = commonWarnings(input) + warnings + numerator.warnings + denominator.warnings,
        )
    }

    private fun referenceBreakEvenSales(
        input: BusinessMetricInput,
        sales: AmountMetricResult,
        referenceCost: AmountMetricResult,
        fixedCost: AmountMetricResult,
    ): AmountMetricResult {
        val missing = linkedSetOf<MissingMetricInput>().apply {
            addAll(sales.missingInputs)
            addAll(referenceCost.missingInputs)
            addAll(fixedCost.missingInputs)
        }
        val warnings = commonWarnings(input) + referenceWarnings() + fixedWarnings()
        val isCompletedMonth = input.period is MetricPeriod.Monthly && input.periodState.isComplete
        if (!isCompletedMonth) {
            missing += MissingMetricInput.COMPLETED_CALENDAR_MONTH
        }

        val salesYen = sales.value?.yen
        val costYen = referenceCost.value?.yen
        val fixedYen = fixedCost.value?.yen
        if (salesYen != null && salesYen <= 0L) {
            missing += MissingMetricInput.POSITIVE_SALES
        }
        if (fixedYen != null && fixedYen <= 0L) {
            missing += MissingMetricInput.POSITIVE_FIXED_COST
        }

        var invalidMargin = false
        val contributionMargin = if (salesYen != null && salesYen > 0L && costYen != null) {
            val variableCostRate = BigDecimal.valueOf(costYen).divide(
                BigDecimal.valueOf(salesYen),
                RATIO_SCALE,
                RoundingMode.HALF_UP,
            )
            BigDecimal.ONE.subtract(variableCostRate)
                .also { invalidMargin = it <= BigDecimal.ZERO }
        } else {
            null
        }
        if (invalidMargin) {
            missing += MissingMetricInput.VALID_CONTRIBUTION_MARGIN
        }

        val sourceMissing = missing.any {
            it == MissingMetricInput.SALES_SOURCE ||
                it == MissingMetricInput.REFERENCE_COST_SOURCE ||
                it == MissingMetricInput.FIXED_COST_SOURCE ||
                it == MissingMetricInput.NON_NEGATIVE_INPUT
        }
        val value = if (
            isCompletedMonth &&
            !sourceMissing &&
            salesYen != null &&
            salesYen > 0L &&
            fixedYen != null &&
            fixedYen > 0L &&
            contributionMargin != null &&
            contributionMargin > BigDecimal.ZERO
        ) {
            try {
                BigDecimal.valueOf(fixedYen)
                    .divide(contributionMargin, 0, RoundingMode.CEILING)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                missing += MissingMetricInput.VALUE_WITHIN_LONG_RANGE
                null
            }
        } else {
            null
        }
        val availability = when {
            value != null -> combinedValueAvailability(input, listOf(sales, referenceCost, fixedCost))
            !isCompletedMonth ||
                salesYen != null && salesYen <= 0L ||
                fixedYen != null && fixedYen <= 0L ||
                invalidMargin ||
                MissingMetricInput.VALUE_WITHIN_LONG_RANGE in missing ->
                MetricAvailability.NOT_AVAILABLE
            else -> MetricAvailability.INSUFFICIENT_DATA
        }
        return amountResult(
            input = input,
            type = BusinessMetricType.REFERENCE_BREAK_EVEN_SALES,
            value = value,
            availability = availability,
            basis = setOf(MetricCalculationBasis.FIXED_COST_DIVIDED_BY_CONTRIBUTION_MARGIN),
            missing = missing,
            warnings = warnings,
        )
    }

    private fun breakEvenRemaining(
        input: BusinessMetricInput,
        sales: AmountMetricResult,
        breakEvenSales: AmountMetricResult,
    ): BreakEvenRemainingMetricResult {
        val missing = linkedSetOf<MissingMetricInput>().apply {
            addAll(sales.missingInputs)
            addAll(breakEvenSales.missingInputs)
        }
        val value = if (sales.value != null && breakEvenSales.value != null) {
            try {
                val difference = Math.subtractExact(breakEvenSales.value.yen, sales.value.yen)
                if (difference > 0L) {
                    BreakEvenPosition.Remaining(difference)
                } else {
                    BreakEvenPosition.Achieved(Math.negateExact(difference))
                }
            } catch (_: ArithmeticException) {
                missing += MissingMetricInput.VALUE_WITHIN_LONG_RANGE
                null
            }
        } else {
            null
        }
        val availability = if (value != null) {
            combinedValueAvailability(input, listOf(sales, breakEvenSales))
        } else {
            breakEvenSales.availability
        }
        return BreakEvenRemainingMetricResult(
            value = value,
            period = input.period,
            availability = availability,
            calculationBasis = setOf(MetricCalculationBasis.BREAK_EVEN_SALES_MINUS_RECORDED_SALES),
            missingInputs = missing,
            warnings = commonWarnings(input) + breakEvenSales.warnings,
            sourceSummary = input.sourceSummary,
            calculatedAt = input.calculatedAt,
        )
    }

    private fun averageSpendPerCustomer(
        input: BusinessMetricInput,
        sales: AmountMetricResult,
    ): AmountMetricResult {
        val missing = linkedSetOf<MissingMetricInput>().apply { addAll(sales.missingInputs) }
        val warnings = linkedSetOf<MetricWarning>().apply { addAll(commonWarnings(input)) }
        val customerCount = input.customerCount
        if (input.sourceSummary.customerCountSourceCount == 0 || customerCount == null) {
            missing += MissingMetricInput.CUSTOMER_COUNT_SOURCE
        }
        if ((customerCount != null && customerCount < 0L) ||
            (input.sourceSummary.customerCountSourceCount == 0 && customerCount != null)
        ) {
            missing += MissingMetricInput.NON_NEGATIVE_INPUT
        }
        if (customerCount == 0L) {
            missing += MissingMetricInput.POSITIVE_CUSTOMER_COUNT
            warnings += MetricWarning.CUSTOMER_COUNT_ZERO_OR_UNKNOWN
        }
        if (input.sourceSummary.customerCountZeroOrUnknown) {
            warnings += MetricWarning.CUSTOMER_COUNT_ZERO_OR_UNKNOWN
        }
        val value = if (
            sales.value != null &&
            customerCount != null &&
            customerCount > 0L &&
            MissingMetricInput.NON_NEGATIVE_INPUT !in missing
        ) {
            sales.value.yen / customerCount
        } else {
            null
        }
        val availability = when {
            value != null -> combinedValueAvailability(input, listOf(sales))
            customerCount == 0L -> MetricAvailability.INSUFFICIENT_DATA
            else -> MetricAvailability.INSUFFICIENT_DATA
        }
        return amountResult(
            input = input,
            type = BusinessMetricType.AVERAGE_SPEND_PER_CUSTOMER,
            value = value,
            availability = availability,
            basis = setOf(MetricCalculationBasis.RECORDED_SALES_DIVIDED_BY_CUSTOMER_COUNT),
            missing = missing,
            warnings = warnings,
        )
    }

    private fun sourceBackedCategoryAmount(
        amount: Long?,
        sourceCount: Int,
        missing: MutableSet<MissingMetricInput>,
    ): Long {
        if (sourceCount == 0) {
            if (amount != null) {
                missing += MissingMetricInput.NON_NEGATIVE_INPUT
            }
            return 0L
        }
        if (amount == null) {
            missing += MissingMetricInput.REFERENCE_COST_SOURCE
            return 0L
        }
        if (amount < 0L) {
            missing += MissingMetricInput.NON_NEGATIVE_INPUT
            return 0L
        }
        return amount
    }

    private fun safeAdd(
        values: List<Long>,
        missing: MutableSet<MissingMetricInput>,
    ): Long? = try {
        values.fold(0L, Math::addExact)
    } catch (_: ArithmeticException) {
        missing += MissingMetricInput.VALUE_WITHIN_LONG_RANGE
        null
    }

    private fun amountResult(
        input: BusinessMetricInput,
        type: BusinessMetricType,
        value: Long?,
        availability: MetricAvailability,
        basis: Set<MetricCalculationBasis>,
        missing: Set<MissingMetricInput>,
        warnings: Set<MetricWarning>,
    ) = AmountMetricResult(
        type = type,
        value = value?.let(MetricValue::Amount),
        period = input.period,
        availability = availability,
        classification = type.expectedClassification,
        calculationBasis = basis,
        missingInputs = missing,
        warnings = warnings,
        sourceSummary = input.sourceSummary,
        calculatedAt = input.calculatedAt,
    )

    private fun ratioResult(
        input: BusinessMetricInput,
        type: BusinessMetricType,
        value: BigDecimal?,
        availability: MetricAvailability,
        basis: Set<MetricCalculationBasis>,
        missing: Set<MissingMetricInput>,
        warnings: Set<MetricWarning>,
    ) = RatioMetricResult(
        type = type,
        value = value?.let(MetricValue::Ratio),
        period = input.period,
        availability = availability,
        classification = type.expectedClassification,
        calculationBasis = basis,
        missingInputs = missing,
        warnings = warnings,
        sourceSummary = input.sourceSummary,
        calculatedAt = input.calculatedAt,
    )

    private fun valueAvailability(input: BusinessMetricInput): MetricAvailability =
        if (
            !input.periodState.isComplete ||
            input.periodState.containsFutureDatedData ||
            !input.sourceSummary.cancellationsExcluded
        ) {
            MetricAvailability.PARTIAL_DATA
        } else {
            MetricAvailability.AVAILABLE
        }

    private fun combinedValueAvailability(
        input: BusinessMetricInput,
        dependencies: List<BusinessMetricResult>,
    ): MetricAvailability =
        if (
            valueAvailability(input) == MetricAvailability.PARTIAL_DATA ||
            dependencies.any { it.availability == MetricAvailability.PARTIAL_DATA }
        ) {
            MetricAvailability.PARTIAL_DATA
        } else {
            MetricAvailability.AVAILABLE
        }

    private fun derivedAvailability(
        input: BusinessMetricInput,
        value: Long?,
        dependencies: List<BusinessMetricResult>,
    ): MetricAvailability = if (value == null) {
        MetricAvailability.INSUFFICIENT_DATA
    } else {
        combinedValueAvailability(input, dependencies)
    }

    private fun commonWarnings(input: BusinessMetricInput): Set<MetricWarning> =
        linkedSetOf<MetricWarning>().apply {
            if (!input.periodState.isComplete) add(MetricWarning.PARTIAL_PERIOD)
            if (input.periodState.containsFutureDatedData) {
                add(MetricWarning.FUTURE_DATED_DATA_INCLUDED)
            }
            if (input.sourceSummary.legacyFallbackUsed) add(MetricWarning.LEGACY_FALLBACK_USED)
            if (!input.sourceSummary.cancellationsExcluded) {
                add(MetricWarning.CANCELLATION_EXCLUSION_NOT_CONFIRMED)
            }
        }

    private fun referenceWarnings(): Set<MetricWarning> = setOf(
        MetricWarning.INVENTORY_NOT_INCLUDED,
        MetricWarning.UNREGISTERED_PURCHASES_NOT_INCLUDED,
        MetricWarning.CONSUMABLES_EXCLUDED_FROM_REFERENCE_COST,
    )

    private fun fixedWarnings(): Set<MetricWarning> = setOf(
        MetricWarning.UTILITIES_TREATED_AS_MANAGEMENT_FIXED_COST,
        MetricWarning.OWNER_LABOR_COST_EXCLUDED,
    )
}
