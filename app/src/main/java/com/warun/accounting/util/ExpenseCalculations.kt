package com.warun.accounting.util

import com.warun.accounting.data.local.ExpenseRecord

fun Iterable<ExpenseRecord>.expenseAmount(): Long = sumOf { it.amount }

fun Iterable<ExpenseRecord>.cashExpenseAmount(): Long =
    filter { isCashPaymentMethod(it.paymentMethod) }.sumOf { it.amount }

fun calculateCashBalance(openingCash: Long, cashSales: Long, cashExpense: Long): Long =
    openingCash + cashSales - cashExpense

fun calculateCashFlow(cashSales: Long, cashExpense: Long): Long =
    cashSales - cashExpense

data class ExpenseDateCategoryKey(
    val expenseDate: String,
    val category: String
)

enum class PreferredExpenseAmountSource {
    ACTIVE_RECORDS,
    CANCELLATION_SUPPRESSED,
    LEGACY_FALLBACK,
    NO_SOURCE,
}

data class PreferredExpenseAmountDetail(
    val amount: Long,
    val source: PreferredExpenseAmountSource,
    val sourceCount: Int,
)

/**
 * Shared active/cancelled/legacy selection rule.
 *
 * Callers retain responsibility for calculating [activeAmount], allowing existing UI aggregation
 * to preserve its current arithmetic while new domain assembly can use overflow-safe arithmetic.
 */
fun preferredExpenseAmountDetail(
    activeRecordCount: Int,
    activeAmount: Long,
    cancellationExists: Boolean,
    legacyAmount: Long,
): PreferredExpenseAmountDetail {
    require(activeRecordCount >= 0)
    return when {
        activeRecordCount > 0 -> PreferredExpenseAmountDetail(
            amount = activeAmount,
            source = PreferredExpenseAmountSource.ACTIVE_RECORDS,
            sourceCount = activeRecordCount,
        )
        cancellationExists -> PreferredExpenseAmountDetail(
            amount = 0L,
            source = PreferredExpenseAmountSource.CANCELLATION_SUPPRESSED,
            sourceCount = 0,
        )
        legacyAmount > 0L -> PreferredExpenseAmountDetail(
            amount = legacyAmount,
            source = PreferredExpenseAmountSource.LEGACY_FALLBACK,
            sourceCount = 1,
        )
        else -> PreferredExpenseAmountDetail(
            amount = legacyAmount,
            source = PreferredExpenseAmountSource.NO_SOURCE,
            sourceCount = 0,
        )
    }
}

fun Iterable<ExpenseRecord>.preferredExpenseAmount(
    reportDate: String,
    category: String,
    legacyAmount: Long,
    cancelledExpenseKeys: Set<ExpenseDateCategoryKey> = emptySet()
): Long {
    val records = filter { it.expenseDate == reportDate && it.category == category }
    return preferredExpenseAmountDetail(
        activeRecordCount = records.size,
        activeAmount = records.expenseAmount(),
        cancellationExists = ExpenseDateCategoryKey(reportDate, category) in cancelledExpenseKeys,
        legacyAmount = legacyAmount,
    ).amount
}

fun Iterable<ExpenseRecord>.preferredCashExpenseAmount(
    reportDate: String,
    category: String,
    legacyAmount: Long,
    cancelledExpenseKeys: Set<ExpenseDateCategoryKey> = emptySet()
): Long {
    val records = filter { it.expenseDate == reportDate && it.category == category }
    return preferredExpenseAmountDetail(
        activeRecordCount = records.size,
        activeAmount = records.cashExpenseAmount(),
        cancellationExists = ExpenseDateCategoryKey(reportDate, category) in cancelledExpenseKeys,
        legacyAmount = legacyAmount,
    ).amount
}
