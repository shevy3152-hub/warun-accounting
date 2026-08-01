package com.warun.accounting.data.metrics

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.domain.metrics.MetricExpenseCategory
import com.warun.accounting.domain.metrics.MetricPeriod
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessMetricSnapshotProviderTest {
    @Test
    fun mapsDailyReportWithoutExpenses() = runTest {
        val result = provider(
            reports = listOf(report()),
            visibility = emptyList(),
        ).observe(request()).first()

        val snapshot = assertSuccess(result)
        assertEquals(1, snapshot.reports.size)
        assertEquals(LocalDate.parse("2026-07-01"), snapshot.reports.single().reportDate)
        assertEquals(1_500L, snapshot.reports.single().salesYen)
        assertTrue(snapshot.expenseVisibility.isEmpty())
    }

    @Test
    fun mapsAllConfirmedCategoriesThroughStrictMapper() = runTest {
        val rows = listOf(
            visibility("food", "food_purchase", false),
            visibility("alcohol", "alcohol_purchase", false),
            visibility("consumables", "consumables", false),
            visibility("other", "other_expense", false),
            visibility("vehicle", "vehicle_transport", false),
        )

        val snapshot = assertSuccess(provider(emptyList(), rows).observe(request()).first())
        assertEquals(
            setOf(
                MetricExpenseCategory.FOOD_PURCHASE,
                MetricExpenseCategory.ALCOHOL_PURCHASE,
                MetricExpenseCategory.CONSUMABLES,
                MetricExpenseCategory.OTHER_EXPENSE,
                MetricExpenseCategory.VEHICLE_TRANSPORT,
            ),
            snapshot.expenseVisibility.map { it.expense.category }.toSet(),
        )
    }

    @Test
    fun keepsActiveAndCancelledRowsDistinct() = runTest {
        val rows = listOf(
            visibility("active", "other_expense", false),
            visibility("cancelled", "other_expense", true),
        )

        val snapshot = assertSuccess(provider(emptyList(), rows).observe(request()).first())
        assertEquals(listOf(false, true), snapshot.expenseVisibility.map { it.isCancelled })
    }

    @Test
    fun inputOrderDoesNotChangeSnapshotOrder() = runTest {
        val first = provider(
            reports = listOf(report("b", "2026-07-02"), report("a", "2026-07-01")),
            visibility = listOf(
                visibility("b", "other_expense", false, "2026-07-02"),
                visibility("a", "food_purchase", false, "2026-07-01"),
            ),
        ).observe(request()).first()
        val second = provider(
            reports = listOf(report("a", "2026-07-01"), report("b", "2026-07-02")),
            visibility = listOf(
                visibility("a", "food_purchase", false, "2026-07-01"),
                visibility("b", "other_expense", false, "2026-07-02"),
            ),
        ).observe(request()).first()

        assertEquals(first, second)
    }

    @Test
    fun unknownCategoryFailsWithoutPartialSnapshot() = runTest {
        val result = provider(
            reports = listOf(report()),
            visibility = listOf(visibility("expense-1", "legacy_food", false)),
        ).observe(request()).first()

        val failure = assertMappingFailure(result)
        assertEquals(BusinessMetricSnapshotMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY, failure.reason)
        assertEquals("legacy_food", failure.originalValue)
        assertEquals("expense-1", failure.recordId)
        assertTrue(failure.categoryFailure != null)
    }

    @Test
    fun invalidDateFailsWithoutCorrection() = runTest {
        val result = provider(
            reports = listOf(report(reportDate = "2026/07/01")),
            visibility = emptyList(),
        ).observe(request()).first()

        val failure = assertMappingFailure(result)
        assertEquals(BusinessMetricSnapshotMappingFailureReason.INVALID_REPORT_DATE, failure.reason)
        assertEquals("2026/07/01", failure.originalValue)
    }

    @Test
    fun duplicateVisibilityStateFailsInsteadOfGuessing() = runTest {
        val result = provider(
            reports = emptyList(),
            visibility = listOf(
                visibility("expense-1", "other_expense", false),
                visibility("expense-1", "other_expense", true),
            ),
        ).observe(request()).first()

        val failure = assertMappingFailure(result)
        assertEquals(BusinessMetricSnapshotMappingFailureReason.CONFLICTING_EXPENSE_VISIBILITY, failure.reason)
        assertEquals("expense-1", failure.recordId)
    }

    @Test
    fun dailyReportUpdateReemitsSnapshot() = runTest {
        val reports = MutableStateFlow(listOf(report(sales = 1_000L)))
        val flow = provider(reports, MutableStateFlow(emptyList())).observe(request())
        val updated = async { flow.drop(1).first() }
        runCurrent()

        reports.value = listOf(report(sales = 2_000L))
        assertEquals(2_000L, assertSuccess(updated.await()).reports.single().salesYen)
    }

    @Test
    fun expenseUpdateReemitsSnapshot() = runTest {
        val visibility = MutableStateFlow<List<ExpenseVisibilityRecord>>(emptyList())
        val flow = provider(
            MutableStateFlow<List<DailyReport>>(emptyList()),
            visibility,
        ).observe(request())
        val updated = async { flow.drop(1).first() }
        runCurrent()

        visibility.value = listOf(visibility("expense-1", "other_expense", false))
        assertEquals(1, assertSuccess(updated.await()).expenseVisibility.size)
    }

    @Test
    fun cancellationUpdateReemitsWithCancelledState() = runTest {
        val visibility = MutableStateFlow(
            listOf(visibility("expense-1", "other_expense", false)),
        )
        val flow = provider(
            MutableStateFlow<List<DailyReport>>(emptyList()),
            visibility,
        ).observe(request())
        val updated = async { flow.drop(1).first() }
        runCurrent()

        visibility.value = listOf(visibility("expense-1", "other_expense", true))
        assertTrue(assertSuccess(updated.await()).expenseVisibility.single().isCancelled)
    }

    @Test
    fun mappingFailureRecoversWhenInputBecomesValid() = runTest {
        val visibility = MutableStateFlow(
            listOf(visibility("expense-1", "unknown", false)),
        )
        val flow = provider(
            MutableStateFlow<List<DailyReport>>(emptyList()),
            visibility,
        ).observe(request())
        val first = async { flow.first() }
        runCurrent()
        val initial = first.await()
        val recovered = async { flow.drop(1).first() }
        runCurrent()

        visibility.value = listOf(visibility("expense-1", "other_expense", false))
        assertTrue(initial is BusinessMetricSnapshotResult.MappingFailure)
        assertTrue(recovered.await() is BusinessMetricSnapshotResult.Success)
    }

    @Test
    fun sourceExceptionBecomesDataAccessFailure() = runTest {
        val failingReports = flow<List<DailyReport>> {
            throw IllegalStateException("source unavailable")
        }

        val result = BusinessMetricSnapshotProvider(
            failingReports,
            MutableStateFlow<List<ExpenseVisibilityRecord>>(emptyList()),
        ).observe(request()).first()

        val failure = result as BusinessMetricSnapshotResult.DataAccessFailure
        assertEquals("java.lang.IllegalStateException", failure.exceptionType)
        assertEquals("source unavailable", failure.message)
    }

    private fun provider(
        reports: List<DailyReport>,
        visibility: List<ExpenseVisibilityRecord>,
    ) = provider(MutableStateFlow(reports), MutableStateFlow(visibility))

    private fun provider(
        reports: MutableStateFlow<List<DailyReport>>,
        visibility: MutableStateFlow<List<ExpenseVisibilityRecord>>,
    ) = BusinessMetricSnapshotProvider(reports, visibility)

    private fun request() = BusinessMetricSnapshotRequest(
        period = MetricPeriod.Daily(LocalDate.parse("2026-07-01")),
        evaluationDate = LocalDate.parse("2026-07-29"),
        calculatedAt = Instant.parse("2026-07-29T00:00:00Z"),
    )

    private fun report(
        id: String = "report-1",
        reportDate: String = "2026-07-01",
        sales: Long = 1_500L,
    ) = DailyReport(
        id = id,
        reportDate = reportDate,
        status = "completed",
        authorName = null,
        cashSales = sales,
        cardSales = 0L,
        qrSales = 0L,
        accountsReceivableSales = 0L,
        otherSales = 0L,
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
        customerCount = 1,
        groupCount = 1,
        memo = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun visibility(
        id: String,
        category: String,
        isCancelled: Boolean,
        expenseDate: String = "2026-07-01",
    ) = ExpenseVisibilityRecord(
        expense = ExpenseRecord(
            id = id,
            expenseDate = expenseDate,
            category = category,
            supplierName = null,
            amount = 100L,
            paymentMethod = null,
            memo = null,
            receiptId = null,
            sourceType = "manual",
            createdAt = 1L,
            updatedAt = 1L,
        ),
        isCancelled = isCancelled,
    )

    private fun assertSuccess(result: BusinessMetricSnapshotResult) =
        (result as BusinessMetricSnapshotResult.Success).snapshot

    private fun assertMappingFailure(result: BusinessMetricSnapshotResult) =
        (result as BusinessMetricSnapshotResult.MappingFailure).failure
}
