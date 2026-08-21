package com.warun.accounting.data.metrics

import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricDailyReportSource
import com.warun.accounting.domain.metrics.MetricExpenseSource
import com.warun.accounting.domain.metrics.MetricExpenseCategoryMapper
import com.warun.accounting.domain.metrics.MetricExpenseCategoryMappingResult
import com.warun.accounting.domain.metrics.MetricExpenseVisibilitySource
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.util.ExpenseDateCategoryKey
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class BusinessMetricSnapshotRequest(
    val period: MetricPeriod,
    val evaluationDate: LocalDate,
    val calculatedAt: Instant,
)

enum class BusinessMetricSnapshotMappingFailureReason {
    INVALID_REPORT_DATE,
    INVALID_EXPENSE_DATE,
    UNKNOWN_EXPENSE_CATEGORY,
    DUPLICATE_EXPENSE_VISIBILITY,
    CONFLICTING_EXPENSE_VISIBILITY,
    AMOUNT_OVERFLOW,
}

data class BusinessMetricSnapshotMappingFailure(
    val reason: BusinessMetricSnapshotMappingFailureReason,
    val recordId: String?,
    val originalValue: String? = null,
    val categoryFailure: MetricExpenseCategoryMappingResult.Failure? = null,
)

/**
 * Same-generation legacy inputs used only by the debug comparison boundary.
 * The normal BusinessMetric calculation continues to consume the domain snapshot below.
 */
data class BusinessMetricComparisonSource(
    val reports: List<DailyReport>,
    val activeExpenses: List<ExpenseRecord>,
    val cancelledExpenseKeys: Set<ExpenseDateCategoryKey>,
)

sealed interface BusinessMetricSnapshotResult {
    data class Success(
        val snapshot: BusinessMetricSourceSnapshot,
        val comparisonSource: BusinessMetricComparisonSource? = null,
    ) : BusinessMetricSnapshotResult

    data class MappingFailure(
        val failure: BusinessMetricSnapshotMappingFailure,
    ) : BusinessMetricSnapshotResult

    data class DataAccessFailure(
        val exceptionType: String,
        val message: String?,
    ) : BusinessMetricSnapshotResult
}

/**
 * Converts the existing repository flows into a domain-only metrics snapshot.
 *
 * The visibility projection is intentionally the only expense input. It contains each expense
 * and its cancellation flag from the same Room query, so active and cancelled rows are not
 * assembled from separate generations.
 */
