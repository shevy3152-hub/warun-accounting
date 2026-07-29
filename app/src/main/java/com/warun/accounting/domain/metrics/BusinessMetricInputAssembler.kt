package com.warun.accounting.domain.metrics

import com.warun.accounting.util.PreferredExpenseAmountSource
import com.warun.accounting.util.preferredExpenseAmountDetail
import java.time.LocalDate

object BusinessMetricInputAssembler {
    fun assemble(snapshot: BusinessMetricSourceSnapshot): BusinessMetricInputAssemblyResult {
        val inconsistentReasons = linkedSetOf<AssemblyFailureReason>()
        if (
            snapshot.reports.groupingBy { it.reportDate }.eachCount().values.any { it > 1 }
        ) {
            inconsistentReasons += AssemblyFailureReason.DUPLICATE_DAILY_REPORT_DATE
        }
        if (snapshot.expenseVisibility.groupingBy { it.expense.id }.eachCount().values.any { it > 1 }) {
            inconsistentReasons += AssemblyFailureReason.VISIBILITY_SNAPSHOT_INCONSISTENT
        }
        if (inconsistentReasons.isNotEmpty()) {
            return BusinessMetricInputAssemblyResult.InconsistentSnapshot(inconsistentReasons)
        }

        val reports = snapshot.reports.filter { snapshot.period.contains(it.reportDate) }
        val visibility = snapshot.expenseVisibility.filter {
            snapshot.period.contains(it.expense.expenseDate)
        }
        collectInvalidValues(reports, visibility)?.let {
            return BusinessMetricInputAssemblyResult.InvalidSourceData(it)
        }

        return try {
            assembleValidated(snapshot, reports, visibility)
        } catch (_: SourceCountOverflowException) {
            BusinessMetricInputAssemblyResult.Overflow(
                setOf(AssemblyFailureReason.SOURCE_COUNT_OVERFLOW),
            )
        } catch (_: ArithmeticException) {
            BusinessMetricInputAssemblyResult.Overflow(
                setOf(AssemblyFailureReason.AMOUNT_OVERFLOW),
            )
        }
    }

