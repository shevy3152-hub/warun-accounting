package com.warun.accounting.data.export

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailure
import com.warun.accounting.data.metrics.BusinessMetricSnapshotProvider
import com.warun.accounting.data.metrics.BusinessMetricSnapshotRequest
import com.warun.accounting.data.metrics.BusinessMetricSnapshotResult
import com.warun.accounting.domain.metrics.AssemblyFailureReason
import com.warun.accounting.domain.metrics.BusinessMetricInputAssembler
import com.warun.accounting.domain.metrics.BusinessMetricInputAssemblyResult
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.util.paymentMethodDisplayName
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

data class DailyReportExportRow(
    val reportId: String,
    val reportDate: LocalDate,
    val cashSales: Long,
    val cardSales: Long,
    val qrSales: Long,
    val accountsReceivableSales: Long,
    val otherSales: Long,
    val salesTotal: Long
)

data class ExpenseDetailExportRow(
    val expenseId: String,
    val expenseDate: LocalDate,
    val supplierName: String,
    val amount: Long,
    val memo: String,
    val paymentMethod: String,
    val evidenceCount: Int
)

data class StoredEvidenceExportItem(
    val expenseId: String,
    val evidenceId: String,
    val captureId: String,
    val storedUri: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long,
    val storedAt: Long,
    val mediaType: String = "image/jpeg",
    val fixedCostType: String? = null,
    val reportDate: LocalDate? = null,
    val sortOrder: Int = 0
)

data class DailyReportExportTotals(
    val cashSales: Long,
    val cardSales: Long,
    val qrSales: Long,
    val accountsReceivableSales: Long,
    val otherSales: Long,
    val salesTotal: Long
)

data class MonthlyExportSnapshot(
    val targetMonth: YearMonth,
    val dailyReports: List<DailyReportExportRow>,
    val dailyReportTotals: DailyReportExportTotals,
    val expenses: List<ExpenseDetailExportRow>,
    val expenseTotal: Long,
    val storedEvidence: List<StoredEvidenceExportItem>,
    val fixedCostStoredEvidence: List<StoredEvidenceExportItem> = emptyList()
) {
    val dailyReportFileName: String
        get() = "${targetMonth.year}年${targetMonth.monthValue}月_日報.xlsx"

    val expenseDetailFileName: String
        get() = "${targetMonth.year}年${targetMonth.monthValue}月_支出明細.xlsx"
}

/** Safe integer range for Excel's documented 15-significant-digit numeric precision. */
object ExcelIntegerContract {
    const val MaxExactInteger = 999_999_999_999_999L
    const val MinExactInteger = -MaxExactInteger

    fun isExact(value: Long): Boolean = value in MinExactInteger..MaxExactInteger
}

enum class MonthlyExportValidationFailureReason {
    INVALID_REPORT_DATE,
    INVALID_EXPENSE_DATE,
    REPORT_OUTSIDE_TARGET_MONTH,
    EXPENSE_OUTSIDE_TARGET_MONTH,
    DUPLICATE_DAILY_REPORT_DATE,
    DUPLICATE_OR_CONFLICTING_EXPENSE,
    INCONSISTENT_EVIDENCE_LINK,
    DUPLICATE_EVIDENCE,
    INVALID_SOURCE_DATA,
    AMOUNT_OVERFLOW,
    BUSINESS_METRIC_MAPPING_FAILURE,
    SALES_TOTAL_MISMATCH,
    EXCEL_INTEGER_OUT_OF_RANGE
}

data class MonthlyExportValidationFailure(
    val reason: MonthlyExportValidationFailureReason,
    val recordId: String? = null,
    val detail: String? = null
)

sealed interface MonthlyExportSnapshotResult {
    data class Success(val snapshot: MonthlyExportSnapshot) : MonthlyExportSnapshotResult
    data class ValidationFailure(
        val failure: MonthlyExportValidationFailure
    ) : MonthlyExportSnapshotResult
    data class DataAccessFailure(
        val exceptionType: String,
        val message: String?
    ) : MonthlyExportSnapshotResult
}

