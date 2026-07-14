package com.warun.accounting.ui.model

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.ui.util.currentMonthString
import com.warun.accounting.ui.util.todayString

data class DashboardUiState(
    val reports: List<DailyReport> = emptyList(),
    val receipts: List<ReceiptRecord> = emptyList(),
    val monthlySubmissions: List<MonthlySubmission> = emptyList(),
    val appSettings: AppSettings? = null
) {
    private val today = todayString()
    private val currentMonth = currentMonthString()
    private val todayReports = reports.filter { it.reportDate == today }
    private val monthReports = reports.filter { it.reportDate.startsWith(currentMonth) }
    private val monthReceipts = receipts.filter { it.purchaseDate?.startsWith(currentMonth) == true }

    private fun DailyReport.salesTotal(): Long =
        cashSales + cardSales + qrSales + accountsReceivableSales + otherSales

    private fun DailyReport.expenseTotal(): Long =
        foodPurchases + alcoholPurchases + consumablesExpense + utilitiesExpense + miscellaneousExpense + otherExpense

    val salesTotal: Long = reports.sumOf { it.salesTotal() }
    val expenseTotal: Long = reports.sumOf { it.expenseTotal() }
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
    val todayExpensesTotal: Long = todayReports.sumOf { it.expenseTotal() }
    val todayBalance: Long = todaySales - todayExpensesTotal
    val todayCashSales: Long = todayReports.sumOf { it.cashSales }
    val todayCashExpenses: Long = todayExpensesTotal
    private val todayLatestReport: DailyReport? = todayReports.maxByOrNull { it.reportDate }
    private val todayTheoreticalClosingCash: Long =
        (todayLatestReport?.openingCash ?: 0L) + todayCashSales - todayCashExpenses
    val todayCashDifference: Long =
        (todayLatestReport?.actualClosingCash?.takeIf { it > 0 } ?: todayTheoreticalClosingCash) - todayTheoreticalClosingCash

    val monthSales: Long = monthReports.sumOf { it.salesTotal() }
    val monthReceiptExpensesTotal: Long = monthReceipts.sumOf { it.totalAmount }
    val monthExpensesTotal: Long = monthReports.sumOf { it.expenseTotal() } + monthReceiptExpensesTotal
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
