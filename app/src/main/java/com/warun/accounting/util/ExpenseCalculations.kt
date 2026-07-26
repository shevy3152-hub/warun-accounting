package com.warun.accounting.util

import com.warun.accounting.data.local.ExpenseRecord

fun Iterable<ExpenseRecord>.expenseAmount(): Long = sumOf { it.amount }

fun Iterable<ExpenseRecord>.cashExpenseAmount(): Long =
    filter { it.paymentMethod == PaymentMethodCash }.sumOf { it.amount }

fun calculateCashBalance(openingCash: Long, cashSales: Long, cashExpense: Long): Long =
    openingCash + cashSales - cashExpense

fun calculateCashFlow(cashSales: Long, cashExpense: Long): Long =
    cashSales - cashExpense

fun Iterable<ExpenseRecord>.preferredExpenseAmount(
    reportDate: String,
    category: String,
    legacyAmount: Long
): Long {
    val records = filter { it.expenseDate == reportDate && it.category == category }
    return if (records.isNotEmpty()) records.expenseAmount() else legacyAmount
}

fun Iterable<ExpenseRecord>.preferredCashExpenseAmount(
    reportDate: String,
    category: String,
    legacyAmount: Long
): Long {
    val records = filter { it.expenseDate == reportDate && it.category == category }
    return if (records.isNotEmpty()) records.cashExpenseAmount() else legacyAmount
}
