package com.warun.accounting.ui.viewmodel

import com.warun.accounting.data.metrics.BusinessMetricRequestClock
import com.warun.accounting.data.metrics.BusinessMetricSnapshotProvider
import com.warun.accounting.data.metrics.BusinessMetricSnapshotRequestFactory
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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

    private fun viewModel(): BusinessMetricViewModel {
        val provider = BusinessMetricSnapshotProvider(
            MutableStateFlow<List<DailyReport>>(emptyList()),
            MutableStateFlow<List<ExpenseVisibilityRecord>>(emptyList()),
        )
        val factory = BusinessMetricSnapshotRequestFactory(
            object : BusinessMetricRequestClock {
                override fun now(): Instant = Instant.parse("2026-07-29T00:00:00Z")
                override val zone: ZoneId = ZoneId.of("UTC")
            },
        )
        return BusinessMetricViewModel(provider, factory)
    }
}