class MonthlyExportSnapshotProvider @Inject constructor(
    private val repository: MonthlyExportRepository
) {
    suspend fun load(targetMonth: YearMonth): MonthlyExportSnapshotResult {
        val source = try {
            repository.loadSourceSnapshot(targetMonth)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            return MonthlyExportSnapshotResult.DataAccessFailure(
                exceptionType = throwable::class.qualifiedName ?: throwable::class.simpleName.orEmpty(),
                message = throwable.message
            )
        }
        return assemble(targetMonth, source)
    }

    internal fun assemble(
        targetMonth: YearMonth,
        source: MonthlyExportSourceSnapshot
    ): MonthlyExportSnapshotResult {
        val datedReports = mutableListOf<Pair<DailyReport, LocalDate>>()
        source.dailyReports.forEach { report ->
            val date = parseDate(report.reportDate) ?: return validationFailure(
                MonthlyExportValidationFailureReason.INVALID_REPORT_DATE,
                report.id,
                report.reportDate
            )
            if (YearMonth.from(date) != targetMonth) {
                return validationFailure(
                    MonthlyExportValidationFailureReason.REPORT_OUTSIDE_TARGET_MONTH,
                    report.id,
                    report.reportDate
                )
            }
            datedReports += report to date
        }
        datedReports.groupBy { it.second }.entries.firstOrNull { it.value.size > 1 }?.let {
            return validationFailure(
                MonthlyExportValidationFailureReason.DUPLICATE_DAILY_REPORT_DATE,
                it.value.first().first.id,
                it.key.toString()
            )
        }

        val datedVisibility = mutableListOf<Pair<com.warun.accounting.data.local.ExpenseVisibilityRecord, LocalDate>>()
        source.expenseVisibility.forEach { row ->
            val expense = row.expense
            val date = parseDate(expense.expenseDate) ?: return validationFailure(
                MonthlyExportValidationFailureReason.INVALID_EXPENSE_DATE,
                expense.id,
                expense.expenseDate
            )
            if (YearMonth.from(date) != targetMonth) {
                return validationFailure(
                    MonthlyExportValidationFailureReason.EXPENSE_OUTSIDE_TARGET_MONTH,
                    expense.id,
                    expense.expenseDate
                )
            }
            datedVisibility += row to date
        }
        datedVisibility.groupBy { it.first.expense.id }.entries.firstOrNull { it.value.size > 1 }?.let {
            return validationFailure(
                MonthlyExportValidationFailureReason.DUPLICATE_OR_CONFLICTING_EXPENSE,
                it.key
            )
        }

        val metricResult = BusinessMetricSnapshotProvider.mapSourceSnapshot(
            request = BusinessMetricSnapshotRequest(
                period = MetricPeriod.Monthly(targetMonth),
                evaluationDate = targetMonth.atEndOfMonth(),
                calculatedAt = Instant.EPOCH
            ),
            reports = source.dailyReports,
            visibility = source.expenseVisibility
        )
        val formalSalesTotal = when (metricResult) {
            is BusinessMetricSnapshotResult.MappingFailure ->
                return metricMappingFailure(metricResult.failure)
            is BusinessMetricSnapshotResult.DataAccessFailure ->
                return MonthlyExportSnapshotResult.DataAccessFailure(
                    metricResult.exceptionType,
                    metricResult.message
                )
            is BusinessMetricSnapshotResult.Success -> when (
                val assembled = BusinessMetricInputAssembler.assemble(metricResult.snapshot)
            ) {
                is BusinessMetricInputAssemblyResult.Success -> assembled.input.recordedSalesYen
                is BusinessMetricInputAssemblyResult.InconsistentSnapshot -> {
                    val reason = if (
                        AssemblyFailureReason.DUPLICATE_DAILY_REPORT_DATE in assembled.reasons
                    ) {
                        MonthlyExportValidationFailureReason.DUPLICATE_DAILY_REPORT_DATE
                    } else {
                        MonthlyExportValidationFailureReason.DUPLICATE_OR_CONFLICTING_EXPENSE
                    }
                    return validationFailure(reason, detail = assembled.reasons.joinToString())
                }
                is BusinessMetricInputAssemblyResult.InvalidSourceData ->
                    return validationFailure(
                        MonthlyExportValidationFailureReason.INVALID_SOURCE_DATA,
                        detail = assembled.reasons.joinToString()
                    )
                is BusinessMetricInputAssemblyResult.Overflow ->
                    return validationFailure(
                        MonthlyExportValidationFailureReason.AMOUNT_OVERFLOW,
                        detail = assembled.reasons.joinToString()
                    )
            }
        }

        val reportRows: List<DailyReportExportRow>
        val reportTotals: DailyReportExportTotals
        try {
            reportRows = datedReports
                .sortedWith(compareBy<Pair<DailyReport, LocalDate>> { it.second }.thenBy { it.first.id })
                .map { (report, date) -> report.toExportRow(date) }
            reportTotals = DailyReportExportTotals(
                cashSales = reportRows.exactSum(DailyReportExportRow::cashSales),
                cardSales = reportRows.exactSum(DailyReportExportRow::cardSales),
                qrSales = reportRows.exactSum(DailyReportExportRow::qrSales),
                accountsReceivableSales = reportRows.exactSum(
                    DailyReportExportRow::accountsReceivableSales
                ),
                otherSales = reportRows.exactSum(DailyReportExportRow::otherSales),
                salesTotal = reportRows.exactSum(DailyReportExportRow::salesTotal)
            )
        } catch (_: ArithmeticException) {
            return validationFailure(MonthlyExportValidationFailureReason.AMOUNT_OVERFLOW)
        }

        val reportNumericValues = reportRows.flatMap { row ->
            listOf(
                row.cashSales,
                row.cardSales,
                row.qrSales,
                row.accountsReceivableSales,
                row.otherSales,
                row.salesTotal
            )
        } + listOf(
            reportTotals.cashSales,
            reportTotals.cardSales,
            reportTotals.qrSales,
            reportTotals.accountsReceivableSales,
            reportTotals.otherSales,
            reportTotals.salesTotal
        )
        reportNumericValues.firstOrNull { !ExcelIntegerContract.isExact(it) }?.let { value ->
            return validationFailure(
                MonthlyExportValidationFailureReason.EXCEL_INTEGER_OUT_OF_RANGE,
                detail = value.toString()
            )
        }

        if (formalSalesTotal != reportTotals.salesTotal.takeIf { reportRows.isNotEmpty() }) {
            return validationFailure(
                MonthlyExportValidationFailureReason.SALES_TOTAL_MISMATCH,
                detail = "formal=$formalSalesTotal,rowTotal=${reportTotals.salesTotal}"
            )
        }

        val activeExpensesById = datedVisibility
            .filterNot { it.first.isCancelled }
            .associate { it.first.expense.id to (it.first.expense to it.second) }
        val monthlyExpenseIds = datedVisibility.map { it.first.expense.id }.toSet()
        source.storedEvidence.firstOrNull { it.expenseId !in monthlyExpenseIds }?.let {
            return validationFailure(
                MonthlyExportValidationFailureReason.INCONSISTENT_EVIDENCE_LINK,
                it.evidenceId,
                it.expenseId
            )
        }
        source.storedEvidence.groupBy { it.evidenceId }.entries
            .firstOrNull { it.value.size > 1 }
            ?.let {
                return validationFailure(
                    MonthlyExportValidationFailureReason.DUPLICATE_EVIDENCE,
                    it.key
                )
            }

        val activeEvidence = source.storedEvidence
            .filter { it.expenseId in activeExpensesById }
            .sortedWith(
                compareBy<com.warun.accounting.data.local.ExpenseEvidenceRecord> {
                    activeExpensesById.getValue(it.expenseId).second
                }.thenBy { activeExpensesById.getValue(it.expenseId).first.createdAt }
                    .thenBy { it.expenseId }
                    .thenBy { it.createdAt }
                    .thenBy { it.evidenceId }
            )
        val fixedCostEvidence = source.storedFixedCostEvidence
            .map { item ->
                StoredEvidenceExportItem(
                    expenseId = "fixed:${item.dailyReportId}:${item.fixedCostType}",
                    evidenceId = item.evidenceId,
                    captureId = item.captureId,
                    storedUri = item.storedUri,
                    byteSize = item.byteSize,
                    sha256 = item.sha256,
                    createdAt = item.createdAt,
                    storedAt = item.storedAt,
                    mediaType = item.mediaType,
                    fixedCostType = item.fixedCostType,
                    reportDate = parseDate(item.reportDate),
                    sortOrder = item.sortOrder
                )
            }
            .sortedWith(compareBy<StoredEvidenceExportItem> { it.reportDate }.thenBy { it.fixedCostType }.thenBy { it.sortOrder }.thenBy { it.evidenceId })
        val allEvidence = activeEvidence.map { it.evidenceId } + fixedCostEvidence.map { it.evidenceId }
        allEvidence.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let {
            return validationFailure(MonthlyExportValidationFailureReason.DUPLICATE_EVIDENCE, it.key)
        }
        val evidenceCountByExpense = activeEvidence.groupingBy { it.expenseId }.eachCount()

        val expenseRows: List<ExpenseDetailExportRow>
        val expenseTotal: Long
        try {
            expenseRows = activeExpensesById.values
                .sortedWith(
                    compareBy<Pair<ExpenseRecord, LocalDate>> { it.second }
                        .thenBy { it.first.createdAt }
                        .thenBy { it.first.id }
                )
                .map { (expense, date) ->
                    ExpenseDetailExportRow(
                        expenseId = expense.id,
                        expenseDate = date,
                        supplierName = expense.supplierName.orEmpty(),
                        amount = expense.amount,
                        memo = expense.memo.orEmpty(),
                        paymentMethod = paymentMethodDisplayName(expense.paymentMethod),
                        evidenceCount = evidenceCountByExpense[expense.id] ?: 0
                    )
                }
            expenseTotal = expenseRows.fold(0L) { total, row ->
                Math.addExact(total, row.amount)
            }
        } catch (_: ArithmeticException) {
            return validationFailure(MonthlyExportValidationFailureReason.AMOUNT_OVERFLOW)
        }

        (expenseRows.map(ExpenseDetailExportRow::amount) + expenseTotal)
            .firstOrNull { !ExcelIntegerContract.isExact(it) }
            ?.let { value ->
                return validationFailure(
                    MonthlyExportValidationFailureReason.EXCEL_INTEGER_OUT_OF_RANGE,
                    detail = value.toString()
                )
            }

        return MonthlyExportSnapshotResult.Success(
            MonthlyExportSnapshot(
                targetMonth = targetMonth,
                dailyReports = reportRows,
                dailyReportTotals = reportTotals,
                expenses = expenseRows,
                expenseTotal = expenseTotal,
                storedEvidence = activeEvidence.map { evidence ->
                    StoredEvidenceExportItem(
                        expenseId = evidence.expenseId,
                        evidenceId = evidence.evidenceId,
                        captureId = evidence.captureId,
                        storedUri = evidence.storedUri,
                        byteSize = evidence.byteSize,
                        sha256 = evidence.sha256,
                        createdAt = evidence.createdAt,
                        storedAt = evidence.storedAt
                    )
                },
                fixedCostStoredEvidence = fixedCostEvidence
            )
        )
    }

    private fun DailyReport.toExportRow(date: LocalDate): DailyReportExportRow =
        DailyReportExportRow(
            reportId = id,
            reportDate = date,
            cashSales = cashSales,
            cardSales = cardSales,
            qrSales = qrSales,
            accountsReceivableSales = accountsReceivableSales,
            otherSales = otherSales,
            salesTotal = cashSales
                .let { Math.addExact(it, cardSales) }
                .let { Math.addExact(it, qrSales) }
                .let { Math.addExact(it, accountsReceivableSales) }
                .let { Math.addExact(it, otherSales) }
        )

    private fun List<DailyReportExportRow>.exactSum(
        selector: (DailyReportExportRow) -> Long
    ): Long = fold(0L) { total, row -> Math.addExact(total, selector(row)) }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun metricMappingFailure(
        failure: BusinessMetricSnapshotMappingFailure
    ): MonthlyExportSnapshotResult.ValidationFailure = validationFailure(
        MonthlyExportValidationFailureReason.BUSINESS_METRIC_MAPPING_FAILURE,
        failure.recordId,
        listOfNotNull(
            failure.reason.name,
            failure.originalValue,
            failure.categoryFailure?.javaClass?.simpleName
        ).joinToString(":")
    )

    private fun validationFailure(
        reason: MonthlyExportValidationFailureReason,
        recordId: String? = null,
        detail: String? = null
    ) = MonthlyExportSnapshotResult.ValidationFailure(
        MonthlyExportValidationFailure(reason, recordId, detail)
    )
}
