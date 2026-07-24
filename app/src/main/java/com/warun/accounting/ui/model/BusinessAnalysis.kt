package com.warun.accounting.ui.model

import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import java.math.BigDecimal
import java.math.RoundingMode

internal enum class BusinessAnalysisCalculationStatus {
    Available,
    NoSales,
    NonPositiveContributionMargin,
    InsufficientData,
    AmountOverflow
}

internal data class BusinessAnalysisSummary(
    val salesTotal: Long,
    val estimatedCost: Long?,
    val estimatedCostRate: BigDecimal?,
    val estimatedGrossProfit: Long?,
    val estimatedGrossMargin: BigDecimal?,
    val simpleFixedCost: Long?,
    val estimatedBreakEvenSales: Long?,
    val breakEvenGap: Long?,
    val calculationStatus: BusinessAnalysisCalculationStatus
)

internal fun buildBusinessAnalysisSummary(
    salesTotal: Long,
    expenseTotal: Long,
    periodExpenses: List<ExpenseRecord>
): BusinessAnalysisSummary {
    if (salesTotal < 0L || expenseTotal < 0L || periodExpenses.any { it.amount < 0L }) {
        return unavailableBusinessAnalysis(
            salesTotal = salesTotal.coerceAtLeast(0L),
            status = BusinessAnalysisCalculationStatus.InsufficientData
        )
    }

    val estimatedCost = checkedSum(
        periodExpenses
            .distinctBy(ExpenseRecord::id)
            .filter {
                it.category == ExpenseCategory.FoodPurchase ||
                    it.category == ExpenseCategory.AlcoholPurchase
            }
            .map(ExpenseRecord::amount)
    )
    if (estimatedCost == null) {
        return unavailableBusinessAnalysis(
            salesTotal = salesTotal,
            status = BusinessAnalysisCalculationStatus.AmountOverflow
        )
    }

    val rawEstimatedGrossProfit = checkedSubtract(salesTotal, estimatedCost)
    val rawSimpleFixedCost = checkedSubtract(expenseTotal, estimatedCost)
    if (rawEstimatedGrossProfit == null || rawSimpleFixedCost == null) {
        return BusinessAnalysisSummary(
            salesTotal = salesTotal,
            estimatedCost = estimatedCost,
            estimatedCostRate = ratePercentOrNull(estimatedCost, salesTotal),
            estimatedGrossProfit = rawEstimatedGrossProfit?.takeIf { it >= 0L },
            estimatedGrossMargin = rawEstimatedGrossProfit?.takeIf { it >= 0L }?.let {
                ratePercentOrNull(it, salesTotal)
            },
            simpleFixedCost = rawSimpleFixedCost?.takeIf { it >= 0L },
            estimatedBreakEvenSales = null,
            breakEvenGap = null,
            calculationStatus = BusinessAnalysisCalculationStatus.AmountOverflow
        )
    }

    val estimatedGrossProfit = rawEstimatedGrossProfit.takeIf { it >= 0L }
    val simpleFixedCost = rawSimpleFixedCost.takeIf { it >= 0L }
    val estimatedCostRate = ratePercentOrNull(estimatedCost, salesTotal)
    val estimatedGrossMargin = estimatedGrossProfit?.let {
        ratePercentOrNull(it, salesTotal)
    }
    val unavailableStatus = when {
        salesTotal <= 0L -> BusinessAnalysisCalculationStatus.NoSales
        simpleFixedCost == null ->
            BusinessAnalysisCalculationStatus.InsufficientData
        estimatedGrossProfit == null || estimatedGrossProfit <= 0L ->
            BusinessAnalysisCalculationStatus.NonPositiveContributionMargin
        else -> null
    }
    if (unavailableStatus != null) {
        return BusinessAnalysisSummary(
            salesTotal = salesTotal,
            estimatedCost = estimatedCost,
            estimatedCostRate = estimatedCostRate,
            estimatedGrossProfit = estimatedGrossProfit,
            estimatedGrossMargin = estimatedGrossMargin,
            simpleFixedCost = simpleFixedCost,
            estimatedBreakEvenSales = null,
            breakEvenGap = null,
            calculationStatus = unavailableStatus
        )
    }

    val contributionMarginRate =
        BigDecimal.valueOf(requireNotNull(estimatedGrossProfit))
            .divide(BigDecimal.valueOf(salesTotal), CalculationScale, RoundingMode.HALF_UP)
    val breakEvenDecimal =
        BigDecimal.valueOf(requireNotNull(simpleFixedCost))
            .divide(contributionMarginRate, 0, RoundingMode.HALF_UP)
    val estimatedBreakEvenSales = breakEvenDecimal.toLongExactOrNull()
    val breakEvenGap = estimatedBreakEvenSales?.let { checkedSubtract(salesTotal, it) }
    val status = if (estimatedBreakEvenSales == null || breakEvenGap == null) {
        BusinessAnalysisCalculationStatus.AmountOverflow
    } else {
        BusinessAnalysisCalculationStatus.Available
    }

    return BusinessAnalysisSummary(
        salesTotal = salesTotal,
        estimatedCost = estimatedCost,
        estimatedCostRate = estimatedCostRate,
        estimatedGrossProfit = estimatedGrossProfit,
        estimatedGrossMargin = estimatedGrossMargin,
        simpleFixedCost = simpleFixedCost,
        estimatedBreakEvenSales = estimatedBreakEvenSales,
        breakEvenGap = breakEvenGap,
        calculationStatus = status
    )
}

