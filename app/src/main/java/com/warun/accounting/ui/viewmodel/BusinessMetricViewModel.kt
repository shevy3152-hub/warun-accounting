package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.metrics.BusinessMetricSnapshotProvider
import com.warun.accounting.data.metrics.BusinessMetricSnapshotRequestFactory
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import com.warun.accounting.ui.model.toBusinessMetricUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessMetricViewModel @Inject constructor(
    private val snapshotProvider: BusinessMetricSnapshotProvider,
    private val requestFactory: BusinessMetricSnapshotRequestFactory,
) : ViewModel() {
    private val selectedPeriod = MutableStateFlow<MetricPeriod?>(null)

    val state: StateFlow<BusinessMetricUiState> = selectedPeriod
        .flatMapLatest { period ->
            if (period == null) {
                flowOf(BusinessMetricUiState.Loading)
            } else {
                snapshotProvider
                    .observe(requestFactory.create(period))
                    .map { it.toBusinessMetricUiState() }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BusinessMetricUiState.Loading,
        )

    fun selectPeriod(period: MetricPeriod) {
        if (selectedPeriod.value != period) {
            selectedPeriod.value = period
        }
    }
}
