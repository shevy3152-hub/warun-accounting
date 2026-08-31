package com.warun.accounting.ui.model

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.data.local.PrepaidAccountBalance
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.prepaid.netCashChargeAmount
import com.warun.accounting.ui.util.currentMonthString
import com.warun.accounting.ui.util.todayString
import com.warun.accounting.util.calculateCashBalance
import com.warun.accounting.util.calculateCashFlow
import com.warun.accounting.util.cashExpenseAmount
import com.warun.accounting.util.expenseAmount
import com.warun.accounting.util.ExpenseDateCategoryKey
import com.warun.accounting.util.preferredCashExpenseAmount
import com.warun.accounting.util.preferredExpenseAmount

data class DashboardUiState(
    val reports: List<DailyReport> = emptyList(),
    val receipts: List<ReceiptRecord> = emptyList(),
    val expenses: List<ExpenseRecord> = emptyList(),
    val expenseEvidence: List<ExpenseEvidenceRecord> = emptyList(),
    val monthlySubmissions: List<MonthlySubmission> = emptyList(),
    val supplierCandidates: List<SupplierCandidateRecord> = emptyList(),
    val appSettings: AppSettings? = null,
    val prepaidTransactions: List<PrepaidTransactionRecord> = emptyList(),
    val prepaidAccounts: List<PrepaidAccountRecord> = emptyList(),
    val prepaidBalances: List<PrepaidAccountBalance> = emptyList(),
    val expensePrepaidLinks: List<ExpensePrepaidLinkRecord> = emptyList(),
    val cancelledExpenseKeys: Set<ExpenseDateCategoryKey> = emptySet()
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
        expenses.filter { it.expenseDate == reportDate && it.category == category }.expenseAmount()

    private fun cashExpenseCategoryTotal(reportDate: String, category: String): Long =
        expenses.filter { it.expenseDate == reportDate && it.category == category }.cashExpenseAmount()

    private fun DailyReport.utilityExpenseTotal(): Long {
        val breakdownTotal = electricityExpense + gasExpense + waterExpense
        return breakdownTotal.takeIf { it > 0L } ?: utilitiesExpense
    }

    private fun DailyReport.expenseTotal(): Long =
        expenseCategoryTotal(reportDate, ExpenseCategory.FoodPurchase) +
            expenseCategoryTotal(reportDate, ExpenseCategory.AlcoholPurchase) +
            expenses.preferredExpenseAmount(
                reportDate,
                ExpenseCategory.Consumables,
                consumablesExpense,
                cancelledExpenseKeys
            ) +
            expenseCategoryTotal(reportDate, ExpenseCategory.OtherExpense) +
            expenseCategoryTotal(reportDate, ExpenseCategory.VehicleTransport) +
            directExpenseTotal()

    private fun DailyReport.cashExpenseTotal(): Long =
        cashExpenseCategoryTotal(reportDate, ExpenseCategory.FoodPurchase) +
            cashExpenseCategoryTotal(reportDate, ExpenseCategory.AlcoholPurchase) +
            expenses.preferredCashExpenseAmount(
                reportDate,
                ExpenseCategory.Consumables,
                consumablesExpense,
                cancelledExpenseKeys
            ) +
            cashExpenseCategoryTotal(reportDate, ExpenseCategory.OtherExpense) +
            cashExpenseCategoryTotal(reportDate, ExpenseCategory.VehicleTransport) +
            cashDirectExpenseTotal()

    private fun DailyReport.directExpenseTotal(): Long =
        utilityExpenseTotal() + communicationExpense + rentExpense + accountantFeeExpense + miscellaneousExpense

    private fun DailyReport.cashDirectExpenseTotal(): Long =
        electricityExpense + waterExpense + communicationExpense + rentExpense +
            accountantFeeExpense + miscellaneousExpense

    val salesTotal: Long = reports.sumOf { it.salesTotal() }
    val expenseTotal: Long = reports.sumOf { it.expenseTotal() } + expensesWithoutReportsTotal(reports, expenses)
    val cashSales: Long = reports.sumOf { it.cashSales }
    val cardSales: Long = reports.sumOf { it.cardSales }
    val qrSales: Long = reports.sumOf { it.qrSales }
    val accountsReceivableSales: Long = reports.sumOf { it.accountsReceivableSales }
    val otherSales: Long = reports.sumOf { it.otherSales }
    val cashExpenses: Long = reports.sumOf { it.cashExpenseTotal() } + cashExpensesWithoutReportsTotal(reports, expenses)
    val cashCharges: Long = netCashChargeAmount(prepaidTransactions)
    val cashOutflow: Long = cashExpenses + cashCharges
    val latestReport: DailyReport? = reports.maxByOrNull { it.reportDate }
    val closingCash: Long = latestReport?.takeIf { it.hasActualClosingCash }?.actualClosingCash
        ?: calculateCashBalance(latestReport?.openingCash ?: 0L, cashSales, cashOutflow)

    val todaySales: Long = todayReports.sumOf { it.salesTotal() }
    private val todayExpensesWithoutReports: Long = expensesWithoutReportsTotal(todayReports, expenses.filter { it.expenseDate == today })
    val todayExpensesTotal: Long = todayReports.sumOf { it.expenseTotal() } + todayExpensesWithoutReports
    val todayBalance: Long = todaySales - todayExpensesTotal
    val todayCashSales: Long = todayReports.sumOf { it.cashSales }
    val todayCashExpenses: Long = todayReports.sumOf { it.cashExpenseTotal() } + cashExpensesWithoutReportsTotal(todayReports, expenses.filter { it.expenseDate == today })
    val todayCashCharges: Long = netCashChargeAmount(prepaidTransactions) { it == today }
    val todayCashOutflow: Long = todayCashExpenses + todayCashCharges
    val todayCashFlow: Long = calculateCashFlow(todayCashSales, todayCashOutflow)
    private val todayLatestReport: DailyReport? = todayReports.maxByOrNull { it.reportDate }
    private val todayTheoreticalClosingCash: Long =
        calculateCashBalance(todayLatestReport?.openingCash ?: 0L, todayCashSales, todayCashOutflow)
    val todayClosingCash: Long = todayLatestReport?.takeIf { it.hasActualClosingCash }?.actualClosingCash
        ?: todayTheoreticalClosingCash
    val todayCashDifference: Long = todayClosingCash - todayTheoreticalClosingCash

    val monthSales: Long = monthReports.sumOf { it.salesTotal() }
    val monthReceiptExpensesTotal: Long = 0L
    val monthExpensesTotal: Long = monthReports.sumOf { it.expenseTotal() } + expensesWithoutReportsTotal(monthReports, monthExpenses)
    val monthEstimatedBalance: Long = monthSales - monthExpensesTotal
    val unconfirmedReceiptCount: Int = receipts.count { !it.isConfirmed }
    val dateUnknownReceiptCount: Int = receipts.count {
        !it.isConfirmed && it.purchaseDate.isNullOrBlank()
    }
    val monthUnconfirmedReceiptCount: Int = monthReceipts.count { !it.isConfirmed }
    val monthDraftReportCount: Int = monthReports.count { it.status == DailyReportStatus.Draft }
    val currentMonthSubmitted: Boolean = monthlySubmissions.any {
        it.targetMonth == currentMonth && it.status == MonthlySubmissionStatus.Submitted
    }
    val currentMonthSubmissionLabel: String = PaperSubmissionCopy.statusLabel(currentMonthSubmitted)
    val latestGuestUnitPrice: Long = latestReport
        ?.takeIf { it.customerCount > 0 }
        ?.let { it.salesTotal() / it.customerCount }
        ?: 0L

    fun prepaidAccountForExpense(expenseId: String): PrepaidAccountRecord? {
        val purchaseId = expensePrepaidLinks
            .firstOrNull { it.expenseId == expenseId }
            ?.purchaseTransactionId
            ?: return null
        val accountId = prepaidTransactions
            .firstOrNull { it.id == purchaseId }
            ?.accountId
            ?: return null
        return prepaidAccounts.firstOrNull { it.id == accountId }
    }
}

private fun expensesWithoutReportsTotal(reports: List<DailyReport>, expenses: List<ExpenseRecord>): Long {
    val reportDates = reports.map { it.reportDate }.toSet()
    return expenses.filterNot { it.expenseDate in reportDates }.expenseAmount()
}

private fun cashExpensesWithoutReportsTotal(reports: List<DailyReport>, expenses: List<ExpenseRecord>): Long {
    val reportDates = reports.map { it.reportDate }.toSet()
    return expenses.filterNot { it.expenseDate in reportDates }.cashExpenseAmount()
}
