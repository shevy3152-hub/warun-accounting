package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.cancellation.ExpenseCancellationAuditItem
import com.warun.accounting.data.cancellation.ExpenseCancellationAuditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpenseCancellationAuditViewModel @Inject constructor(
    repository: ExpenseCancellationAuditRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val items: StateFlow<List<ExpenseCancellationAuditItem>> =
        savedStateHandle.getStateFlow(ReportDateArgument, "")
            .flatMapLatest { reportDate ->
                if (reportDate.isBlank()) {
                    flowOf(emptyList())
                } else {
                    repository.observeByExpenseDate(reportDate)
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    private companion object {
        const val ReportDateArgument = "reportDate"
    }
}