internal fun formatBusinessRate(rate: BigDecimal?): String =
    rate
        ?.setScale(1, RoundingMode.HALF_UP)
        ?.toPlainString()
        ?.plus("%")
        ?: "—"

internal fun BusinessAnalysisSummary.breakEvenStatusMessage(): String = when {
    calculationStatus == BusinessAnalysisCalculationStatus.Available &&
        breakEvenGap != null &&
        breakEvenGap >= 0L ->
        "損益分岐点を ${breakEvenGap.toYenAmount()}上回っています"
    calculationStatus == BusinessAnalysisCalculationStatus.Available &&
        breakEvenGap != null ->
        "損益分岐点まであと ${checkedAbsolute(breakEvenGap).toYenAmount()}です"
    calculationStatus == BusinessAnalysisCalculationStatus.NoSales ->
        "売上が0円のため計算できません"
    calculationStatus == BusinessAnalysisCalculationStatus.NonPositiveContributionMargin ->
        "限界利益率相当が0以下のため計算できません"
    calculationStatus == BusinessAnalysisCalculationStatus.AmountOverflow ->
        "金額が大きすぎるため計算できません"
    else ->
        "データ不足のため計算できません"
}

private fun unavailableBusinessAnalysis(
    salesTotal: Long,
    status: BusinessAnalysisCalculationStatus
): BusinessAnalysisSummary = BusinessAnalysisSummary(
    salesTotal = salesTotal,
    estimatedCost = null,
    estimatedCostRate = null,
    estimatedGrossProfit = null,
    estimatedGrossMargin = null,
    simpleFixedCost = null,
    estimatedBreakEvenSales = null,
    breakEvenGap = null,
    calculationStatus = status
)

private fun checkedSum(values: List<Long>): Long? {
    var total = 0L
    values.forEach { value ->
        total = try {
            Math.addExact(total, value)
        } catch (_: ArithmeticException) {
            return null
        }
    }
    return total
}

private fun checkedSubtract(left: Long, right: Long): Long? =
    try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private fun ratePercentOrNull(numerator: Long, denominator: Long): BigDecimal? {
    if (denominator <= 0L) return null
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100L))
        .divide(BigDecimal.valueOf(denominator), CalculationScale, RoundingMode.HALF_UP)
}

private fun BigDecimal.toLongExactOrNull(): Long? =
    try {
        longValueExact()
    } catch (_: ArithmeticException) {
        null
    }

private fun checkedAbsolute(value: Long): Long =
    if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)

private fun Long.toYenAmount(): String =
    "%,d円".format(this)

private const val CalculationScale = 12
