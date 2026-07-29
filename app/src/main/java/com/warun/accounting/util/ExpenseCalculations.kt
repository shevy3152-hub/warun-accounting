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

fun Iterable<ExpenseRecord>.preferredExpenseAmount(
    reportDate: String,
    category: String,
    legacyAmount: Long,
    cancelledExpenseKeys: Set<ExpenseDateCategoryKey> = emptySet()
): Long {
    val records = filter { it.expenseDate == reportDate && it.category == category }
    return when {
        records.isNotEmpty() -> records.expenseAmount()
        ExpenseDateCategoryKey(reportDate, category) in cancelledExpenseKeys -> 0L
        else -> legacyAmount
    }
}

fun Iterable<ExpenseRecord>.preferredCashExpenseAmount(
    reportDate: String,
    category: String,
    legacyAmount: Long,
    cancelledExpenseKeys: Set<ExpenseDateCategoryKey> = emptySet()
): Long {
    val records = filter { it.expenseDate == reportDate && it.category == category }
    return when {
        records.isNotEmpty() -> records.cashExpenseAmount()
        ExpenseDateCategoryKey(reportDate, category) in cancelledExpenseKeys -> 0L
        else -> legacyAmount
    }
}
