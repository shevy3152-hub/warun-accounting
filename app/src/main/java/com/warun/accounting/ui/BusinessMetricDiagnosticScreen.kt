package com.warun.accounting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warun.accounting.domain.metrics.BusinessMetricReport
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.metrics.DiagnosticPeriodMode
import com.warun.accounting.ui.metrics.DiagnosticPeriodSelection
import com.warun.accounting.ui.metrics.diagnosticFailureMessage
import com.warun.accounting.ui.metrics.formatDiagnosticMetricValue
import com.warun.accounting.ui.metrics.parseDiagnosticPeriod
import com.warun.accounting.ui.metrics.toDiagnosticLabel
import com.warun.accounting.ui.model.BusinessMetricUiState
import com.warun.accounting.ui.viewmodel.BusinessMetricViewModel

@Composable
fun BusinessMetricDiagnosticScreen(
    onBack: () -> Unit,
    viewModel: BusinessMetricViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var modeName by rememberSaveable { mutableStateOf<String?>(null) }
    var dailyText by rememberSaveable { mutableStateOf("") }
    var monthlyText by rememberSaveable { mutableStateOf("") }
    var rangeStartText by rememberSaveable { mutableStateOf("") }
    var rangeEndText by rememberSaveable { mutableStateOf("") }
    val mode = modeName?.let { savedName ->
        runCatching { DiagnosticPeriodMode.valueOf(savedName) }.getOrNull()
    }
    val selection = remember(mode, dailyText, monthlyText, rangeStartText, rangeEndText) {
        parseDiagnosticPeriod(mode, dailyText, monthlyText, rangeStartText, rangeEndText)
    }

    LaunchedEffect(selection) {
        if (selection is DiagnosticPeriodSelection.Valid) {
            viewModel.selectPeriod(selection.period)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("経営指標（確認用）", style = MaterialTheme.typography.headlineSmall)
        Text(
            "開発確認用です。既存画面や税理士提出の表示値は変更していません。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("戻る")
        }
        PeriodSelector(
            mode = mode,
            dailyText = dailyText,
            monthlyText = monthlyText,
            rangeStartText = rangeStartText,
            rangeEndText = rangeEndText,
            onModeSelected = { modeName = it.name },
            onDailyChanged = { dailyText = it },
            onMonthlyChanged = { monthlyText = it },
            onRangeStartChanged = { rangeStartText = it },
            onRangeEndChanged = { rangeEndText = it },
        )
        when (selection) {
            DiagnosticPeriodSelection.Empty ->
                Text("期間を選択してください", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DiagnosticPeriodSelection.Invalid ->
                Text(selection.message, color = MaterialTheme.colorScheme.error)
            is DiagnosticPeriodSelection.Valid -> {
                Text("選択中: ${selection.period.toDiagnosticLabel()}")
                DiagnosticStateContent(
                    state = state,
                    selectedPeriod = selection.period,
                )
            }
        }
    }
}

@Composable
private fun PeriodSelector(
    mode: DiagnosticPeriodMode?,
    dailyText: String,
    monthlyText: String,
    rangeStartText: String,
    rangeEndText: String,
    onModeSelected: (DiagnosticPeriodMode) -> Unit,
    onDailyChanged: (String) -> Unit,
    onMonthlyChanged: (String) -> Unit,
    onRangeStartChanged: (String) -> Unit,
    onRangeEndChanged: (String) -> Unit,
) {
    DiagnosticCard {
        Text("期間", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DiagnosticPeriodMode.entries.forEach { candidate ->
            if (candidate == mode) {
                Button(onClick = { onModeSelected(candidate) }, modifier = Modifier.fillMaxWidth()) {
                    Text(candidate.label())
                }
            } else {
                OutlinedButton(onClick = { onModeSelected(candidate) }, modifier = Modifier.fillMaxWidth()) {
                    Text(candidate.label())
                }
            }
        }
        when (mode) {
            DiagnosticPeriodMode.DAILY -> DiagnosticInput("対象日 (yyyy-MM-dd)", dailyText, onDailyChanged)
            DiagnosticPeriodMode.MONTHLY -> DiagnosticInput("対象年月 (yyyy-MM)", monthlyText, onMonthlyChanged)
            DiagnosticPeriodMode.CUSTOM_RANGE -> {
                DiagnosticInput("開始日 (yyyy-MM-dd)", rangeStartText, onRangeStartChanged)
                DiagnosticInput("終了日 (yyyy-MM-dd)", rangeEndText, onRangeEndChanged)
            }
            null -> Unit
        }
    }
}

private fun DiagnosticPeriodMode.label(): String = when (this) {
    DiagnosticPeriodMode.DAILY -> "Daily"
    DiagnosticPeriodMode.MONTHLY -> "Monthly"
    DiagnosticPeriodMode.CUSTOM_RANGE -> "CustomRange"
}

@Composable
private fun DiagnosticInput(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DiagnosticStateContent(
    state: BusinessMetricUiState,
    selectedPeriod: MetricPeriod,
) {
    val visibleState = if (state is BusinessMetricUiState.Success && state.report.period != selectedPeriod) {
        BusinessMetricUiState.Loading
    } else {
        state
    }
    when (visibleState) {
        BusinessMetricUiState.Loading -> {
            CircularProgressIndicator()
            Text("読み込み中")
        }
        is BusinessMetricUiState.Success -> BusinessMetricReportContent(visibleState.report)
        is BusinessMetricUiState.MappingFailure,
        is BusinessMetricUiState.DataAccessFailure,
        is BusinessMetricUiState.AssemblyFailure,
        is BusinessMetricUiState.CalculationFailure,
        -> Text(diagnosticFailureMessage(visibleState), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BusinessMetricReportContent(report: BusinessMetricReport) {
    val metrics = listOf(
        "売上合計" to report.recordedSales,
        "支出合計（新経路）" to report.recordedExpenses,
        "参考原価" to report.referenceCost,
        "概算粗利" to report.approximateGrossProfit,
        "原価率" to report.referenceCostRate,
        "固定費相当額" to report.recordedFixedCostEquivalent,
        "損益分岐点" to report.referenceBreakEvenSales,
    )
    metrics.forEach { (label, result) ->
        DiagnosticCard {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(formatDiagnosticMetricValue(result), style = MaterialTheme.typography.headlineSmall)
            Text("availability: ${result.availability}")
            if (result.missingInputs.isNotEmpty()) {
                Text("missingInputs: ${result.missingInputs.joinToString()}")
            }
            if (result.warnings.isNotEmpty()) {
                Text("warnings: ${result.warnings.joinToString()}")
            }
        }
    }
}

@Composable
private fun DiagnosticCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}