    private fun assembleValidated(
        snapshot: BusinessMetricSourceSnapshot,
        reports: List<MetricDailyReportSource>,
        visibility: List<MetricExpenseVisibilitySource>,
    ): BusinessMetricInputAssemblyResult {
        val activeExpenses = visibility.filterNot { it.isCancelled }.map { it.expense }
        val cancelledKeys = visibility.filter { it.isCancelled }
            .map { ExpenseKey(it.expense.expenseDate, it.expense.category) }
            .toSet()

        var salesTotal = 0L
        reports.forEach { salesTotal = Math.addExact(salesTotal, it.salesYen) }

        var recordedExpenseTotal = activeExpenses.exactAmountSum()
        var expenseSourceCount = activeExpenses.size
        var foodTotal = activeExpenses
            .filter { it.category == MetricExpenseCategory.FOOD_PURCHASE }
            .exactAmountSum()
        var alcoholTotal = activeExpenses
            .filter { it.category == MetricExpenseCategory.ALCOHOL_PURCHASE }
            .exactAmountSum()
        var foodSourceCount = activeExpenses.count {
            it.category == MetricExpenseCategory.FOOD_PURCHASE
        }
        var alcoholSourceCount = activeExpenses.count {
            it.category == MetricExpenseCategory.ALCOHOL_PURCHASE
        }
        var legacyFallbackUsed = false

        reports.forEach { report ->
            val fallbackValues = listOf(
                MetricExpenseCategory.FOOD_PURCHASE to report.legacyFoodPurchasesYen,
                MetricExpenseCategory.ALCOHOL_PURCHASE to report.legacyAlcoholPurchasesYen,
                MetricExpenseCategory.CONSUMABLES to report.legacyConsumablesExpenseYen,
            )
            fallbackValues.forEach { (category, legacyAmount) ->
                val matching = activeExpenses.filter {
                    it.expenseDate == report.reportDate && it.category == category
                }
                val detail = preferredExpenseAmountDetail(
                    activeRecordCount = matching.size,
                    activeAmount = matching.exactAmountSum(),
                    cancellationExists = ExpenseKey(report.reportDate, category) in cancelledKeys,
                    legacyAmount = legacyAmount,
                )
                if (
                    detail.source == PreferredExpenseAmountSource.LEGACY_FALLBACK &&
                    legacyAmount > 0L
                ) {
                    recordedExpenseTotal = Math.addExact(recordedExpenseTotal, detail.amount)
                    expenseSourceCount = addSourceCount(expenseSourceCount, detail.sourceCount)
                    legacyFallbackUsed = true
                    when (category) {
                        MetricExpenseCategory.FOOD_PURCHASE -> {
                            foodTotal = Math.addExact(foodTotal, detail.amount)
                            foodSourceCount = addSourceCount(
                                foodSourceCount,
                                detail.sourceCount,
                            )
                        }
                        MetricExpenseCategory.ALCOHOL_PURCHASE -> {
                            alcoholTotal = Math.addExact(alcoholTotal, detail.amount)
                            alcoholSourceCount = addSourceCount(
                                alcoholSourceCount,
                                detail.sourceCount,
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }

        val fixedCosts = assembleFixedCosts(reports)
        recordedExpenseTotal = Math.addExact(recordedExpenseTotal, fixedCosts.total)
        var directExpenseSourceCount = fixedCosts.sourceCount

        reports.forEach { report ->
            if (report.miscellaneousExpenseYen > 0L) {
                recordedExpenseTotal = Math.addExact(
                    recordedExpenseTotal,
                    report.miscellaneousExpenseYen,
                )
                directExpenseSourceCount = addSourceCount(directExpenseSourceCount, 1)
            }
        }

        var customerTotal = 0L
        var customerSourceCount = 0
        reports.forEach { report ->
            if (report.customerCount > 0L) {
                customerTotal = Math.addExact(customerTotal, report.customerCount)
                customerSourceCount = addSourceCount(customerSourceCount, 1)
            }
        }

        val recordedExpenseSourceCount = addSourceCount(
            expenseSourceCount,
            directExpenseSourceCount,
        )
        val sourceSummary = MetricSourceSummary(
            salesSourceCount = reports.size,
            expenseSourceCount = expenseSourceCount,
            foodPurchaseSourceCount = foodSourceCount,
            alcoholPurchaseSourceCount = alcoholSourceCount,
            fixedCostSourceCount = fixedCosts.sourceCount,
            customerCountSourceCount = customerSourceCount,
            legacyFallbackUsed = legacyFallbackUsed,
            cancellationsExcluded = true,
            fixedCostSourcePartial = reports.any { it.hasUnknownFixedCostSource() },
            customerCountZeroOrUnknown = reports.any { it.customerCount == 0L },
            directExpenseSourceCount = directExpenseSourceCount,
        )
        return BusinessMetricInputAssemblyResult.Success(
            BusinessMetricInput(
                period = snapshot.period,
                periodState = periodState(snapshot),
                recordedSalesYen = salesTotal.takeIf { reports.isNotEmpty() },
                recordedExpensesYen = recordedExpenseTotal.takeIf {
                    recordedExpenseSourceCount > 0
                },
                foodPurchasesYen = foodTotal.takeIf { foodSourceCount > 0 },
                alcoholPurchasesYen = alcoholTotal.takeIf { alcoholSourceCount > 0 },
                rentYen = fixedCosts.rent,
                communicationYen = fixedCosts.communication,
                accountantFeeYen = fixedCosts.accountant,
                electricityYen = fixedCosts.electricity,
                gasYen = fixedCosts.gas,
                waterYen = fixedCosts.water,
                customerCount = customerTotal.takeIf { customerSourceCount > 0 },
                sourceSummary = sourceSummary,
                calculatedAt = snapshot.calculatedAt,
                unallocatedUtilitiesYen = fixedCosts.unallocatedUtilities,
            ),
        )
    }

    /**
     * Applies allocated-vs-legacy utility selection independently for each report date.
     * Period totals may therefore contain both allocated and unallocated amounts from different
     * dates without double counting or reclassifying the legacy amount.
     */
    private fun assembleFixedCosts(reports: List<MetricDailyReportSource>): FixedCostTotals {
        var result = FixedCostTotals()
        reports.forEach { report ->
            result = result.addBase(report)
            val hasAllocatedUtility = listOf(
                report.electricityExpenseYen,
                report.gasExpenseYen,
                report.waterExpenseYen,
            ).any { it > 0L }
            result = if (hasAllocatedUtility) {
                result.addAllocatedUtilities(report)
            } else if (report.legacyUtilitiesExpenseYen > 0L) {
                result.addUnallocatedUtilities(report.legacyUtilitiesExpenseYen)
            } else {
                result
            }
        }
        return result
    }

    private fun collectInvalidValues(
        reports: List<MetricDailyReportSource>,
        visibility: List<MetricExpenseVisibilitySource>,
    ): Set<AssemblyFailureReason>? {
        val reasons = linkedSetOf<AssemblyFailureReason>()
        if (reports.any { it.salesYen < 0L }) {
            reasons += AssemblyFailureReason.NEGATIVE_SALES
        }
        if (
            visibility.any { it.expense.amountYen < 0L } ||
            reports.any {
                it.legacyFoodPurchasesYen < 0L ||
                    it.legacyAlcoholPurchasesYen < 0L ||
                    it.legacyConsumablesExpenseYen < 0L ||
                    it.miscellaneousExpenseYen < 0L
            }
        ) {
            reasons += AssemblyFailureReason.NEGATIVE_EXPENSE
        }
        if (reports.any {
                it.rentExpenseYen < 0L ||
                    it.communicationExpenseYen < 0L ||
                    it.accountantFeeExpenseYen < 0L ||
                    it.electricityExpenseYen < 0L ||
                    it.gasExpenseYen < 0L ||
                    it.waterExpenseYen < 0L ||
                    it.legacyUtilitiesExpenseYen < 0L
            }
        ) {
            reasons += AssemblyFailureReason.NEGATIVE_FIXED_COST
        }
        if (reports.any { it.customerCount < 0L }) {
            reasons += AssemblyFailureReason.NEGATIVE_CUSTOMER_COUNT
        }
        return reasons.takeIf { it.isNotEmpty() }
    }

    private fun periodState(snapshot: BusinessMetricSourceSnapshot): MetricPeriodState {
        val isComplete = snapshot.period.endDateInclusive.isBefore(snapshot.evaluationDate)
        val containsFutureDatedData =
                snapshot.period.startDate.isAfter(snapshot.evaluationDate) ||
                snapshot.reports.any {
                    snapshot.period.contains(it.reportDate) &&
                        it.reportDate.isAfter(snapshot.evaluationDate)
                } ||
                snapshot.expenseVisibility.any {
                    snapshot.period.contains(it.expense.expenseDate) &&
                        it.expense.expenseDate.isAfter(snapshot.evaluationDate)
                }
        return MetricPeriodState(
            isComplete = isComplete,
            containsFutureDatedData = containsFutureDatedData,
        )
    }

    private fun MetricPeriod.contains(date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDateInclusive)

    private fun MetricDailyReportSource.hasUnknownFixedCostSource(): Boolean =
        rentExpenseYen == 0L ||
            communicationExpenseYen == 0L ||
            accountantFeeExpenseYen == 0L ||
            if (
                listOf(electricityExpenseYen, gasExpenseYen, waterExpenseYen).any { it > 0L }
            ) {
                listOf(electricityExpenseYen, gasExpenseYen, waterExpenseYen).any { it == 0L }
            } else {
                legacyUtilitiesExpenseYen == 0L
            }

    private fun List<MetricExpenseSource>.exactAmountSum(): Long =
        fold(0L) { total, expense -> Math.addExact(total, expense.amountYen) }

    private fun addSourceCount(current: Int, addition: Int): Int =
        try {
            Math.addExact(current, addition)
        } catch (error: ArithmeticException) {
            throw SourceCountOverflowException(error)
        }

    private data class ExpenseKey(
        val date: LocalDate,
        val category: MetricExpenseCategory,
    )

    private data class FixedCostTotals(
        val rent: Long? = null,
        val communication: Long? = null,
        val accountant: Long? = null,
        val electricity: Long? = null,
        val gas: Long? = null,
        val water: Long? = null,
        val unallocatedUtilities: Long? = null,
        val sourceCount: Int = 0,
    ) {
        val total: Long
            get() = listOf(
                rent,
                communication,
                accountant,
                electricity,
                gas,
                water,
                unallocatedUtilities,
            ).fold(0L) { sum, value -> Math.addExact(sum, value ?: 0L) }

        fun addBase(report: MetricDailyReportSource): FixedCostTotals {
            var next = this
            if (report.rentExpenseYen > 0L) {
                next = next.copy(
                    rent = addNullable(next.rent, report.rentExpenseYen),
                    sourceCount = addSourceCount(next.sourceCount, 1),
                )
            }
            if (report.communicationExpenseYen > 0L) {
                next = next.copy(
                    communication = addNullable(
                        next.communication,
                        report.communicationExpenseYen,
                    ),
                    sourceCount = addSourceCount(next.sourceCount, 1),
                )
            }
            if (report.accountantFeeExpenseYen > 0L) {
                next = next.copy(
                    accountant = addNullable(
                        next.accountant,
                        report.accountantFeeExpenseYen,
                    ),
                    sourceCount = addSourceCount(next.sourceCount, 1),
                )
            }
            return next
        }

        fun addAllocatedUtilities(report: MetricDailyReportSource): FixedCostTotals {
            var next = this
            if (report.electricityExpenseYen > 0L) {
                next = next.copy(
                    electricity = addNullable(
                        next.electricity,
                        report.electricityExpenseYen,
                    ),
                    sourceCount = addSourceCount(next.sourceCount, 1),
                )
            }
            if (report.gasExpenseYen > 0L) {
                next = next.copy(
                    gas = addNullable(next.gas, report.gasExpenseYen),
                    sourceCount = addSourceCount(next.sourceCount, 1),
                )
            }
            if (report.waterExpenseYen > 0L) {
                next = next.copy(
                    water = addNullable(next.water, report.waterExpenseYen),
                    sourceCount = addSourceCount(next.sourceCount, 1),
                )
            }
            return next
        }

        fun addUnallocatedUtilities(amount: Long): FixedCostTotals =
            copy(
                unallocatedUtilities = addNullable(unallocatedUtilities, amount),
                sourceCount = addSourceCount(sourceCount, 1),
            )

        private fun addNullable(current: Long?, amount: Long): Long =
            Math.addExact(current ?: 0L, amount)
    }

    private class SourceCountOverflowException(cause: ArithmeticException) :
        ArithmeticException(cause.message) {
        init {
            initCause(cause)
        }
    }
}