class BusinessMetricSnapshotProvider private constructor(
    private val observeDailyReports: () -> Flow<List<DailyReport>>,
    private val observeExpenseVisibilityRecords: () -> Flow<List<ExpenseVisibilityRecord>>,
) {
    companion object {
        /**
         * Maps an already consistent repository snapshot through the same production mapping
         * rules used by [observe]. Export uses this boundary so it cannot invent a second sales
         * calculation.
         */
        internal fun mapSourceSnapshot(
            request: BusinessMetricSnapshotRequest,
            reports: List<DailyReport>,
            visibility: List<ExpenseVisibilityRecord>,
        ): BusinessMetricSnapshotResult = BusinessMetricSnapshotProvider(
            dailyReports = kotlinx.coroutines.flow.flowOf(reports),
            expenseVisibilityRecords = kotlinx.coroutines.flow.flowOf(visibility),
        ).mapSnapshot(request, reports, visibility)
    }

    @Inject
    constructor(accountingRepository: AccountingRepository) : this(
        observeDailyReports = accountingRepository::observeDailyReports,
        observeExpenseVisibilityRecords = accountingRepository::observeExpenseVisibilityRecords,
    )

    internal constructor(
        dailyReports: Flow<List<DailyReport>>,
        expenseVisibilityRecords: Flow<List<ExpenseVisibilityRecord>>,
    ) : this(
        observeDailyReports = { dailyReports },
        observeExpenseVisibilityRecords = { expenseVisibilityRecords },
    )

    fun observe(
        request: BusinessMetricSnapshotRequest,
    ): Flow<BusinessMetricSnapshotResult> = combine(
        observeDailyReports(),
        observeExpenseVisibilityRecords(),
    ) { reports, visibility ->
        mapSnapshot(request, reports, visibility)
    }
        .distinctUntilChanged()
        .catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(
                BusinessMetricSnapshotResult.DataAccessFailure(
                    exceptionType = throwable::class.qualifiedName ?: throwable::class.simpleName.orEmpty(),
                    message = throwable.message,
                ),
            )
        }

    private fun mapSnapshot(
        request: BusinessMetricSnapshotRequest,
        reports: List<DailyReport>,
        visibility: List<ExpenseVisibilityRecord>,
    ): BusinessMetricSnapshotResult {
        val visibilityById = visibility.groupBy { it.expense.id }
        visibilityById.entries.firstOrNull { it.value.size > 1 }?.let { (expenseId, rows) ->
            val reason = if (rows.map { it.isCancelled }.distinct().size > 1) {
                BusinessMetricSnapshotMappingFailureReason.CONFLICTING_EXPENSE_VISIBILITY
            } else {
                BusinessMetricSnapshotMappingFailureReason.DUPLICATE_EXPENSE_VISIBILITY
            }
            return BusinessMetricSnapshotResult.MappingFailure(
                BusinessMetricSnapshotMappingFailure(
                    reason = reason,
                    recordId = expenseId,
                ),
            )
        }

        val mappedReports = when (
            val result = reports.map { report -> mapDailyReport(report) }.foldMappingResults()
        ) {
            is MappingResults.Failure ->
                return BusinessMetricSnapshotResult.MappingFailure(result.failure)
            is MappingResults.Success -> result.value
        }

        val mappedExpenses = when (
            val result = visibility.map { row -> mapExpenseVisibility(row) }.foldMappingResults()
        ) {
            is MappingResults.Failure ->
                return BusinessMetricSnapshotResult.MappingFailure(result.failure)
            is MappingResults.Success -> result.value
        }

        return BusinessMetricSnapshotResult.Success(
            BusinessMetricSourceSnapshot(
                period = request.period,
                reports = mappedReports.sortedWith(
                    compareBy<MetricDailyReportSource> { it.reportDate }.thenBy { it.id },
                ),
                expenseVisibility = mappedExpenses.sortedWith(
                    compareBy<MetricExpenseVisibilitySource> { it.expense.expenseDate }
                        .thenBy { it.expense.id }
                        .thenBy { it.isCancelled },
                ),
                evaluationDate = request.evaluationDate,
                calculatedAt = request.calculatedAt,
            ),
            comparisonSource = BusinessMetricComparisonSource(
                reports = reports.sortedWith(compareBy<DailyReport> { it.reportDate }.thenBy { it.id }),
                activeExpenses = visibility
                    .filterNot(ExpenseVisibilityRecord::isCancelled)
                    .map(ExpenseVisibilityRecord::expense)
                    .sortedWith(compareBy<ExpenseRecord> { it.expenseDate }.thenBy { it.id }),
                cancelledExpenseKeys = visibility
                    .asSequence()
                    .filter(ExpenseVisibilityRecord::isCancelled)
                    .map { row ->
                        ExpenseDateCategoryKey(
                            expenseDate = row.expense.expenseDate,
                            category = row.expense.category,
                        )
                    }
                    .toSet(),
            ),
        )
    }

    private fun mapDailyReport(report: DailyReport): MappingResults<MetricDailyReportSource> {
        val reportDate = try {
            LocalDate.parse(report.reportDate)
        } catch (_: DateTimeParseException) {
            return MappingResults.Failure(
                BusinessMetricSnapshotMappingFailure(
                    reason = BusinessMetricSnapshotMappingFailureReason.INVALID_REPORT_DATE,
                    recordId = report.id,
                    originalValue = report.reportDate,
                ),
            )
        }
        val sales = try {
            report.cashSales
                .let { value -> Math.addExact(value, report.cardSales) }
                .let { value -> Math.addExact(value, report.qrSales) }
                .let { value -> Math.addExact(value, report.accountsReceivableSales) }
                .let { value -> Math.addExact(value, report.otherSales) }
        } catch (_: ArithmeticException) {
            return MappingResults.Failure(
                BusinessMetricSnapshotMappingFailure(
                    reason = BusinessMetricSnapshotMappingFailureReason.AMOUNT_OVERFLOW,
                    recordId = report.id,
                ),
            )
        }
        return MappingResults.Success(
            MetricDailyReportSource(
                id = report.id,
                reportDate = reportDate,
                salesYen = sales,
                customerCount = report.customerCount.toLong(),
                legacyFoodPurchasesYen = report.foodPurchases,
                legacyAlcoholPurchasesYen = report.alcoholPurchases,
                legacyConsumablesExpenseYen = report.consumablesExpense,
                rentExpenseYen = report.rentExpense,
                communicationExpenseYen = report.communicationExpense,
                accountantFeeExpenseYen = report.accountantFeeExpense,
                electricityExpenseYen = report.electricityExpense,
                gasExpenseYen = report.gasExpense,
                waterExpenseYen = report.waterExpense,
                legacyUtilitiesExpenseYen = report.utilitiesExpense,
                miscellaneousExpenseYen = report.miscellaneousExpense,
            ),
        )
    }

    private fun mapExpenseVisibility(
        row: ExpenseVisibilityRecord,
    ): MappingResults<MetricExpenseVisibilitySource> {
        val expense = row.expense
        val expenseDate = try {
            LocalDate.parse(expense.expenseDate)
        } catch (_: DateTimeParseException) {
            return MappingResults.Failure(
                BusinessMetricSnapshotMappingFailure(
                    reason = BusinessMetricSnapshotMappingFailureReason.INVALID_EXPENSE_DATE,
                    recordId = expense.id,
                    originalValue = expense.expenseDate,
                ),
            )
        }
        return when (val category = MetricExpenseCategoryMapper.map(expense.category, expense.id)) {
            is MetricExpenseCategoryMappingResult.Success -> MappingResults.Success(
                MetricExpenseVisibilitySource(
                    expense = MetricExpenseSource(
                        id = expense.id,
                        expenseDate = expenseDate,
                        category = category.category,
                        amountYen = expense.amount,
                    ),
                    isCancelled = row.isCancelled,
                ),
            )
            is MetricExpenseCategoryMappingResult.Failure -> MappingResults.Failure(
                BusinessMetricSnapshotMappingFailure(
                    reason = BusinessMetricSnapshotMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY,
                    recordId = category.expenseId,
                    originalValue = category.originalCategory,
                    categoryFailure = category,
                ),
            )
        }
    }

    private sealed interface MappingResults<out T> {
        data class Success<T>(val value: T) : MappingResults<T>
        data class Failure(val failure: BusinessMetricSnapshotMappingFailure) : MappingResults<Nothing>
    }

    private fun <T> List<MappingResults<T>>.foldMappingResults(): MappingResults<List<T>> {
        val failure = firstOrNull { it is MappingResults.Failure } as? MappingResults.Failure
        if (failure != null) return failure
        return MappingResults.Success(
            map { (it as MappingResults.Success).value },
        )
    }
}
