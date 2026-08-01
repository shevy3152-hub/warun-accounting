package com.warun.accounting.ui.viewmodel

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.data.metrics.BusinessMetricRequestClock
import com.warun.accounting.data.metrics.BusinessMetricSnapshotProvider
import com.warun.accounting.data.metrics.BusinessMetricSnapshotRequestFactory
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessMetricViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoadingAndEmitsSuccessAfterPeriodSelection() = runTest(dispatcher) {
        val viewModel = viewModel()
        val collector = launch { viewModel.state.collect {} }

        assertTrue(viewModel.state.value is BusinessMetricUiState.Loading)
        viewModel.selectPeriod(MetricPeriod.Daily(LocalDate.parse("2026-07-01")))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is BusinessMetricUiState.Success)
        collector.cancel()
    }

    @Test
    fun selectingSamePeriodDoesNotCreateASecondRequest() = runTest(dispatcher) {
        val viewModel = viewModel()
        val collector = launch { viewModel.state.collect {} }
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-01"))

        viewModel.selectPeriod(period)
        advanceUntilIdle()
        val first = viewModel.state.value
        viewModel.selectPeriod(period)
        advanceUntilIdle()

        assertTrue(viewModel.state.value === first || viewModel.state.value == first)
        collector.cancel()
    }

    @Test
    fun successPreservesNullAvailabilityAndMissingInputs() = runTest(dispatcher) {
        val viewModel = viewModel()
        val collector = launch { viewModel.state.collect {} }

        viewModel.selectPeriod(MetricPeriod.Daily(LocalDate.parse("2026-07-01")))
        advanceUntilIdle()

        val success = viewModel.state.value as BusinessMetricUiState.Success
        assertNull(success.report.recordedExpenses.value)
        assertEquals(MetricAvailability.INSUFFICIENT_DATA, success.report.recordedExpenses.availability)
        assertFalse(success.report.recordedExpenses.missingInputs.isEmpty())
        collector.cancel()
    }

    @Test
    fun mappingFailureRemainsDistinctAndRetainsOriginalCategory() = runTest(dispatcher) {
        val viewModel = viewModel(
            visibility = MutableStateFlow(
                listOf(visibility("expense-1", "not-a-category", false)),
            ),
        )
        val collector = launch { viewModel.state.collect {} }

        viewModel.selectPeriod(MetricPeriod.Daily(LocalDate.parse("2026-07-01")))
        advanceUntilIdle()

        val failure = viewModel.state.value as BusinessMetricUiState.MappingFailure
        assertEquals("not-a-category", failure.failure.originalValue)
        assertFalse(viewModel.state.value is BusinessMetricUiState.Success)
        collector.cancel()
    }

    @Test
    fun dataAccessFailureRemainsDistinctFromSuccess() = runTest(dispatcher) {
        val failingReports = flow<List<DailyReport>> {
            throw IllegalStateException("database unavailable")
        }
        val provider = BusinessMetricSnapshotProvider(
            failingReports,
            MutableStateFlow<List<ExpenseVisibilityRecord>>(emptyList()),
        )
        val viewModel = viewModel(provider)
        val collector = launch { viewModel.state.collect {} }

        viewModel.selectPeriod(MetricPeriod.Daily(LocalDate.parse("2026-07-01")))
        advanceUntilIdle()

        val failure = viewModel.state.value as BusinessMetricUiState.DataAccessFailure
        assertEquals("java.lang.IllegalStateException", failure.exceptionType)
        assertFalse(viewModel.state.value is BusinessMetricUiState.Success)
        collector.cancel()
    }

    @Test
    fun dailyMonthlyAndCustomRangeSelectionsRecalculateForSelectedPeriod() = runTest(dispatcher) {
        val viewModel = viewModel()
        val collector = launch { viewModel.state.collect {} }
        val daily = MetricPeriod.Daily(LocalDate.parse("2026-07-01"))
        val monthly = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val custom = MetricPeriod.CustomRange(
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-03"),
        )

        viewModel.selectPeriod(daily)
        advanceUntilIdle()
        assertEquals(daily, (viewModel.state.value as BusinessMetricUiState.Success).report.period)
        viewModel.selectPeriod(monthly)
        advanceUntilIdle()
        assertEquals(monthly, (viewModel.state.value as BusinessMetricUiState.Success).report.period)
        viewModel.selectPeriod(custom)
        advanceUntilIdle()
        assertEquals(custom, (viewModel.state.value as BusinessMetricUiState.Success).report.period)
        collector.cancel()
    }

    @Test
    fun providerReemitRecalculatesWithoutChangingSelectedPeriod() = runTest(dispatcher) {
        val reports = MutableStateFlow(listOf(report(sales = 0L)))
        val viewModel = viewModel(reports = reports)
        val collector = launch { viewModel.state.collect {} }
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-01"))

        viewModel.selectPeriod(period)
        advanceUntilIdle()
        val initial = viewModel.state.value as BusinessMetricUiState.Success
        assertEquals(0L, initial.report.recordedSales.value?.yen)

        reports.value = listOf(report(sales = 1_000L))
        advanceUntilIdle()

        val updated = viewModel.state.value as BusinessMetricUiState.Success
        assertEquals(period, updated.report.period)
        assertEquals(1_000L, updated.report.recordedSales.value?.yen)
        collector.cancel()
    }

    @Test
    fun delayedOldPeriodCannotOverwriteLatestPeriod() = runTest(dispatcher) {
        val viewModel = viewModel()
        val collector = launch { viewModel.state.collect {} }
        val daily = MetricPeriod.Daily(LocalDate.parse("2026-07-01"))
        val latest = MetricPeriod.CustomRange(
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-03"),
        )

        viewModel.selectPeriod(daily)
        viewModel.selectPeriod(latest)
        advanceUntilIdle()

        assertEquals(latest, (viewModel.state.value as BusinessMetricUiState.Success).report.period)
        collector.cancel()
    }

    private fun viewModel(
        provider: BusinessMetricSnapshotProvider? = null,
        reports: MutableStateFlow<List<DailyReport>>? = null,
        visibility: MutableStateFlow<List<ExpenseVisibilityRecord>> = MutableStateFlow(emptyList()),
    ): BusinessMetricViewModel {
        val resolvedProvider = provider
            ?: reports?.let { BusinessMetricSnapshotProvider(it, visibility) }
            ?: BusinessMetricSnapshotProvider(
                MutableStateFlow<List<DailyReport>>(emptyList()),
                visibility,
            )
        val factory = BusinessMetricSnapshotRequestFactory(
            object : BusinessMetricRequestClock {
                override fun now(): Instant = Instant.parse("2026-07-29T00:00:00Z")
                override val zone: ZoneId = ZoneId.of("UTC")
            },
        )
        return BusinessMetricViewModel(resolvedProvider, factory)
    }

    private fun provider(
        reports: MutableStateFlow<List<DailyReport>> = MutableStateFlow(emptyList()),
        visibility: MutableStateFlow<List<ExpenseVisibilityRecord>> = MutableStateFlow(emptyList()),
    ) = BusinessMetricSnapshotProvider(reports, visibility)

    private fun report(
        id: String = "report-1",
        reportDate: String = "2026-07-01",
        sales: Long,
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
}
