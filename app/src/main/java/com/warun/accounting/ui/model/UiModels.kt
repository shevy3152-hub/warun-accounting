package com.warun.accounting.ui.model

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.ui.util.currentMonthString
import com.warun.accounting.ui.util.todayString

data class DashboardUiState(
    val reports: List<DailyReport> = emptyList(),
    val receipts: List<ReceiptRecord> = emptyList(),
    val expenses: List<ExpenseRecord> = emptyList(),
    val monthlySubmissions: List<MonthlySubmission> = emptyList(),
    val supplierCandidates: List<SupplierCandidateRecord> = emptyList(),
    val appSettings: AppSettings? = null
) {
    private val today = todayString()
    private val currentMonth = currentMonthString()
    private val todayReports = reports.filter { it.reportDate == today }
    private val monthReports = reports.filter { it.reportDate.startsWith(currentMonth) }
    private val monthReceipts = receipts.filter { it.purchaseDate?.startsWith(currentMonth) == true }
    private val monthExpenses = expenses.filter { it.expenseDate.startsWith(currentMonth) }

    private fun DailyReport.salesTotal(): Long =
        cashSales + cardSales + qrSales + accountsReceivableSales + otherSales

    private fun expenseCategoryTotal(reportDate: String, category: String): Long =
        expenses.filter { it.expenseDate == reportDate && it.category == category }.sumOf { it.amount }

    private fun DailyReport.utilityExpenseTotal(): Long {
        val breakdownTotal = electricityExpense + gasExpense + waterExpense
        return breakdownTotal.takeIf { it > 0L } ?: utilitiesExpense
    }

    private fun DailyReport.expenseTotal(): Long =
        expenseCategoryTotal(reportDate, ExpenseCategory.FoodPurchase) +
            expenseCategoryTotal(reportDate, ExpenseCategory.AlcoholPurchase) +
            expenseCategoryTotal(reportDate, ExpenseCategory.Consumables) +
            expenseCategoryTotal(reportDate, ExpenseCategory.OtherExpense) +
            expenseCategoryTotal(reportDate, ExpenseCategory.VehicleTransport) +
            consumablesExpense + utilityExpenseTotal() + communicationExpense + rentExpense + accountantFeeExpense + miscellaneousExpense

    val salesTotal: Long = reports.sumOf { it.salesTotal() }
    val expenseTotal: Long = reports.sumOf { it.expenseTotal() } + expensesWithoutReportsTotal(reports, expenses)
    val cashSales: Long = reports.sumOf { it.cashSales }
    val cardSales: Long = reports.sumOf { it.cardSales }
    val qrSales: Long = reports.sumOf { it.qrSales }
    val accountsReceivableSales: Long = reports.sumOf { it.accountsReceivableSales }
    val otherSales: Long = reports.sumOf { it.otherSales }
    val cashExpenses: Long = expenseTotal
    val latestReport: DailyReport? = reports.maxByOrNull { it.reportDate }
    val closingCash: Long = latestReport?.actualClosingCash?.takeIf { it > 0 }
        ?: ((latestReport?.openingCash ?: 0L) + cashSales - cashExpenses)

    val todaySales: Long = todayReports.sumOf { it.salesTotal() }
    private val todayExpensesWithoutReports: Long = expensesWithoutReportsTotal(todayReports, expenses.filter { it.expenseDate == today })
    val todayExpensesTotal: Long = todayReports.sumOf { it.expenseTotal() } + todayExpensesWithoutReports
    val todayBalance: Long = todaySales - todayExpensesTotal
    val todayCashSales: Long = todayReports.sumOf { it.cashSales }
    val todayCashExpenses: Long = todayExpensesTotal
    private val todayLatestReport: DailyReport? = todayReports.maxByOrNull { it.reportDate }
    private val todayTheoreticalClosingCash: Long =
        (todayLatestReport?.openingCash ?: 0L) + todayCashSales - todayCashExpenses
    val todayCashDifference: Long =
        (todayLatestReport?.actualClosingCash?.takeIf { it > 0 } ?: todayTheoreticalClosingCash) - todayTheoreticalClosingCash

    val monthSales: Long = monthReports.sumOf { it.salesTotal() }
    val monthReceiptExpensesTotal: Long = 0L
    val monthExpensesTotal: Long = monthReports.sumOf { it.expenseTotal() } + expensesWithoutReportsTotal(monthReports, monthExpenses)
    val monthEstimatedBalance: Long = monthSales - monthExpensesTotal
    val unconfirmedReceiptCount: Int = receipts.count { !it.isConfirmed }
    val dateUnknownReceiptCount: Int = receipts.count { it.purchaseDate.isNullOrBlank() }
    val monthUnconfirmedReceiptCount: Int = monthReceipts.count { !it.isConfirmed }
    val monthDraftReportCount: Int = monthReports.count { it.status == DailyReportStatus.Draft }
    val currentMonthSubmitted: Boolean = monthlySubmissions.any {
        it.targetMonth == currentMonth && it.status == MonthlySubmissionStatus.Submitted
    }
    val currentMonthSubmissionLabel: String = if (currentMonthSubmitted) "提出済み" else "未提出"
    val latestGuestUnitPrice: Long = latestReport
        ?.takeIf { it.customerCount > 0 }
        ?.let { it.salesTotal() / it.customerCount }
        ?: 0L
}

private fun expensesWithoutReportsTotal(reports: List<DailyReport>, expenses: List<ExpenseRecord>): Long {
    val reportDates = reports.map { it.reportDate }.toSet()
    return expenses.filterNot { it.expenseDate in reportDates }.sumOf { it.amount }
}