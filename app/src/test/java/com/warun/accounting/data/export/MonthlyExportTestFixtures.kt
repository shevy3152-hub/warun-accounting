package com.warun.accounting.data.export

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import java.time.YearMonth

internal object MonthlyExportTestFixtures {
    val targetMonth: YearMonth = YearMonth.of(2026, 6)

    fun source(): MonthlyExportSourceSnapshot {
        val active = listOf(
            expense("cash", "2026-06-01", 100L, "現金", "現金店"),
            expense("credit", "2026-06-02", 200L, "クレジットカード", "カード店"),
            expense("electronic", "2026-06-03", 300L, "電子マネー", "電子店"),
            expense("credit-purchase", "2026-06-04", 400L, "掛け", "掛け店"),
            expense("prepaid", "2026-06-05", 500L, "プリペイド", "プリペイド店"),
            expense("edited", "2026-06-06", 650L, "現金", "編集後の支出先", memo = "編集後の用途")
        )
        val cancelled = expense("cancelled", "2026-06-07", 9_999L, "現金", "取消店")
        return MonthlyExportSourceSnapshot(
            dailyReports = listOf(
                report("report-1", "2026-06-01", 1_000L, 500L, 250L, 100L, 50L),
                report("report-2", "2026-06-02", 200L, 300L, 400L, 500L, 600L)
            ),
            expenseVisibility = active.map { ExpenseVisibilityRecord(it, false) } +
                ExpenseVisibilityRecord(cancelled, true),
            storedEvidence = listOf(
                evidence("cash", "ev-cash-1", 1L),
                evidence("cash", "ev-cash-2", 2L),
                evidence("cancelled", "ev-cancelled", 3L)
            )
        )
    }

    fun snapshot(): MonthlyExportSnapshot {
        val provider = MonthlyExportSnapshotProvider(
            object : MonthlyExportRepository {
                override suspend fun loadSourceSnapshot(
                    targetMonth: YearMonth
                ): MonthlyExportSourceSnapshot = error("not used")
            }
        )
        return (provider.assemble(targetMonth, source()) as MonthlyExportSnapshotResult.Success).snapshot
    }

    fun report(
        id: String,
        date: String,
        cash: Long,
        card: Long,
        qr: Long,
        accountsReceivable: Long,
        other: Long
    ) = DailyReport(
        id = id,
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = cash,
        cardSales = card,
        qrSales = qr,
        accountsReceivableSales = accountsReceivable,
        otherSales = other,
        foodPurchases = 0L,
        alcoholPurchases = 0L,
        consumablesExpense = 0L,
        utilitiesExpense = 0L,
        electricityExpense = 0L,
        gasExpense = 0L,
        waterExpense = 0L,
        communicationExpense = 0L,
        rentExpense = 0L,
        accountantFeeExpense = 0L,
        miscellaneousExpense = 0L,
        otherExpense = 0L,
        openingCash = 0L,
        actualClosingCash = 0L,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1L,
        updatedAt = 1L,
        hasActualClosingCash = false
    )

    fun expense(
        id: String,
        date: String,
        amount: Long,
        paymentMethod: String?,
        supplier: String,
        memo: String = "用途-$id"
    ) = ExpenseRecord(
        id = id,
        expenseDate = date,
        category = ExpenseCategory.OtherExpense,
        supplierName = supplier,
        amount = amount,
        paymentMethod = paymentMethod,
        memo = memo,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = id.length.toLong(),
        updatedAt = id.length.toLong() + 1L
    )

    fun evidence(expenseId: String, evidenceId: String, order: Long) = ExpenseEvidenceRecord(
        expenseId = expenseId,
        evidenceId = evidenceId,
        captureId = "capture-$evidenceId",
        storedUri = "file:/stored/$evidenceId.jpg",
        byteSize = 100L,
        sha256 = "sha-$evidenceId",
        createdAt = order,
        storedAt = order + 10L
    )
}
