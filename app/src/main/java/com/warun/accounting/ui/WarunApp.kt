@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.warun.accounting.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.ui.input.DateInputTextField
import com.warun.accounting.ui.model.BusinessAnalysisSummary
import com.warun.accounting.ui.model.breakEvenStatusMessage
import com.warun.accounting.ui.model.buildBusinessAnalysisSummary
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.ui.model.formatBusinessRate
import com.warun.accounting.ui.receipt.ReceiptCameraScreen
import com.warun.accounting.ui.receipt.ReceiptCaptureStartCoordinator
import com.warun.accounting.ui.receipt.ReceiptCaptureResultKey
import com.warun.accounting.ui.receipt.ReceiptImageImportUiState
import com.warun.accounting.ui.receipt.ReceiptImageImportViewModel
import com.warun.accounting.ui.receipt.ReceiptOcrPanel
import com.warun.accounting.ui.evidence.EvidenceImageDialog
import com.warun.accounting.ui.receipt.ReceiptOcrViewModel
import com.warun.accounting.ui.receipt.ReceiptOcrMergeAction
import com.warun.accounting.ui.receipt.captureOrNull
import com.warun.accounting.ui.receipt.consumeReceiptCaptureResult
import com.warun.accounting.ui.receipt.journalProtectedReceiptImageStore
import com.warun.accounting.ui.receipt.planReceiptOcrMerge
import com.warun.accounting.ui.receipt.toSavedValue
import com.warun.accounting.ui.util.toYen
import com.warun.accounting.util.calculateCashBalance
import com.warun.accounting.util.cashExpenseAmount
import com.warun.accounting.util.expenseAmount
import com.warun.accounting.util.isSupportedPaymentMethod
import com.warun.accounting.util.normalizePaymentMethod
import com.warun.accounting.util.paymentMethodOptions
import com.warun.accounting.util.preferredCashExpenseAmount
import com.warun.accounting.util.preferredExpenseAmount
import com.warun.accounting.ui.viewmodel.AppSettingsInput
import com.warun.accounting.ui.viewmodel.DailyReportInput
import com.warun.accounting.ui.viewmodel.DashboardViewModel
import com.warun.accounting.evidence.EvidenceFinalizationAfterAccountingSaveException
import com.warun.accounting.ui.viewmodel.ExpenseInput
import com.warun.accounting.ui.viewmodel.InputStateViewModel
import com.warun.accounting.ui.viewmodel.ReceiptInput
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : AppDestination("home", "ホーム", Icons.Outlined.Dashboard)
    data object ReportEntry : AppDestination("report_entry", "日報入力", Icons.Outlined.EditNote)
    data object Receipt : AppDestination("receipt", "レシート", Icons.Outlined.ReceiptLong)
    data object Balance : AppDestination("balance", "収支確認", Icons.Outlined.Assessment)
    data object MonthlyOrganization : AppDestination("monthly_organization", "月別整理", Icons.Outlined.ListAlt)
    data object ReportList : AppDestination("report_list", "日報一覧", Icons.Outlined.ListAlt)
    data object Submit : AppDestination("submit", "税理士へ提出", Icons.Outlined.Download)
    data object Settings : AppDestination("settings", "設定", Icons.Outlined.Settings)
}

private val destinations = listOf(
    AppDestination.Home,
    AppDestination.ReportEntry,
    AppDestination.Receipt,
    AppDestination.Balance,
    AppDestination.MonthlyOrganization,
    AppDestination.ReportList,
    AppDestination.Submit,
    AppDestination.Settings
)

private val phoneDestinations = listOf(
    AppDestination.Home,
    AppDestination.ReportEntry,
    AppDestination.Receipt,
    AppDestination.Balance,
    AppDestination.MonthlyOrganization
)

internal object ReportRoutes {
    const val ReportDateArg = "reportDate"
    const val Detail = "report_detail/{reportDate}"
    const val Entry = "report_entry/{reportDate}"

    fun detail(reportDate: String): String = "report_detail/$reportDate"
    fun entry(reportDate: String): String = "report_entry/$reportDate"
}

internal data class TopLevelNavigationPolicy(
    val saveState: Boolean,
    val restoreState: Boolean
)

internal val topLevelNavigationPolicy = TopLevelNavigationPolicy(
    saveState = false,
    restoreState = false
)

internal fun topLevelRouteForLabel(label: String): String? =
    destinations.firstOrNull { it.label == label }?.route

private object ReceiptRoutes {
    const val Camera = "receipt_camera"
}

private const val FoodPurchaseCategory = ExpenseCategory.FoodPurchase
private const val AlcoholPurchaseCategory = ExpenseCategory.AlcoholPurchase
private const val ConsumablesCategory = ExpenseCategory.Consumables
private const val OtherExpenseCategory = ExpenseCategory.OtherExpense
private const val VehicleTransportCategory = ExpenseCategory.VehicleTransport

private data class SupplierCandidate(
    val name: String,
    val category: String? = null,
    val paymentMethod: String = "",
    val record: SupplierCandidateRecord? = null
)

private val foodSupplierCandidates = listOf(
    SupplierCandidate("トキノ屋", FoodPurchaseCategory, "掛け"),
    SupplierCandidate("バロー", FoodPurchaseCategory, "現金"),
    SupplierCandidate("ピアゴ", FoodPurchaseCategory, "現金"),
    SupplierCandidate("アミカ", FoodPurchaseCategory, "現金"),
    SupplierCandidate("他", FoodPurchaseCategory)
)

private val alcoholSupplierCandidates = listOf(
    SupplierCandidate("サカツ", AlcoholPurchaseCategory, "掛け"),
    SupplierCandidate("まるみや酒店", AlcoholPurchaseCategory, "現金"),
    SupplierCandidate("中島酒店", AlcoholPurchaseCategory, "現金"),
    SupplierCandidate("他", AlcoholPurchaseCategory)
)

private val consumablesCandidates = listOf(
    SupplierCandidate("ドラックアオキ", ConsumablesCategory, "現金"),
    SupplierCandidate("DCMカーマ", ConsumablesCategory, "現金"),
    SupplierCandidate("PROsite", ConsumablesCategory, "現金"),
    SupplierCandidate("他", ConsumablesCategory)
)

private val vehicleTransportCandidates = listOf(
    SupplierCandidate("ENEOS", VehicleTransportCategory, "現金"),
    SupplierCandidate("他", VehicleTransportCategory)
)

private fun fixedSupplierCandidatesFor(category: String): List<SupplierCandidate> =
    when (category) {
        FoodPurchaseCategory -> foodSupplierCandidates
        AlcoholPurchaseCategory -> alcoholSupplierCandidates
        ConsumablesCategory -> consumablesCandidates
        VehicleTransportCategory -> vehicleTransportCandidates
        else -> listOf(SupplierCandidate("他", category))
    }

private fun supplierCandidatesFor(
    category: String,
    savedCandidates: List<SupplierCandidateRecord>
): List<SupplierCandidate> {
    val fixedCandidates = fixedSupplierCandidatesFor(category)
    val fixedNames = fixedCandidates.map { it.name }.toSet()
    val userCandidates = savedCandidates
        .filter { it.category == category && !it.isHidden && it.name !in fixedNames }
        .map { record ->
            SupplierCandidate(
                name = record.name,
                category = record.category,
                paymentMethod = record.paymentMethod.orEmpty(),
                record = record
            )
        }
    return fixedCandidates + userCandidates
}

private fun receiptParserStoreNames(savedCandidates: List<SupplierCandidateRecord>): List<String> {
    val fixedNames = (
        foodSupplierCandidates + alcoholSupplierCandidates + consumablesCandidates +
            vehicleTransportCandidates
        ).map { it.name }
        .filter { it != "他" }
    val savedNames = savedCandidates
        .filterNot { it.isHidden }
        .map { it.name }
    return (fixedNames + savedNames).distinct()
}

private fun expenseCategoryLabel(category: String): String =
    when (category) {
        FoodPurchaseCategory -> "食材仕入"
        AlcoholPurchaseCategory -> "酒類仕入"
        ConsumablesCategory -> "消耗品費"
        OtherExpenseCategory -> "その他支出"
        VehicleTransportCategory -> "車両・交通費"
        else -> category
    }

private fun expenseCategoryTotal(expenses: List<ExpenseRecord>, reportDate: String, category: String): Long =
    expenses.filter { it.expenseDate == reportDate && it.category == category }.expenseAmount()

private fun detailExpense(input: DailyReportInput, expenses: List<ExpenseRecord>, category: String): Long =
    expenseCategoryTotal(expenses, input.reportDate, category)

private fun cashExpenseCategoryTotal(expenses: List<ExpenseRecord>, reportDate: String, category: String): Long =
    expenses.filter { it.expenseDate == reportDate && it.category == category }.cashExpenseAmount()

private fun cashDetailExpense(input: DailyReportInput, expenses: List<ExpenseRecord>, category: String): Long =
    cashExpenseCategoryTotal(expenses, input.reportDate, category)

private fun DailyReportInput.utilityBreakdownExpenseTotal(): Long =
    electricityExpense.toInputLong() + gasExpense.toInputLong() + waterExpense.toInputLong()

private fun DailyReportInput.utilityExpenseTotal(): Long = utilityBreakdownExpenseTotal()

private fun DailyReportInput.isLegacyUtilityExpense(): Boolean =
    utilitiesExpense.toInputLong() > 0L && electricityExpense.toInputLong() == 0L && gasExpense.toInputLong() == 0L && waterExpense.toInputLong() == 0L

private fun DailyReportInput.withUtilityCompatibility(
    cleanInput: DailyReportInput,
    utilityFieldsEdited: Boolean
): DailyReportInput {
    val utilitiesTotal = if (cleanInput.isLegacyUtilityExpense() && !utilityFieldsEdited) {
        cleanInput.utilitiesExpense.toInputLong()
    } else {
        utilityBreakdownExpenseTotal()
    }
    return copy(utilitiesExpense = utilitiesTotal.toString())
}

private fun expensesWithoutReportsTotal(reports: List<DailyReport>, expenses: List<ExpenseRecord>): Long {
    val reportDates = reports.map { it.reportDate }.toSet()
    return expenses.filterNot { it.expenseDate in reportDates }.expenseAmount()
}

private fun cashExpensesWithoutReportsTotal(reports: List<DailyReport>, expenses: List<ExpenseRecord>): Long {
    val reportDates = reports.map { it.reportDate }.toSet()
    return expenses.filterNot { it.expenseDate in reportDates }.cashExpenseAmount()
}

private fun List<ExpenseRecord>.withDraftExpensePreview(draft: ExpenseInput?): List<ExpenseRecord> {
    if (draft == null) return this
    val amount = draft.amount.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val withoutEditingTarget = if (draft.id.isBlank()) this else filterNot { it.id == draft.id }
    if (amount <= 0L || draft.expenseDate.isBlank() || draft.category.isBlank()) return withoutEditingTarget
    return withoutEditingTarget + ExpenseRecord(
        id = draft.id.ifBlank { "__draft_expense__" },
        expenseDate = draft.expenseDate,
        category = draft.category,
        supplierName = draft.supplierName.ifBlank { null },
        amount = amount,
        paymentMethod = normalizePaymentMethod(draft.paymentMethod),
        memo = draft.memo.ifBlank { null },
        receiptId = draft.receiptId.ifBlank { null },
        sourceType = draft.sourceType,
        createdAt = draft.createdAt ?: Long.MAX_VALUE,
        updatedAt = Long.MAX_VALUE
    )
}

@Composable
fun WarunApp(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceRecoveryNotice by viewModel.evidenceRecoveryNotice.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Home.route
    var liveSidebarSummary by remember { mutableStateOf<SidebarSummaryOverride?>(null) }
    val reportEntryNavigationGuard = remember { ReportEntryNavigationGuard() }
    var pendingNavigationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            journalProtectedReceiptImageStore(context).cleanupOrphanedImportFiles()
        }
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute != AppDestination.ReportEntry.route && currentRoute != ReportRoutes.Entry) {
            liveSidebarSummary = null
        }
    }

    fun requestGuardedNavigation(action: () -> Unit) {
        if (reportEntryNavigationGuard.isActive && reportEntryNavigationGuard.hasUnsavedChanges) {
            pendingNavigationAction = action
        } else {
            action()
        }
    }

    fun runPendingNavigation(action: () -> Unit) {
        pendingNavigationAction = null
        action()
    }

    val guardedNavigateSingleTop: (String) -> Unit = { route ->
        requestGuardedNavigation { navController.navigateSingleTop(route) }
    }
    val guardedNavigate: (String) -> Unit = { route ->
        requestGuardedNavigation { navController.navigate(route) }
    }
    val guardedPopBackStack: () -> Unit = {
        requestGuardedNavigation { navController.popBackStack() }
    }


    pendingNavigationAction?.let { action ->
        val isSaving = reportEntryNavigationGuard.isSaving
        AlertDialog(
            onDismissRequest = {
                if (!isSaving) pendingNavigationAction = null
            },
            title = { Text("未保存の内容があります") },
            text = { Text("この日報には未保存の変更があります。移動する前に保存できます。") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            reportEntryNavigationGuard.saveDraftAndContinue?.invoke {
                                runPendingNavigation(action)
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSaving) "保存中…" else "下書き保存")
                    }
                    Button(
                        onClick = {
                            reportEntryNavigationGuard.saveCompletedAndContinue?.invoke {
                                runPendingNavigation(action)
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSaving) "保存中…" else "入力完了で保存")
                    }
                    OutlinedButton(
                        onClick = {
                            reportEntryNavigationGuard.discardChanges?.invoke()
                            runPendingNavigation(action)
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存せずに続行")
                    }
                    TextButton(
                        onClick = { pendingNavigationAction = null },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("戻る")
                    }
                }
            },
            dismissButton = {}
        )
    }
    if (evidenceRecoveryNotice.shouldNotify) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEvidenceRecoveryNotice,
            title = { Text("レシート画像の復旧を確認してください") },
            text = {
                Text(
                    "一部のレシート画像を復旧できませんでした。支出データは保持されています。" +
                        "未解決の項目は${evidenceRecoveryNotice.issueCount}件です。"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissEvidenceRecoveryNotice) {
                    Text("確認")
                }
            }
        )
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isPhone = maxWidth < 720.dp
        if (isPhone) {
            Scaffold(
                bottomBar = {
                    BottomNavigation(
                        currentRoute = currentRoute,
                        onSelect = guardedNavigateSingleTop
                    )
                }
            ) { padding ->
                AppNavHost(
                    uiState = uiState,
                    evidenceRecoveryIssueCount = evidenceRecoveryNotice.issueCount,
                    viewModel = viewModel,
                    navController = navController,
                    contentPadding = padding,
                    onNavigateSingleTop = guardedNavigateSingleTop,
                    onNavigate = guardedNavigate,
                    onPopBackStack = guardedPopBackStack,
                    reportEntryNavigationGuard = reportEntryNavigationGuard,
                    onReportEntrySummaryChange = { liveSidebarSummary = it }
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                SideNavigation(
                    uiState = uiState,
                    currentRoute = currentRoute,
                    liveSummary = liveSidebarSummary,
                    onSelect = guardedNavigateSingleTop
                )
                AppNavHost(
                    uiState = uiState,
                    evidenceRecoveryIssueCount = evidenceRecoveryNotice.issueCount,
                    viewModel = viewModel,
                    navController = navController,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                    onNavigateSingleTop = guardedNavigateSingleTop,
                    onNavigate = guardedNavigate,
                    onPopBackStack = guardedPopBackStack,
                    reportEntryNavigationGuard = reportEntryNavigationGuard,
                    onReportEntrySummaryChange = { liveSidebarSummary = it }
                )
            }
        }
    }

}
@Composable
private fun AppNavHost(
    uiState: DashboardUiState,
    evidenceRecoveryIssueCount: Int,
    viewModel: DashboardViewModel,
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onNavigateSingleTop: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onPopBackStack: () -> Unit,
    reportEntryNavigationGuard: ReportEntryNavigationGuard,
    onReportEntrySummaryChange: (SidebarSummaryOverride?) -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        composable(AppDestination.Home.route) {
            HomeScreen(
                uiState = uiState,
                evidenceRecoveryIssueCount = evidenceRecoveryIssueCount,
                onNavigate = onNavigateSingleTop
            )
        }
        composable(AppDestination.ReportEntry.route) { backStackEntry ->
            val inputStateViewModel: InputStateViewModel = hiltViewModel(backStackEntry)
            var capturedReceipt by remember { mutableStateOf<ReceiptCaptureResult?>(null) }
            LaunchedEffect(backStackEntry) {
                consumeReceiptCaptureResult(backStackEntry.savedStateHandle)?.let { capturedReceipt = it }
            }
            ReportEntryScreen(
                uiState = uiState,
                onSaveReport = viewModel::saveDailyReportWithExpenseAndEvidence,
                onSaveExpense = viewModel::saveExpenseWithEvidence,
                onDeleteExpense = viewModel::deleteExpense,
                onAddSupplierCandidate = viewModel::addSupplierCandidate,
                onHideSupplierCandidate = viewModel::hideSupplierCandidate,
                onOpenReceiptCamera = { navController.navigate(ReceiptRoutes.Camera) },
                capturedReceipt = capturedReceipt,
                onCaptureReceived = { capturedReceipt = it },
                onCaptureCleared = { capturedReceipt = null },
                navigationGuard = reportEntryNavigationGuard,
                onRequestBack = onPopBackStack,
                onLiveSummaryChange = onReportEntrySummaryChange,
                inputStateViewModel = inputStateViewModel
            )
        }
        composable(AppDestination.Receipt.route) { backStackEntry ->
            var capturedReceipt by remember { mutableStateOf<ReceiptCaptureResult?>(null) }
            LaunchedEffect(backStackEntry) {
                consumeReceiptCaptureResult(backStackEntry.savedStateHandle)?.let { capturedReceipt = it }
            }
            ReceiptScreen(
                uiState = uiState,
                onNavigate = onNavigateSingleTop,
                onSaveReceipt = viewModel::saveReceipt,
                onOpenReceiptCamera = { navController.navigate(ReceiptRoutes.Camera) },
                capturedReceipt = capturedReceipt,
                onCaptureCleared = { capturedReceipt = null }
            )
        }
        composable(ReceiptRoutes.Camera) {
            ReceiptCameraScreen(
                onCaptured = { result ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(ReceiptCaptureResultKey, result.toSavedValue())
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(AppDestination.Balance.route) {
            BalanceScreen(uiState = uiState)
        }
        composable(AppDestination.MonthlyOrganization.route) {
            MonthlyOrganizationScreen(
                uiState = uiState,
                onMarkSubmitted = viewModel::markMonthSubmitted,
                onOpenSubmit = {
                    onNavigateSingleTop(AppDestination.Submit.route)
                }
            )
        }
        composable(AppDestination.ReportList.route) {
            ReportListScreen(
                uiState = uiState,
                onOpenDate = { reportDate ->
                    onNavigate(ReportRoutes.detail(reportDate))
                }
            )
        }
        composable(
            route = ReportRoutes.Detail,
            arguments = listOf(navArgument(ReportRoutes.ReportDateArg) { type = NavType.StringType })
        ) { backStackEntry ->
            val reportDate = backStackEntry.arguments?.getString(ReportRoutes.ReportDateArg).orEmpty()
            ReportDetailScreen(
                uiState = uiState,
                reportDate = reportDate,
                onBack = onPopBackStack,
                onEntry = { onNavigate(ReportRoutes.entry(reportDate)) }
            )
        }
        composable(
            route = ReportRoutes.Entry,
            arguments = listOf(navArgument(ReportRoutes.ReportDateArg) { type = NavType.StringType })
        ) { backStackEntry ->
            val inputStateViewModel: InputStateViewModel = hiltViewModel(backStackEntry)
            var capturedReceipt by remember { mutableStateOf<ReceiptCaptureResult?>(null) }
            LaunchedEffect(backStackEntry) {
                consumeReceiptCaptureResult(backStackEntry.savedStateHandle)?.let { capturedReceipt = it }
            }
            ReportEntryScreen(
                uiState = uiState,
                initialDate = backStackEntry.arguments?.getString(ReportRoutes.ReportDateArg),
                onSaveReport = viewModel::saveDailyReportWithExpenseAndEvidence,
                onSaveExpense = viewModel::saveExpenseWithEvidence,
                onDeleteExpense = viewModel::deleteExpense,
                onAddSupplierCandidate = viewModel::addSupplierCandidate,
                onHideSupplierCandidate = viewModel::hideSupplierCandidate,
                onOpenReceiptCamera = { navController.navigate(ReceiptRoutes.Camera) },
                capturedReceipt = capturedReceipt,
                onCaptureReceived = { capturedReceipt = it },
                onCaptureCleared = { capturedReceipt = null },
                navigationGuard = reportEntryNavigationGuard,
                onRequestBack = onPopBackStack,
                onLiveSummaryChange = onReportEntrySummaryChange,
                inputStateViewModel = inputStateViewModel
            )
        }
        composable(AppDestination.Submit.route) {
            SubmitScreen(
                uiState = uiState,
                onMarkSubmitted = viewModel::markMonthSubmitted,
                onOpenMonthlyOrganization = {
                    onNavigateSingleTop(AppDestination.MonthlyOrganization.route)
                }
            )
        }
        composable(AppDestination.Settings.route) {
            SettingsScreen(uiState = uiState, onSave = viewModel::saveAppSettings)
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = topLevelNavigationPolicy.saveState
        }
        launchSingleTop = true
        restoreState = topLevelNavigationPolicy.restoreState
    }
}
@Composable
private fun SideNavigation(
    uiState: DashboardUiState,
    currentRoute: String,
    liveSummary: SidebarSummaryOverride?,
    onSelect: (String) -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .width(248.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF111B2D), Color(0xFF07111F))
                )
            )
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "わるん会計",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "日報・レシート管理",
                    color = Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                destinations.forEach { destination ->
                    SideMenuItem(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        onClick = { onSelect(destination.route) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SidebarSummary(uiState, liveSummary)
        }
    }
}
@Composable
private fun SideMenuItem(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val container = if (selected) Color(0xFF2F5BE8) else Color.Transparent
    val content = if (selected) Color.White else Color(0xFFE5EDF8)
    Surface(
        color = container,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(destination.icon, contentDescription = destination.label, tint = content)
            Text(destination.label, color = content, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
@Composable
private fun BottomNavigation(
    currentRoute: String,
    onSelect: (String) -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        phoneDestinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label, maxLines = 1) }
            )
        }
    }
}
@Composable
private fun SidebarSummary(uiState: DashboardUiState, liveSummary: SidebarSummaryOverride?) {
    val summary = resolveSidebarSummary(uiState, liveSummary)
    val salesTotal = summary.salesTotal
    val estimatedBalance = summary.estimatedBalance
    val closingCash = summary.closingCash
    DashboardCard(containerColor = Color(0xFF182538)) {
        Text("今日のサマリー", color = Color.White, fontWeight = FontWeight.Bold)
        SummaryLine("売上合計", salesTotal.toYen(), Color.White)
        SummaryLine("概算差額", estimatedBalance.toYen(), Color(0xFF6EE78A))
        SummaryLine("現金残高", closingCash.toYen(), Color.White)
    }
}
@Composable
private fun SummaryLine(label: String, value: String, color: Color) {
    Column {
        Text(label, color = Color(0xFFAAB7CC), style = MaterialTheme.typography.labelSmall)
        Text(value, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun HomeScreen(
    uiState: DashboardUiState,
    evidenceRecoveryIssueCount: Int,
    onNavigate: (String) -> Unit
) {
    ScreenColumn {
        ScreenTitle("ホーム", "今日と今月の状況をすぐ確認できます。")
        if (evidenceRecoveryIssueCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "レシート画像の復旧に未解決項目があります",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "一部のレシート画像を復旧できませんでした。支出データは保持されています。未解決: ${evidenceRecoveryIssueCount}件",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        HomePrimaryActions(onNavigate)
        HomeStatusGrid(uiState)
        HomeMonthlyTasks(uiState, onNavigate)
        PhoneMenuCards(onNavigate)
        DailyReportList(uiState.reports.take(5))
    }
}
@Composable
private fun PhoneMenuCards(onNavigate: (String) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 720.dp) {
            MenuCardGrid(onNavigate)
        }
    }
}
@Composable
private fun HomePrimaryActions(onNavigate: (String) -> Unit) {
    DashboardCard {
        AdaptivePrimaryActionLayout { buttonModifier ->
            PrimaryActionButton(
                label = "日報を入力",
                onClick = { onNavigate(AppDestination.ReportEntry.route) },
                modifier = buttonModifier
            )
            PrimaryActionButton(
                label = "レシートを登録",
                onClick = { onNavigate(AppDestination.Receipt.route) },
                modifier = buttonModifier
            )
            PrimaryActionButton(
                label = "税理士へ提出",
                onClick = { onNavigate(AppDestination.Submit.route) },
                modifier = buttonModifier
            )
        }
        Text(
            text = "レシート撮影は次フェーズです。初期実装では購入日ベースの仮登録を行います。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
@Composable
private fun HomeStatusGrid(uiState: DashboardUiState) {
    DashboardCard {
        Text("今日", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveSummaryGrid { cardModifier ->
            SummaryCard("今日の売上", uiState.todaySales.toYen(), modifier = cardModifier)
            SummaryCard("今日の支出", uiState.todayExpensesTotal.toYen(), modifier = cardModifier)
            SummaryCard("今日の差額", uiState.todayBalance.toYen(), modifier = cardModifier)
            SummaryCard("現金差額", uiState.todayCashDifference.toYen(), modifier = cardModifier)
            SummaryCard("未確認レシート件数", "${uiState.unconfirmedReceiptCount}件", modifier = cardModifier)
        }
    }
    DashboardCard {
        Text("今月", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveSummaryGrid { cardModifier ->
            SummaryCard("今月の売上", uiState.monthSales.toYen(), modifier = cardModifier)
            SummaryCard("今月の支出", uiState.monthExpensesTotal.toYen(), modifier = cardModifier)
            SummaryCard("今月の概算差額", uiState.monthEstimatedBalance.toYen(), modifier = cardModifier)
        }
    }
}
@Composable
private fun HomeMonthlyTasks(
    uiState: DashboardUiState,
    onNavigate: (String) -> Unit
) {
    DashboardCard {
        Text("今月のやること", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveSummaryGrid { cardModifier ->
            SummaryCard("未確認レシート", "${uiState.monthUnconfirmedReceiptCount}件", modifier = cardModifier)
            SummaryCard("日付未確認", "${uiState.dateUnknownReceiptCount}件", modifier = cardModifier)
            SummaryCard("下書き日報", "${uiState.monthDraftReportCount}件", modifier = cardModifier)
            SummaryCard("提出状況", uiState.currentMonthSubmissionLabel, modifier = cardModifier)
        }
        ResponsivePrimaryAction(
            label = "月別整理を開く",
            onClick = { onNavigate(AppDestination.MonthlyOrganization.route) }
        )
    }
}
@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier.width(168.dp)
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDFF)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
@Composable
private fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp)) {
        Text(label)
    }
}
@Composable
private fun ResponsivePrimaryAction(
    label: String,
    onClick: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isCompact = maxWidth < 720.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isCompact) Arrangement.Start else Arrangement.End
        ) {
            PrimaryActionButton(
                label = label,
                onClick = onClick,
                modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(280.dp)
            )
        }
    }
}
@Composable
private fun AdaptivePrimaryActionLayout(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isCompact = maxWidth < 520.dp
        val buttonModifier = if (isCompact) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(180.dp)
        }
        if (isCompact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content(buttonModifier)
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                content(buttonModifier)
            }
        }
    }
}
@Composable
private fun SaveActionCard(
    draftLabel: String,
    completeLabel: String,
    onSaveDraft: () -> Unit,
    onSaveComplete: () -> Unit,
    isSaving: Boolean = false
) {
    DashboardCard(containerColor = Color(0xFFEFF6FF)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 520.dp
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onSaveDraft,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        SaveButtonContent(draftLabel, isSaving && draftLabel == "保存中…")
                    }
                    PrimaryActionButton(
                        label = completeLabel,
                        onClick = onSaveComplete,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onSaveDraft,
                        enabled = !isSaving,
                        modifier = Modifier.width(180.dp).height(52.dp)
                    ) {
                        SaveButtonContent(draftLabel, isSaving && draftLabel == "保存中…")
                    }
                    Spacer(Modifier.width(10.dp))
                    PrimaryActionButton(
                        label = completeLabel,
                        onClick = onSaveComplete,
                        modifier = Modifier.width(220.dp),
                        enabled = !isSaving
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveButtonContent(label: String, showProgress: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
        }
        Text(label)
    }
}

internal fun receiptInputAfterSave(
    result: Result<Unit>,
    currentInput: ReceiptInput,
    resetInput: ReceiptInput
): ReceiptInput = if (result.isSuccess) resetInput else currentInput

private fun newReceiptInput(): ReceiptInput = ReceiptInput(
    id = UUID.randomUUID().toString(),
    capturedDate = LocalDate.now().toString()
)
private data class SaveFeedback(
    val title: String,
    val body: String,
    val isError: Boolean
)

internal sealed interface ReportExpenseSaveDecision {
    data class Allowed(val expenseToSave: ExpenseInput?) : ReportExpenseSaveDecision
    data class BlockedDateMismatch(
        val reportDate: String,
        val draft: ExpenseInput
    ) : ReportExpenseSaveDecision
}

internal fun reportExpenseSaveDecision(
    reportDate: String,
    draftExpense: ExpenseInput?,
    expenseFormDirty: Boolean
): ReportExpenseSaveDecision {
    if (!expenseFormDirty || draftExpense == null) {
        return ReportExpenseSaveDecision.Allowed(null)
    }
    return if (draftExpense.expenseDate == reportDate) {
        ReportExpenseSaveDecision.Allowed(draftExpense)
    } else {
        ReportExpenseSaveDecision.BlockedDateMismatch(reportDate, draftExpense)
    }
}

internal data class SidebarSummaryOverride(
    val salesTotal: Long,
    val estimatedBalance: Long,
    val closingCash: Long
)

internal fun resolveSidebarSummary(
    uiState: DashboardUiState,
    liveSummary: SidebarSummaryOverride?
): SidebarSummaryOverride = liveSummary ?: SidebarSummaryOverride(
    salesTotal = uiState.todaySales,
    estimatedBalance = uiState.todayBalance,
    closingCash = uiState.todayClosingCash
)

private class ReportEntryNavigationGuard {
    var isActive by mutableStateOf(false)
    var hasUnsavedChanges by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var saveDraftAndContinue: ((() -> Unit) -> Unit)? = null
    var saveCompletedAndContinue: ((() -> Unit) -> Unit)? = null
    var discardChanges: (() -> Unit)? = null

    fun reset() {
        isActive = false
        hasUnsavedChanges = false
        isSaving = false
        saveDraftAndContinue = null
        saveCompletedAndContinue = null
        discardChanges = null
    }
}
@Composable
private fun SaveFeedbackOverlay(feedback: SaveFeedback, onDismiss: () -> Unit) {
    LaunchedEffect(feedback) {
        delay(if (feedback.isError) 2200 else 1400)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (feedback.isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (feedback.isError) MaterialTheme.colorScheme.error else Color(0xFF15803D),
                    modifier = Modifier.width(44.dp).height(44.dp)
                )
                Text(feedback.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(feedback.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
private fun MenuCardGrid(onNavigate: (String) -> Unit) {
    DashboardCard {
        Text("メニュー", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveMenuButtonLayout { buttonModifier ->
            destinations.forEach { destination ->
                OutlinedButton(
                    onClick = { onNavigate(destination.route) },
                    modifier = buttonModifier.height(48.dp)
                ) {
                    Icon(destination.icon, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(destination.label)
                }
            }
        }
    }
}
@Composable
private fun AdaptiveMenuButtonLayout(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isCompact = maxWidth < 520.dp
        val buttonModifier = if (isCompact) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(168.dp)
        }
        if (isCompact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content(buttonModifier)
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                content(buttonModifier)
            }
        }
    }
}
@Composable
private fun ReportEntryScreen(
    uiState: DashboardUiState,
    initialDate: String? = null,
    onSaveReport: (DailyReportInput, ExpenseInput?, ReceiptCaptureResult?, (Result<Unit>) -> Unit) -> Unit,
    onSaveExpense: (ExpenseInput, ReceiptCaptureResult?, (Result<Unit>) -> Unit) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onOpenReceiptCamera: () -> Unit,
    capturedReceipt: ReceiptCaptureResult? = null,
    onCaptureReceived: (ReceiptCaptureResult) -> Unit,
    onCaptureCleared: () -> Unit,
    navigationGuard: ReportEntryNavigationGuard? = null,
    onRequestBack: () -> Unit = {},
    onLiveSummaryChange: (SidebarSummaryOverride?) -> Unit = {},
    inputStateViewModel: InputStateViewModel = hiltViewModel(),
    receiptOcrViewModel: ReceiptOcrViewModel = hiltViewModel(),
    receiptImageImportViewModel: ReceiptImageImportViewModel = hiltViewModel()
) {
    val initialReportDate = remember(initialDate) { initialDate ?: DailyReportInput().reportDate }

    fun inputForDate(reportDate: String): DailyReportInput =
        uiState.reports.firstOrNull { it.reportDate == reportDate }?.toInput()
            ?: DailyReportInput(reportDate = reportDate)

    inputStateViewModel.initializeReport(inputForDate(initialReportDate))
    var reportInput by inputStateViewModel.reportInputState
    var cleanReportInput by inputStateViewModel.cleanReportInputState
    var pendingReportDate by inputStateViewModel.pendingReportDateState
    var expenseFormDirty by inputStateViewModel.expenseFormDirtyState
    var utilityFieldsEdited by inputStateViewModel.utilityFieldsEditedState
    var draftExpenseInput by inputStateViewModel.draftExpenseInputState
    val pendingExpenseCapture by inputStateViewModel.pendingExpenseCaptureState
    var savingStatus by inputStateViewModel.reportSavingStatusState
    val imageImportState by receiptImageImportViewModel.uiState.collectAsStateWithLifecycle()
    var saveFeedback by remember { mutableStateOf<SaveFeedback?>(null) }
    var expenseSaveInProgress by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val captureStartCoordinator = remember(context) {
        ReceiptCaptureStartCoordinator(
            journalProtectedReceiptImageStore(context)
        )
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (
            uri != null &&
            !receiptImageImportViewModel.handlePickerResult(uri.toString())
        ) {
            saveFeedback = SaveFeedback(
                title = "画像を取り込めませんでした",
                body = "取込処理が完了してから、もう一度お試しください。",
                isError = true
            )
        }
    }
    val receiptAcquisitionEnabled =
        savingStatus == null &&
            !expenseSaveInProgress &&
            imageImportState !is ReceiptImageImportUiState.Importing &&
            imageImportState !is ReceiptImageImportUiState.Imported

    fun startNewReceiptCapture() {
        if (!receiptAcquisitionEnabled) {
            saveFeedback = SaveFeedback(
                title = "撮影を開始できませんでした",
                body = "保存または画像取込が完了してから、もう一度お試しください。",
                isError = true
            )
            return
        }
        val started = captureStartCoordinator.startNewCapture(
            capturesToDiscard = listOf(
                capturedReceipt,
                receiptOcrViewModel.uiState.value.captureOrNull,
                pendingExpenseCapture
            ),
            clearSessionState = {
                receiptOcrViewModel.clear()
                receiptImageImportViewModel.reset()
                onCaptureCleared()
                inputStateViewModel.discardPendingExpenseCapture()
            },
            openCamera = onOpenReceiptCamera
        )
        if (!started) {
            saveFeedback = SaveFeedback(
                title = "撮影を開始できませんでした",
                body = "前回の未確定画像を削除できませんでした。入力内容は保持されています。もう一度お試しください。",
                isError = true
            )
        }
    }

    fun startReceiptImageImport() {
        if (!receiptAcquisitionEnabled) {
            saveFeedback = SaveFeedback(
                title = "画像を選択できませんでした",
                body = "保存または画像取込が完了してから、もう一度お試しください。",
                isError = true
            )
            return
        }
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val importedCapture =
        (imageImportState as? ReceiptImageImportUiState.Imported)?.result
    LaunchedEffect(importedCapture?.captureId) {
        val newCapture = importedCapture ?: return@LaunchedEffect
        val previousPendingCapture = pendingExpenseCapture
        val discardResult = captureStartCoordinator.adoptImportedCapture(
            importedCapture = newCapture,
            capturesToDiscard = listOf(
                capturedReceipt,
                receiptOcrViewModel.uiState.value.captureOrNull,
                previousPendingCapture
            ),
            clearOcrSessionState = receiptOcrViewModel::clear,
            adoptCapture = { capture ->
                receiptOcrViewModel.runOcr(capture)
                onCaptureReceived(capture)
            },
            clearDiscardedOwnership = { deletedCaptureIds ->
                if (
                    previousPendingCapture?.captureId
                        ?.let(deletedCaptureIds::contains) == true
                ) {
                    inputStateViewModel.discardPendingExpenseCapture()
                }
            }
        )
        receiptImageImportViewModel.consumeImported(newCapture.captureId)
        saveFeedback = if (discardResult.retainedCaptureIds.isEmpty()) {
            SaveFeedback(
                title = "画像を取り込みました",
                body = "編集中の支出内容を保持したまま、文字を読み取ります。",
                isError = false
            )
        } else {
            SaveFeedback(
                title = "画像を取り込みました",
                body = "新しい画像を読み取ります。保護中または削除できない以前の画像は、安全のため保持しています。",
                isError = false
            )
        }
    }

    LaunchedEffect(capturedReceipt?.captureId) {
        if (capturedReceipt != null) {
            saveFeedback = SaveFeedback(
                title = "レシート画像を受け取りました",
                body = "編集中の支出内容を保持したまま、文字を読み取ります。",
                isError = false
            )
        }
    }

    val paymentVisibility = uiState.appSettings.toPaymentVisibility()
    val reportExpenses = uiState.expenses.filter { it.expenseDate == reportInput.reportDate }
    val liveReportExpenses = reportExpenses.withDraftExpensePreview(draftExpenseInput?.takeIf { it.expenseDate == reportInput.reportDate })
    val enteredReportDates = remember(uiState.reports) { uiState.reports.map { it.reportDate }.toSet() }
    val totals = reportInput.calculateTotals(paymentVisibility, liveReportExpenses)
    val hasUnsavedChanges = reportInput != cleanReportInput || expenseFormDirty || utilityFieldsEdited
    val expenseSaveDecision = reportExpenseSaveDecision(
        reportDate = reportInput.reportDate,
        draftExpense = draftExpenseInput,
        expenseFormDirty = expenseFormDirty
    )

    LaunchedEffect(reportInput.reportDate, totals) {
        onLiveSummaryChange(
            SidebarSummaryOverride(
                salesTotal = totals.totalSales,
                estimatedBalance = totals.todayBalance,
                closingCash = totals.actualClosingCash
            )
        )
    }
    DisposableEffect(Unit) {
        onDispose { onLiveSummaryChange(null) }
    }

    fun openReportDate(reportDate: String) {
        val nextInput = inputForDate(reportDate)
        inputStateViewModel.openReport(nextInput)
    }

    fun showSaveFailure(savedInput: DailyReportInput, error: Throwable? = null) {
        val evidenceFailureMessage = (error as? EvidenceFinalizationAfterAccountingSaveException)?.message
        saveFeedback = SaveFeedback(
            title = "保存できませんでした",
            body = evidenceFailureMessage
                ?: "${savedInput.reportDate} の日報を\n保存できませんでした。入力内容を確認して、もう一度お試しください。",
            isError = true
        )
    }

    fun showSaveSuccess(status: String, savedInput: DailyReportInput) {
        inputStateViewModel.markReportSaved(savedInput)
        saveFeedback = SaveFeedback(
            title = if (status == DailyReportStatus.Draft) "下書きを保存しました" else "保存しました",
            body = "${savedInput.reportDate} の日報を\n${if (status == DailyReportStatus.Draft) "下書き保存しました" else "保存しました"}",
            isError = false
        )
    }

    fun ExpenseInput.isReadyToSave(): Boolean {
        val amountValue = amount.trim().toLongOrNull()
        return expenseDate.isNotBlank() &&
            category.isNotBlank() &&
            amountValue != null &&
            amountValue > 0L &&
            isSupportedPaymentMethod(normalizePaymentMethod(paymentMethod))
    }

    fun saveCurrentReport(status: String, afterSuccess: (() -> Unit)? = null) {
        if (savingStatus != null) return
        val currentExpenseDecision = reportExpenseSaveDecision(
            reportDate = reportInput.reportDate,
            draftExpense = draftExpenseInput,
            expenseFormDirty = expenseFormDirty
        )
        if (currentExpenseDecision is ReportExpenseSaveDecision.BlockedDateMismatch) {
            saveFeedback = SaveFeedback(
                title = "支出を先に保存してください",
                body = "支出日 ${currentExpenseDecision.draft.expenseDate} は日報日 ${currentExpenseDecision.reportDate} と異なります。日報の一括保存ではこの支出は保存されません。支出フォームの「保存」で個別保存してください。",
                isError = true
            )
            return
        }
        val savedInput = reportInput
            .copy(status = status)
            .withUtilityCompatibility(cleanReportInput, utilityFieldsEdited)
            .withHiddenPaymentsCleared(paymentVisibility)
        val expenseToSave = (currentExpenseDecision as ReportExpenseSaveDecision.Allowed).expenseToSave

        savingStatus = status
        if (expenseToSave != null && !expenseToSave.isReadyToSave()) {
            savingStatus = null
            showSaveFailure(savedInput)
            return
        }
        val evidenceCapture = expenseToSave?.let { inputStateViewModel.pendingCaptureFor(it.id) }
        onSaveReport(savedInput, expenseToSave, evidenceCapture) { result ->
            savingStatus = null
            result.fold(
                onSuccess = {
                    if (expenseToSave != null && evidenceCapture != null) {
                        inputStateViewModel.markPendingEvidenceStored(
                            expenseId = expenseToSave.id,
                            captureId = evidenceCapture.captureId
                        )
                    }
                    showSaveSuccess(status, savedInput)
                    afterSuccess?.invoke()
                },
                onFailure = { showSaveFailure(savedInput, it) }
            )
        }
    }

    fun saveExpenseWithPendingEvidence(
        input: ExpenseInput,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (expenseSaveInProgress) return
        expenseSaveInProgress = true
        val evidenceCapture = inputStateViewModel.pendingCaptureFor(input.id)
        onSaveExpense(input, evidenceCapture) { result ->
            expenseSaveInProgress = false
            if (result.isSuccess && evidenceCapture != null) {
                inputStateViewModel.markPendingEvidenceStored(
                    expenseId = input.id,
                    captureId = evidenceCapture.captureId
                )
            }
            onResult(result)
        }
    }

    LaunchedEffect(reportInput, cleanReportInput, expenseFormDirty, utilityFieldsEdited, savingStatus, draftExpenseInput) {
        navigationGuard?.isActive = true
        navigationGuard?.hasUnsavedChanges = hasUnsavedChanges
        navigationGuard?.isSaving = savingStatus != null
        navigationGuard?.saveDraftAndContinue = { afterSuccess -> saveCurrentReport(DailyReportStatus.Draft, afterSuccess) }
        navigationGuard?.saveCompletedAndContinue = { afterSuccess -> saveCurrentReport(DailyReportStatus.Completed, afterSuccess) }
        navigationGuard?.discardChanges = {
            inputStateViewModel.discardReportChanges()
        }
    }
    DisposableEffect(navigationGuard) {
        navigationGuard?.isActive = true
        onDispose { navigationGuard?.reset() }
    }

    BackHandler(enabled = hasUnsavedChanges && pendingReportDate == null && savingStatus == null) {
        navigationGuard?.isActive = true
        navigationGuard?.hasUnsavedChanges = true
        onRequestBack()
    }

    fun requestOpenReportDate(reportDate: String) {
        if (reportDate == reportInput.reportDate) return
        if (hasUnsavedChanges) {
            pendingReportDate = reportDate
        } else {
            openReportDate(reportDate)
        }
    }

    pendingReportDate?.let { targetDate ->
        val isSaving = savingStatus != null
        AlertDialog(
            onDismissRequest = {
                if (!isSaving) pendingReportDate = null
            },
            title = { Text("未保存の内容があります") },
            text = { Text("この日報には未保存の変更があります。移動する前に保存できます。") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            saveCurrentReport(DailyReportStatus.Draft) {
                                pendingReportDate = null
                                openReportDate(targetDate)
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (savingStatus == DailyReportStatus.Draft) "保存中…" else "下書き保存")
                    }
                    Button(
                        onClick = {
                            saveCurrentReport(DailyReportStatus.Completed) {
                                pendingReportDate = null
                                openReportDate(targetDate)
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (savingStatus == DailyReportStatus.Completed) "保存中…" else "入力完了で保存")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingReportDate = null
                            openReportDate(targetDate)
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存せずに続行")
                    }
                    TextButton(
                        onClick = { pendingReportDate = null },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("戻る")
                    }
                }
            },
            dismissButton = {}
        )
    }
    saveFeedback?.let { feedback ->
        SaveFeedbackOverlay(feedback = feedback, onDismiss = { saveFeedback = null })
    }

    ScreenColumn {
        ScreenTitle("日報入力", "空いた時間に任意の日付で入力できます。途中でも下書き保存できます。")
        ReceiptOcrPanel(
            capturedReceipt = capturedReceipt,
            onCaptureCleared = onCaptureCleared,
            onOpenReceiptCamera = ::startNewReceiptCapture,
            knownStoreNames = receiptParserStoreNames(uiState.supplierCandidates),
            existingExpense = draftExpenseInput,
            existingPendingCapture = pendingExpenseCapture,
            onApplyToExpense = { result, expectedCaptureId ->
                val mergeSummary = draftExpenseInput?.let { summarizeReceiptOcrMerge(it, result) }
                val applied = inputStateViewModel.applyReceiptOcr(result, expectedCaptureId)
                if (applied) {
                    saveFeedback = SaveFeedback(
                        title = "支出入力へ反映しました",
                        body = mergeSummary?.toFeedbackText()
                            ?: "OCR候補を支出入力へ反映しました。内容を確認して編集できます。",
                        isError = false
                    )
                }
                applied
            },
            viewModel = receiptOcrViewModel
        )
        when (val state = imageImportState) {
            is ReceiptImageImportUiState.Importing -> Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("選択した画像を取り込んでいます…")
                }
            }
            is ReceiptImageImportUiState.Error -> Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        state.error.userMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = receiptImageImportViewModel::clearError) {
                        Text("閉じる")
                    }
                }
            }
            ReceiptImageImportUiState.Idle,
            is ReceiptImageImportUiState.Imported -> Unit
        }
        (expenseSaveDecision as? ReportExpenseSaveDecision.BlockedDateMismatch)?.let { blocked ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "日付が異なる未保存の支出があります",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "支出日 ${blocked.draft.expenseDate} は日報日 ${blocked.reportDate} と異なります。日報の一括保存ではこの支出は保存されないため、先に支出フォームの「保存」で個別保存してください。",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "日報日とOCR購入日は自動変更されません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        DailyReportForm(
            input = reportInput,
            paymentVisibility = paymentVisibility,
            totals = totals,
            expenses = reportExpenses,
            expenseEvidence = uiState.expenseEvidence,
            previewExpenses = liveReportExpenses,
            draftExpenseInput = draftExpenseInput,
            ocrApplyCaptureId = pendingExpenseCapture?.captureId,
            supplierCandidates = uiState.supplierCandidates,
            enteredReportDates = enteredReportDates,
            onInputChange = { reportInput = it },
            onUtilityInputChange = { nextInput, editedValue ->
                if (editedValue.isNotBlank()) utilityFieldsEdited = true
                reportInput = nextInput
            },
            onCalendarDateSelected = { requestOpenReportDate(it) },
            onSaveExpense = ::saveExpenseWithPendingEvidence,
            onDeleteExpense = onDeleteExpense,
            onAddSupplierCandidate = onAddSupplierCandidate,
            onHideSupplierCandidate = onHideSupplierCandidate,
            onOpenReceiptCamera = ::startNewReceiptCapture,
            onOpenReceiptGallery = ::startReceiptImageImport,
            receiptAcquisitionEnabled = receiptAcquisitionEnabled,
            onExpenseFormDirtyChanged = { expenseFormDirty = it },
            onDraftExpenseChanged = { draftExpenseInput = it },
            savingStatus = savingStatus,
            onSave = { status -> saveCurrentReport(status) }
        )
        DailyReportList(uiState.reports.take(3))
    }
}
@Composable
private fun DailyReportForm(
    input: DailyReportInput,
    paymentVisibility: PaymentVisibility,
    totals: DailyReportTotals,
    expenses: List<ExpenseRecord>,
    expenseEvidence: List<ExpenseEvidenceRecord>,
    previewExpenses: List<ExpenseRecord>,
    draftExpenseInput: ExpenseInput?,
    ocrApplyCaptureId: String?,
    supplierCandidates: List<SupplierCandidateRecord>,
    enteredReportDates: Set<String>,
    onInputChange: (DailyReportInput) -> Unit,
    onUtilityInputChange: (DailyReportInput, String) -> Unit,
    onCalendarDateSelected: (String) -> Unit,
    onSaveExpense: (ExpenseInput, (Result<Unit>) -> Unit) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onOpenReceiptCamera: () -> Unit,
    onOpenReceiptGallery: () -> Unit,
    receiptAcquisitionEnabled: Boolean,
    onExpenseFormDirtyChanged: (Boolean) -> Unit,
    onDraftExpenseChanged: (ExpenseInput?) -> Unit,
    savingStatus: String?,
    onSave: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AdaptiveFormCardLayout { cardModifier ->
            BasicInfoCard(
                input = input,
                enteredReportDates = enteredReportDates,
                onInputChange = onInputChange,
                onCalendarDateSelected = onCalendarDateSelected,
                modifier = cardModifier
            )
            SalesCard(
                input = input,
                paymentVisibility = paymentVisibility,
                totalSales = totals.totalSales,
                onInputChange = onInputChange,
                modifier = cardModifier
            )
            ExpenseCard(
                input = input,
                expenses = expenses,
                expenseEvidence = expenseEvidence,
                previewExpenses = previewExpenses,
                draftExpenseInput = draftExpenseInput,
                ocrApplyCaptureId = ocrApplyCaptureId,
                supplierCandidates = supplierCandidates,
                expenseTotal = totals.expenseTotal,
                todayBalance = totals.todayBalance,
                onInputChange = onInputChange,
                onUtilityInputChange = onUtilityInputChange,
                onSaveExpense = onSaveExpense,
                onDeleteExpense = onDeleteExpense,
                onAddSupplierCandidate = onAddSupplierCandidate,
                onHideSupplierCandidate = onHideSupplierCandidate,
                onOpenReceiptCamera = onOpenReceiptCamera,
                onOpenReceiptGallery = onOpenReceiptGallery,
                receiptAcquisitionEnabled = receiptAcquisitionEnabled,
                onExpenseFormDirtyChanged = onExpenseFormDirtyChanged,
                onDraftExpenseChanged = onDraftExpenseChanged,
                modifier = cardModifier
            )
            CashManagementCard(
                input = input,
                totals = totals,
                onInputChange = onInputChange,
                modifier = cardModifier
            )
            BusinessInfoCard(
                input = input,
                customerUnitPrice = totals.customerUnitPrice,
                onInputChange = onInputChange,
                modifier = cardModifier
            )
        }
        SaveActionCard(
            draftLabel = if (savingStatus == DailyReportStatus.Draft) "保存中…" else "下書き保存",
            completeLabel = if (savingStatus == DailyReportStatus.Completed) "保存中…" else "入力完了で保存",
            onSaveDraft = { onSave(DailyReportStatus.Draft) },
            onSaveComplete = { onSave(DailyReportStatus.Completed) },
            isSaving = savingStatus != null
        )
    }
}
@Composable
private fun BasicInfoCard(
    input: DailyReportInput,
    enteredReportDates: Set<String>,
    onInputChange: (DailyReportInput) -> Unit,
    onCalendarDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    FormCard(modifier = modifier) {
        Text("基本情報", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveFormFields { fieldModifier ->
            ReportDateField(
                value = input.reportDate,
                markedDates = enteredReportDates,
                modifier = fieldModifier,
                onCalendarDateSelected = onCalendarDateSelected
            ) {
                onInputChange(input.copy(reportDate = it))
            }
            AppTextField(
                label = "記入者",
                value = input.authorName,
                modifier = fieldModifier
            ) {
                onInputChange(input.copy(authorName = it))
            }
        }
    }
}
@Composable
private fun ReportDateField(
    value: String,
    markedDates: Set<String>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onCalendarDateSelected: (String) -> Unit,
    onDateChange: (String) -> Unit
) {
    var showCalendar by remember { mutableStateOf(false) }

    if (showCalendar) {
        ReportCalendarDialog(
            selectedDate = parseDateOrNull(value) ?: LocalDate.now(),
            markedDates = markedDates,
            onDateSelected = { selectedDate ->
                onCalendarDateSelected(selectedDate.toString())
                showCalendar = false
            },
            onDismiss = { showCalendar = false }
        )
    }

    DateInputTextField(
        label = "日付",
        value = value,
        onValueChange = onDateChange,
        trailingIcon = {
            IconButton(onClick = { showCalendar = true }) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = "カレンダーを開く")
            }
        },
        modifier = modifier
    )
}
@Composable
private fun ReportCalendarDialog(
    selectedDate: LocalDate,
    markedDates: Set<String>,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var visibleMonth by remember(selectedDate) { mutableStateOf(YearMonth.from(selectedDate)) }
    val today = remember { LocalDate.now() }
    val monthStart = visibleMonth.atDay(1)
    val leadingEmptyDays = monthStart.dayOfWeek.value % 7
    val daysInMonth = visibleMonth.lengthOfMonth()
    val weekLabels = listOf("日", "月", "火", "水", "木", "金", "土")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { visibleMonth = visibleMonth.minusMonths(1) }) {
                        Text("前月")
                    }
                    Text(
                        text = visibleMonth.toJapaneseMonthLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { visibleMonth = visibleMonth.plusMonths(1) }) {
                        Text("次月")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    weekLabels.forEach { label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(6) { weekIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(7) { dayIndex ->
                                val cellIndex = weekIndex * 7 + dayIndex
                                val dayNumber = cellIndex - leadingEmptyDays + 1
                                if (dayNumber in 1..daysInMonth) {
                                    val date = visibleMonth.atDay(dayNumber)
                                    ReportCalendarDay(
                                        date = date,
                                        isSelected = date == selectedDate,
                                        isToday = date == today,
                                        isMarked = date.toString() in markedDates,
                                        onClick = { onDateSelected(date) },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("閉じる")
                    }
                }
            }
        }
    }
}
@Composable
private fun ReportCalendarDay(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    isMarked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = if (isMarked) "●" else " ",
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
@Composable
private fun SalesCard(
    input: DailyReportInput,
    paymentVisibility: PaymentVisibility,
    totalSales: Long,
    onInputChange: (DailyReportInput) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    FormCard(modifier = modifier) {
        Text("売上", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveFormFields { fieldModifier ->
            if (paymentVisibility.useCashPayment) {
                AppTextField("現金売上", input.cashSales, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    onInputChange(input.copy(cashSales = it))
                }
            }
            if (paymentVisibility.useCardPayment) {
                AppTextField("クレジットカード売上", input.cardSales, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    onInputChange(input.copy(cardSales = it))
                }
            }
            if (paymentVisibility.useQrPayment) {
                AppTextField("QR決済売上", input.qrSales, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    onInputChange(input.copy(qrSales = it))
                }
            }
            if (paymentVisibility.useAccountsReceivablePayment) {
                AppTextField("売掛売上", input.accountsReceivableSales, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    onInputChange(input.copy(accountsReceivableSales = it))
                }
            }
            if (paymentVisibility.useOtherPayment) {
                AppTextField("その他売上", input.otherSales, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    onInputChange(input.copy(otherSales = it))
                }
            }
        }
        TotalRow("売上合計", totalSales.toYen())
    }
}
@Composable
private fun ExpenseCard(
    input: DailyReportInput,
    expenses: List<ExpenseRecord>,
    expenseEvidence: List<ExpenseEvidenceRecord>,
    previewExpenses: List<ExpenseRecord>,
    draftExpenseInput: ExpenseInput?,
    ocrApplyCaptureId: String?,
    expenseTotal: Long,
    todayBalance: Long,
    onInputChange: (DailyReportInput) -> Unit,
    onUtilityInputChange: (DailyReportInput, String) -> Unit,
    onSaveExpense: (ExpenseInput, (Result<Unit>) -> Unit) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    supplierCandidates: List<SupplierCandidateRecord>,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onOpenReceiptCamera: () -> Unit,
    onOpenReceiptGallery: () -> Unit,
    receiptAcquisitionEnabled: Boolean,
    onExpenseFormDirtyChanged: (Boolean) -> Unit,
    onDraftExpenseChanged: (ExpenseInput?) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var selectedCategory by remember(input.reportDate, draftExpenseInput?.id) {
        mutableStateOf(draftExpenseInput?.category)
    }
    val foodTotal = detailExpense(input, previewExpenses, FoodPurchaseCategory)
    val alcoholTotal = detailExpense(input, previewExpenses, AlcoholPurchaseCategory)
    val consumablesTotal = detailExpense(input, previewExpenses, ConsumablesCategory)
    val otherExpenseTotal = detailExpense(input, previewExpenses, OtherExpenseCategory)
    val vehicleTransportTotal = detailExpense(input, previewExpenses, VehicleTransportCategory)

    FormCard(modifier = modifier) {
        Text("支出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val contentMaxWidth = maxWidth
            val categoryRows: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailedExpenseCategoryRow(FoodPurchaseCategory, foodTotal) {
                        selectedCategory = FoodPurchaseCategory
                    }
                    DetailedExpenseCategoryRow(AlcoholPurchaseCategory, alcoholTotal) {
                        selectedCategory = AlcoholPurchaseCategory
                    }
                }
            }
            val detailPane: @Composable () -> Unit = {
                selectedCategory?.takeIf { it != OtherExpenseCategory && it != VehicleTransportCategory && it != ConsumablesCategory }?.let { category ->
                    ExpenseDetailPanel(
                        reportDate = input.reportDate,
                        category = category,
                        expenses = expenses.filter { it.category == category },
                        expenseEvidence = expenseEvidence,
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        supplierCandidates = supplierCandidates,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onOpenReceiptCamera = onOpenReceiptCamera,
                        onOpenReceiptGallery = onOpenReceiptGallery,
                        receiptAcquisitionEnabled = receiptAcquisitionEnabled,
                        onDirtyChanged = onExpenseFormDirtyChanged,
                        onDraftExpenseChanged = onDraftExpenseChanged,
                        restoredDraft = draftExpenseInput,
                        ocrApplyCaptureId = ocrApplyCaptureId
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (contentMaxWidth >= 620.dp && selectedCategory != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(0.9f)) { categoryRows() }
                        Column(modifier = Modifier.weight(1.1f)) { detailPane() }
                    }
                } else {
                    categoryRows()
                    detailPane()
                }
                DetailedExpenseCategoryRow(
                    ConsumablesCategory,
                    previewExpenses.preferredExpenseAmount(input.reportDate, ConsumablesCategory, input.consumablesExpense.toInputLong())
                ) {
                    selectedCategory = ConsumablesCategory
                }
                if (selectedCategory == ConsumablesCategory) {
                    ExpenseDetailPanel(
                        reportDate = input.reportDate,
                        category = ConsumablesCategory,
                        expenses = expenses.filter { it.category == ConsumablesCategory },
                        expenseEvidence = expenseEvidence,
                        supplierCandidates = supplierCandidates,
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onOpenReceiptCamera = onOpenReceiptCamera,
                        onOpenReceiptGallery = onOpenReceiptGallery,
                        receiptAcquisitionEnabled = receiptAcquisitionEnabled,
                        onDirtyChanged = onExpenseFormDirtyChanged,
                        onDraftExpenseChanged = onDraftExpenseChanged,
                        restoredDraft = draftExpenseInput,
                        ocrApplyCaptureId = ocrApplyCaptureId
                    )
                }
                AdaptiveFormFields { fieldModifier ->
                    AppTextField("電気代（中部電力）", input.electricityExpense, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                        onUtilityInputChange(input.copy(electricityExpense = it), it)
                    }
                    AppTextField("ガス代（丸栄ガス）", input.gasExpense, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                        onUtilityInputChange(input.copy(gasExpense = it), it)
                    }
                    AppTextField("水道代（水道）", input.waterExpense, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                        onUtilityInputChange(input.copy(waterExpense = it), it)
                    }
                }
                UtilityTotalRow(input.utilityBreakdownExpenseTotal().toYen())
                AppTextField("通信費（NTT）", input.communicationExpense, KeyboardType.Number, clearZeroOnFocus = true) {
                    onInputChange(input.copy(communicationExpense = it))
                }
                AppTextField("家賃（ヒロセフサコ）", input.rentExpense, KeyboardType.Number, clearZeroOnFocus = true) {
                    onInputChange(input.copy(rentExpense = it))
                }
                AppTextField("税理士顧問料", input.accountantFeeExpense, KeyboardType.Number, clearZeroOnFocus = true) {
                    onInputChange(input.copy(accountantFeeExpense = it))
                }
                DetailedExpenseCategoryRow(VehicleTransportCategory, vehicleTransportTotal) {
                    selectedCategory = VehicleTransportCategory
                }
                if (selectedCategory == VehicleTransportCategory) {
                    ExpenseDetailPanel(
                        reportDate = input.reportDate,
                        category = VehicleTransportCategory,
                        expenses = expenses.filter { it.category == VehicleTransportCategory },
                        expenseEvidence = expenseEvidence,
                        supplierCandidates = supplierCandidates,
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onOpenReceiptCamera = onOpenReceiptCamera,
                        onOpenReceiptGallery = onOpenReceiptGallery,
                        receiptAcquisitionEnabled = receiptAcquisitionEnabled,
                        onDirtyChanged = onExpenseFormDirtyChanged,
                        onDraftExpenseChanged = onDraftExpenseChanged,
                        restoredDraft = draftExpenseInput,
                        ocrApplyCaptureId = ocrApplyCaptureId
                    )
                }
                AppTextField("雑費", input.miscellaneousExpense, KeyboardType.Number, clearZeroOnFocus = true) {
                    onInputChange(input.copy(miscellaneousExpense = it))
                }
                DetailedExpenseCategoryRow(OtherExpenseCategory, otherExpenseTotal) {
                    selectedCategory = OtherExpenseCategory
                }
                if (selectedCategory == OtherExpenseCategory) {
                    ExpenseDetailPanel(
                        reportDate = input.reportDate,
                        category = OtherExpenseCategory,
                        expenses = expenses.filter { it.category == OtherExpenseCategory },
                        expenseEvidence = expenseEvidence,
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        supplierCandidates = supplierCandidates,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onOpenReceiptCamera = onOpenReceiptCamera,
                        onOpenReceiptGallery = onOpenReceiptGallery,
                        receiptAcquisitionEnabled = receiptAcquisitionEnabled,
                        onDirtyChanged = onExpenseFormDirtyChanged,
                        onDraftExpenseChanged = onDraftExpenseChanged,
                        restoredDraft = draftExpenseInput,
                        ocrApplyCaptureId = ocrApplyCaptureId
                    )
                }
            }
        }
        TotalRow("支出合計", expenseTotal.toYen())
        TotalRow("差額", todayBalance.toYen())
    }
}
@Composable
private fun DetailedExpenseCategoryRow(
    category: String,
    total: Long,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(expenseCategoryLabel(category), fontWeight = FontWeight.Bold)
            Text("合計 ${total.toYen()}  >", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun ExpenseDetailPanel(
    reportDate: String,
    category: String,
    expenses: List<ExpenseRecord>,
    expenseEvidence: List<ExpenseEvidenceRecord>,
    onClose: () -> Unit,
    onSaveExpense: (ExpenseInput, (Result<Unit>) -> Unit) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    supplierCandidates: List<SupplierCandidateRecord>,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onOpenReceiptCamera: () -> Unit,
    onOpenReceiptGallery: () -> Unit,
    receiptAcquisitionEnabled: Boolean,
    onDirtyChanged: (Boolean) -> Unit,
    onDraftExpenseChanged: (ExpenseInput?) -> Unit,
    restoredDraft: ExpenseInput?,
    ocrApplyCaptureId: String?
) {
    var editingExpense by remember(reportDate, category, restoredDraft?.id) {
        mutableStateOf(restoredDraft?.let { draft -> expenses.firstOrNull { it.id == draft.id } })
    }
    var showForm by remember(reportDate, category) { mutableStateOf(true) }
    var formResetKey by remember(reportDate, category) { mutableStateOf(0) }
    var saveFailureMessage by remember(reportDate, category) { mutableStateOf<String?>(null) }
    var selectedEvidence by remember { mutableStateOf<ExpenseEvidenceRecord?>(null) }
    var expenseSaveInProgress by remember(reportDate, category) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${expenseCategoryLabel(category)} 明細", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                onDirtyChanged(false)
                onDraftExpenseChanged(null)
                onClose()
            }) { Text("閉じる") }
        }
        if (expenses.isEmpty()) {
            Text("明細はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            expenses.sortedByDescending { it.createdAt }.forEach { expense ->
                ExpenseRecordRow(
                    expense = expense,
                    evidence = expenseEvidence.filter { it.expenseId == expense.id },
                    onOpenEvidence = { selectedEvidence = it },
                    onEdit = {
                        editingExpense = expense
                        showForm = true
                    },
                    onDelete = { onDeleteExpense(expense) }
                )
            }
        }
        Button(onClick = {
            editingExpense = null
            onDirtyChanged(false)
            onDraftExpenseChanged(null)
            formResetKey++
            showForm = true
        }) {
            Text("支出を追加")
        }
        if (showForm) {
            ExpenseRecordForm(
                reportDate = reportDate,
                initialCategory = category,
                editingExpense = editingExpense,
                resetKey = formResetKey,
                supplierCandidates = supplierCandidates,
                onAddSupplierCandidate = onAddSupplierCandidate,
                onHideSupplierCandidate = onHideSupplierCandidate,
                onOpenReceiptCamera = onOpenReceiptCamera,
                onOpenReceiptGallery = onOpenReceiptGallery,
                receiptAcquisitionEnabled = receiptAcquisitionEnabled && !expenseSaveInProgress,
                onDirtyChanged = onDirtyChanged,
                onDraftExpenseChanged = onDraftExpenseChanged,
                restoredDraft = restoredDraft,
                ocrApplyCaptureId = ocrApplyCaptureId,
                onClose = {
                    onDirtyChanged(false)
                    onDraftExpenseChanged(null)
                    onClose()
                },
                onCancel = {
                    onDirtyChanged(false)
                    onDraftExpenseChanged(null)
                    editingExpense = null
                    showForm = false
                },
                onSave = { expenseInput ->
                    if (expenseSaveInProgress) return@ExpenseRecordForm
                    expenseSaveInProgress = true
                    val wasEditing = editingExpense != null
                    onSaveExpense(expenseInput) { result ->
                        expenseSaveInProgress = false
                        if (result.isSuccess) {
                            saveFailureMessage = null
                            onDirtyChanged(false)
                            onDraftExpenseChanged(null)
                            editingExpense = null
                            if (wasEditing) {
                                showForm = false
                            } else {
                                formResetKey++
                                showForm = true
                            }
                        } else {
                            saveFailureMessage = (result.exceptionOrNull() as? EvidenceFinalizationAfterAccountingSaveException)
                                ?.message
                                ?: "支出明細を保存できませんでした。入力内容を確認して、もう一度お試しください。"
                        }
                    }
                }
            )
        }
    }
    if (saveFailureMessage != null) {
        SaveFeedbackOverlay(
            feedback = SaveFeedback(
                title = "保存できませんでした",
                body = saveFailureMessage.orEmpty(),
                isError = true
            ),
            onDismiss = { saveFailureMessage = null }
        )
    }
    selectedEvidence?.let { evidence ->
        EvidenceImageDialog(
            evidence = evidence,
            onDismiss = { selectedEvidence = null }
        )
    }
}@Composable
private fun ExpenseRecordRow(
    expense: ExpenseRecord,
    evidence: List<ExpenseEvidenceRecord>,
    onOpenEvidence: (ExpenseEvidenceRecord) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(10.dp)) {
            Text(expense.supplierName.orEmpty().ifBlank { "支払先未入力" }, fontWeight = FontWeight.Bold)
            Text("${expense.amount.toYen()} / ${expense.paymentMethod.orEmpty().ifBlank { "支払方法未入力" }}")
            if (evidence.isNotEmpty()) {
                Text("保存済みレシート ${evidence.size}件", color = MaterialTheme.colorScheme.primary)
                evidence.forEachIndexed { index, item ->
                    OutlinedButton(onClick = { onOpenEvidence(item) }) {
                        Text(if (evidence.size == 1) "レシート画像を開く" else "レシート画像 ${index + 1}を開く")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("編集") }
                OutlinedButton(onClick = onDelete) { Text("削除") }
            }
        }
    }
}
@Composable
private fun ExpenseRecordForm(
    reportDate: String,
    initialCategory: String,
    editingExpense: ExpenseRecord?,
    resetKey: Int,
    supplierCandidates: List<SupplierCandidateRecord>,
    onClose: () -> Unit,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onOpenReceiptCamera: () -> Unit,
    onOpenReceiptGallery: () -> Unit,
    receiptAcquisitionEnabled: Boolean,
    onDirtyChanged: (Boolean) -> Unit,
    onDraftExpenseChanged: (ExpenseInput?) -> Unit,
    restoredDraft: ExpenseInput?,
    ocrApplyCaptureId: String?,
    onCancel: () -> Unit,
    onSave: (ExpenseInput) -> Unit
) {
    val restoredInput = restoredDraft
    var expenseDate by remember(editingExpense, initialCategory, resetKey, restoredInput?.id, ocrApplyCaptureId) { mutableStateOf(restoredInput?.expenseDate ?: editingExpense?.expenseDate ?: reportDate) }
    var supplier by remember(editingExpense, initialCategory, resetKey, restoredInput?.id, ocrApplyCaptureId) { mutableStateOf(restoredInput?.supplierName ?: editingExpense?.supplierName.orEmpty()) }
    var category by remember(editingExpense, initialCategory, resetKey, restoredInput?.id) { mutableStateOf(restoredInput?.category ?: editingExpense?.category ?: initialCategory) }
    var paymentMethod by remember(editingExpense, initialCategory, resetKey, restoredInput?.id) { mutableStateOf(normalizePaymentMethod(restoredInput?.paymentMethod ?: editingExpense?.paymentMethod)) }
    var amount by remember(editingExpense, initialCategory, resetKey, restoredInput?.id, ocrApplyCaptureId) { mutableStateOf(restoredInput?.amount ?: editingExpense?.amount?.takeIf { it > 0L }?.toString().orEmpty()) }
    var memo by remember(editingExpense, initialCategory, resetKey, restoredInput?.id) { mutableStateOf(restoredInput?.memo ?: editingExpense?.memo.orEmpty()) }
    var isCustomSupplier by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(false) }
    val supplierFocusRequester = remember { FocusRequester() }
    val amountFocusRequester = remember { FocusRequester() }
    var candidateToHide by remember { mutableStateOf<SupplierCandidateRecord?>(null) }
    val newExpenseId = remember(reportDate, initialCategory, resetKey, restoredInput?.id) { restoredInput?.id ?: UUID.randomUUID().toString() }
    val expenseId = editingExpense?.id ?: newExpenseId
    val candidates = remember(category, supplierCandidates) { supplierCandidatesFor(category, supplierCandidates) }
    val canAddCandidate = isCustomSupplier && supplier.trim().isNotBlank() && candidates.none { it.name == supplier.trim() }
    val initialSupplier = editingExpense?.supplierName.orEmpty()
    val initialExpenseDate = editingExpense?.expenseDate ?: reportDate
    val initialCategoryValue = editingExpense?.category ?: initialCategory
    val initialPaymentMethod = normalizePaymentMethod(editingExpense?.paymentMethod)
    val initialAmount = editingExpense?.amount?.takeIf { it > 0L }?.toString().orEmpty()
    val initialMemo = editingExpense?.memo.orEmpty()
    val formDirty = expenseDate != initialExpenseDate ||
        supplier != initialSupplier ||
        category != initialCategoryValue ||
        paymentMethod != initialPaymentMethod ||
        amount != initialAmount ||
        memo != initialMemo
    val parsedAmount = amount.toLongOrNull()
    val isAmountValid = parsedAmount != null && parsedAmount > 0L
    val isExpenseDateValid = runCatching { LocalDate.parse(expenseDate) }.isSuccess
    val restoredReceiptId = restoredInput?.receiptId ?: editingExpense?.receiptId.orEmpty()
    val currentExpenseInput = ExpenseInput(
        id = expenseId,
        expenseDate = expenseDate,
        category = category,
        supplierName = supplier,
        amount = amount,
        paymentMethod = normalizePaymentMethod(paymentMethod),
        memo = memo,
        receiptId = restoredReceiptId,
        sourceType = restoredInput?.sourceType ?: editingExpense?.sourceType ?: ExpenseSourceType.Manual,
        createdAt = restoredInput?.createdAt ?: editingExpense?.createdAt
    )
    val shouldRetainDraft = shouldRetainExpenseDraft(
        formDirty = formDirty,
        restoredDraftId = restoredInput?.id,
        currentExpenseId = currentExpenseInput.id
    )

    LaunchedEffect(shouldRetainDraft, currentExpenseInput) {
        onDirtyChanged(shouldRetainDraft)
        onDraftExpenseChanged(currentExpenseInput.takeIf { shouldRetainDraft })
    }
    candidateToHide?.let { candidate ->
        AlertDialog(
            onDismissRequest = { candidateToHide = null },
            title = { Text("候補を削除") },
            text = { Text("この候補をリストから非表示にしますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        candidateToHide = null
                        onHideSupplierCandidate(candidate)
                    }
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { candidateToHide = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
            Text(if (editingExpense == null) "支出を追加" else "支出を編集", fontWeight = FontWeight.Bold)
            Text("候補", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { candidate ->
                    val selectCandidate = {
                        val isOther = candidate.name == "他"
                        isCustomSupplier = isOther
                        supplier = if (isOther) "" else candidate.name
                        candidate.category?.let { category = it }
                        paymentMethod = normalizePaymentMethod(candidate.paymentMethod)
                        if (isOther) {
                            supplierFocusRequester.requestFocus()
                        } else {
                            amountFocusRequester.requestFocus()
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.combinedClickable(
                            onClick = selectCandidate,
                            onLongClick = {
                                candidate.record?.let { candidateToHide = it }
                            }
                        )
                    ) {
                        Text(
                            text = candidate.name,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            AppTextField("支払先", supplier, modifier = Modifier.focusRequester(supplierFocusRequester)) { supplier = it }
            DateInputTextField(
                label = "支出日（yyyy-MM-dd）",
                value = expenseDate
            ) {
                expenseDate = it
            }
            if (!isExpenseDateValid) {
                Text("正しい支出日を入力してください", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Text("支払方法", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                paymentMethodOptions.forEach { option ->
                    val selected = paymentMethod == option
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.clickable { paymentMethod = option }
                    ) {
                        Text(
                            text = option,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            AppTextField("金額", amount, KeyboardType.Number, Modifier.focusRequester(amountFocusRequester), clearZeroOnFocus = true) { value ->
                amount = value.filter { it.isDigit() }
            }
            if (amount.isBlank()) {
                Text("1円以上の金額を入力してください", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            } else if (!isAmountValid) {
                Text("1円以上の金額を入力してください", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            AppTextField("メモ", memo) { memo = it }
            OutlinedButton(
                onClick = {
                    onDraftExpenseChanged(currentExpenseInput)
                    onDirtyChanged(true)
                    onOpenReceiptCamera()
                },
                enabled = receiptAcquisitionEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("レシートを撮影")
            }
            OutlinedButton(
                onClick = {
                    onDraftExpenseChanged(currentExpenseInput)
                    onDirtyChanged(true)
                    onOpenReceiptGallery()
                },
                enabled = receiptAcquisitionEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("画像から取り込む")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = isExpenseDateValid && isAmountValid && isSupportedPaymentMethod(paymentMethod),
                    onClick = {
                        onSave(
                            ExpenseInput(
                                id = expenseId,
                                expenseDate = expenseDate,
                                category = category,
                                supplierName = supplier,
                                amount = amount,
                                paymentMethod = normalizePaymentMethod(paymentMethod),
                                memo = memo,
                                receiptId = restoredReceiptId,
                                sourceType = restoredInput?.sourceType ?: editingExpense?.sourceType ?: ExpenseSourceType.Manual,
                                createdAt = restoredInput?.createdAt ?: editingExpense?.createdAt
                            )
                        )
                    }
                ) { Text("保存") }
                OutlinedButton(onClick = onCancel) { Text("キャンセル") }
                OutlinedButton(onClick = onClose) { Text("閉じる") }
                if (canAddCandidate) {
                    OutlinedButton(
                        onClick = { onAddSupplierCandidate(category, supplier.trim(), normalizePaymentMethod(paymentMethod)) },
                        enabled = canAddCandidate
                    ) { Text("候補に追加") }
                }
            }
        }
    }
}

internal fun shouldRetainExpenseDraft(
    formDirty: Boolean,
    restoredDraftId: String?,
    currentExpenseId: String
): Boolean = formDirty || restoredDraftId == currentExpenseId

internal data class ReceiptOcrMergeSummary(
    val filledFields: List<String>,
    val keptFields: List<String>,
    val matchingFields: List<String>
) {
    fun toFeedbackText(): String {
        val filled = filledFields.ifEmpty { listOf("なし") }.joinToString("・")
        val kept = keptFields.ifEmpty { listOf("なし") }.joinToString("・")
        val matching = matchingFields.ifEmpty { listOf("なし") }.joinToString("・")
        return "OCRで補完: $filled。現在入力を維持: $kept。一致: $matching。カテゴリ・支払方法・メモは変更していません。"
    }
}

internal fun summarizeReceiptOcrMerge(
    current: ExpenseInput,
    result: com.warun.accounting.ui.receipt.ReceiptOcrApplyResult
): ReceiptOcrMergeSummary {
    val fields = planReceiptOcrMerge(current, result).fields
    return ReceiptOcrMergeSummary(
        filledFields = fields.filter { it.action == ReceiptOcrMergeAction.FillFromOcr }.map { it.label },
        keptFields = fields.filter { it.action == ReceiptOcrMergeAction.KeepCurrent }.map { it.label },
        matchingFields = fields.filter { it.action == ReceiptOcrMergeAction.Match }.map { it.label }
    )
}
@Composable
private fun CashManagementCard(
    input: DailyReportInput,
    totals: DailyReportTotals,
    onInputChange: (DailyReportInput) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    FormCard(modifier = modifier) {
        Text("現金管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveFormFields { fieldModifier ->
            AppTextField("営業開始時現金", input.openingCash, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                onInputChange(input.copy(openingCash = it))
            }
            AppTextField("実際の終了時現金", input.actualClosingCash, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                onInputChange(input.copy(actualClosingCash = it))
            }
        }
        TotalRow("現金売上", totals.cashSales.toYen())
        TotalRow("現金支出", totals.cashExpense.toYen())
        TotalRow("理論上の終了時現金", totals.theoreticalClosingCash.toYen())
        TotalRow("現金差額", totals.cashDifference.toYen())
    }
}
@Composable
private fun BusinessInfoCard(
    input: DailyReportInput,
    customerUnitPrice: Long,
    onInputChange: (DailyReportInput) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    FormCard(modifier = modifier) {
        Text("営業情報", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveFormFields { fieldModifier ->
            AppTextField("組数", input.groupCount, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                onInputChange(input.copy(groupCount = it))
            }
            AppTextField("来客数", input.customerCount, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                onInputChange(input.copy(customerCount = it))
            }
        }
        TotalRow("客単価", customerUnitPrice.toYen())
        AppTextField("メモ", input.memo) {
            onInputChange(input.copy(memo = it))
        }
    }
}
@Composable
private fun ReceiptScreen(
    uiState: DashboardUiState,
    onNavigate: (String) -> Unit,
    onSaveReceipt: (ReceiptInput, (Result<Unit>) -> Unit) -> Unit,
    onOpenReceiptCamera: () -> Unit,
    capturedReceipt: ReceiptCaptureResult? = null,
    onCaptureCleared: () -> Unit,
    inputStateViewModel: InputStateViewModel = hiltViewModel(),
    receiptOcrViewModel: ReceiptOcrViewModel = hiltViewModel()
) {
    var input by inputStateViewModel.receiptInputState
    var isSaving by inputStateViewModel.receiptSavingState
    var saveFeedback by remember { mutableStateOf<SaveFeedback?>(null) }
    val context = LocalContext.current
    val captureStartCoordinator = remember(context) {
        ReceiptCaptureStartCoordinator(
            journalProtectedReceiptImageStore(context)
        )
    }

    fun startNewReceiptCapture() {
        val started = captureStartCoordinator.startNewCapture(
            capturesToDiscard = listOf(
                capturedReceipt,
                receiptOcrViewModel.uiState.value.captureOrNull
            ),
            clearSessionState = {
                receiptOcrViewModel.clear()
                onCaptureCleared()
            },
            openCamera = onOpenReceiptCamera
        )
        if (!started) {
            saveFeedback = SaveFeedback(
                title = "撮影を開始できませんでした",
                body = "前回の未確定画像を削除できませんでした。入力内容は保持されています。もう一度お試しください。",
                isError = true
            )
        }
    }

    fun saveReceipt(isConfirmed: Boolean) {
        if (isSaving) return
        val savedInput = input.copy(isConfirmed = isConfirmed)
        isSaving = true
        onSaveReceipt(savedInput) { result ->
            isSaving = false
            input = if (result.isSuccess) inputStateViewModel.completeReceiptSave() else input
            saveFeedback = if (result.isSuccess) {
                SaveFeedback(
                    title = "レシートを保存しました",
                    body = "レシート情報を保存しました。",
                    isError = false
                )
            } else {
                SaveFeedback(
                    title = "保存できませんでした",
                    body = "レシートを保存できませんでした。入力内容を確認して、もう一度お試しください。",
                    isError = true
                )
            }
        }
    }

    saveFeedback?.let { feedback ->
        SaveFeedbackOverlay(feedback = feedback, onDismiss = { saveFeedback = null })
    }

    ScreenColumn {
        ScreenTitle("レシート", "撮影した画像から文字を読み取り、全文を確認できます。")
        Button(onClick = ::startNewReceiptCapture, modifier = Modifier.fillMaxWidth()) {
            Text("レシートを撮影")
        }
        ReceiptOcrPanel(
            capturedReceipt = capturedReceipt,
            onCaptureCleared = onCaptureCleared,
            onOpenReceiptCamera = ::startNewReceiptCapture,
            knownStoreNames = receiptParserStoreNames(uiState.supplierCandidates),
            viewModel = receiptOcrViewModel
        )
        FormCard {
            Text("仮レシート登録", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "購入日が不明な場合は空欄のまま保存すると、日付未確認として月別整理に表示します。登録時刻は保存時に自動記録します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AdaptiveFormFields { fieldModifier ->
                DateInputTextField(
                    label = "購入日 yyyy-MM-dd",
                    value = input.purchaseDate,
                    modifier = fieldModifier
                ) {
                    input = input.copy(purchaseDate = it)
                }
                DateInputTextField(
                    label = "撮影日 yyyy-MM-dd",
                    value = input.capturedDate,
                    modifier = fieldModifier
                ) {
                    input = input.copy(capturedDate = it)
                }
                AppTextField("店名", input.storeName, modifier = fieldModifier) {
                    input = input.copy(storeName = it)
                }
                AppTextField("合計金額", input.totalAmount, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    input = input.copy(totalAmount = it)
                }
                AppTextField("消費税", input.taxAmount, KeyboardType.Number, fieldModifier, clearZeroOnFocus = true) {
                    input = input.copy(taxAmount = it)
                }
                AppTextField("登録番号", input.registrationNumber, modifier = fieldModifier) {
                    input = input.copy(registrationNumber = it)
                }
                AppTextField("経費カテゴリ", input.expenseCategory, modifier = fieldModifier) {
                    input = input.copy(expenseCategory = it)
                }
            }
            AppTextField("メモ", input.memo) {
                input = input.copy(memo = it)
            }
        }
        SaveActionCard(
            draftLabel = if (isSaving) "保存中…" else "日付未確認で保存",
            completeLabel = if (isSaving) "保存中…" else "確認済みで保存",
            onSaveDraft = { saveReceipt(false) },
            onSaveComplete = { saveReceipt(true) },
            isSaving = isSaving
        )
        DashboardCard {
            Text("OCR結果は確認用です。内容の自動入力と保存は今後のフェーズで追加します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ResponsivePrimaryAction("日報入力へ移動", onClick = { onNavigate(AppDestination.ReportEntry.route) })
        }
        ReceiptList(uiState.receipts.take(8))
    }
}@Composable
private fun ReceiptList(receipts: List<ReceiptRecord>) {
    DashboardCard {
        Text("最近のレシート", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (receipts.isEmpty()) {
            Text("レシートはまだ登録されていません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            receipts.forEach { receipt ->
                TotalRow(receipt.purchaseDate ?: "日付未確認", receipt.totalAmount.toYen())
                Text(
                    text = listOfNotNull(
                        receipt.storeName,
                        receipt.expenseCategory,
                        if (receipt.isConfirmed) "確認済み" else "未確認"
                    ).joinToString(" / "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
@Composable
private fun BalanceScreen(uiState: DashboardUiState) {
    val today = remember { LocalDate.now() }
    var periodMode by remember { mutableStateOf(BalancePeriodMode.Today) }
    var customStartDate by remember { mutableStateOf(today.toString()) }
    var customEndDate by remember { mutableStateOf(today.toString()) }
    val period = remember(periodMode, customStartDate, customEndDate, today) {
        selectedBalancePeriod(periodMode, customStartDate, customEndDate, today)
    }
    val summary = remember(uiState.reports, uiState.expenses, period) {
        buildBalanceSummary(uiState.reports, uiState.expenses, period)
    }

    ScreenColumn {
        ScreenTitle("収支確認", "日別、月別、期間指定で売上、支出、現金差額を確認します。")
        BalancePeriodSelector(
            selectedMode = periodMode,
            periodLabel = summary.periodLabel,
            customStartDate = customStartDate,
            customEndDate = customEndDate,
            onModeChange = { periodMode = it },
            onCustomStartChange = { customStartDate = it },
            onCustomEndChange = { customEndDate = it }
        )
        BalanceSummaryCards(summary)
        BusinessAnalysisCard(summary.businessAnalysis)
        AdaptiveGrid {
            ExpenseBreakdown(summary.categoryTotals)
            DailyBalanceList(summary.dailyRows)
        }
    }
}
@Composable
private fun BalancePeriodSelector(
    selectedMode: BalancePeriodMode,
    periodLabel: String,
    customStartDate: String,
    customEndDate: String,
    onModeChange: (BalancePeriodMode) -> Unit,
    onCustomStartChange: (String) -> Unit,
    onCustomEndChange: (String) -> Unit
) {
    DashboardCard {
        Text("期間切替", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BalancePeriodMode.entries.forEach { mode ->
                PeriodModeButton(
                    mode = mode,
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) }
                )
            }
        }
        if (selectedMode == BalancePeriodMode.Custom) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateInputTextField(
                    label = "開始日 yyyy-MM-dd",
                    value = customStartDate
                ) {
                    onCustomStartChange(it)
                }
                DateInputTextField(
                    label = "終了日 yyyy-MM-dd",
                    value = customEndDate
                ) {
                    onCustomEndChange(it)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("対象期間", fontWeight = FontWeight.Bold)
            Text(periodLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun PeriodModeButton(
    mode: BalancePeriodMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(mode.label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(mode.label)
        }
    }
}
@Composable
private fun BalanceSummaryCards(summary: BalanceSummary) {
    DashboardCard {
        Text("集計サマリー", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveSummaryGrid { cardModifier ->
            SummaryCard("売上合計", summary.salesTotal.toYen(), modifier = cardModifier)
            SummaryCard("支出合計", summary.expenseTotal.toYen(), modifier = cardModifier)
            SummaryCard("差額", summary.balance.toYen(), modifier = cardModifier)
            SummaryCard("現金売上", summary.cashSales.toYen(), modifier = cardModifier)
            SummaryCard("現金支出", summary.cashExpense.toYen(), modifier = cardModifier)
            SummaryCard("理論上の現金残高", summary.theoreticalCashBalance.toYen(), modifier = cardModifier)
            SummaryCard("実際の現金残高", summary.actualCashBalance.toYen(), modifier = cardModifier)
            SummaryCard("現金差額", summary.cashDifference.toYen(), modifier = cardModifier)
            SummaryCard("未確認レシート件数", "${summary.unconfirmedReceiptCount}件", modifier = cardModifier)
        }
    }
}

@Composable
private fun BusinessAnalysisCard(summary: BusinessAnalysisSummary) {
    DashboardCard {
        Text(
            "経営分析（概算）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "食材仕入・酒類仕入を原価として、選択期間の参考値を計算します。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AdaptiveSummaryGrid { cardModifier ->
            SummaryCard("売上合計", summary.salesTotal.toYen(), modifier = cardModifier)
            SummaryCard(
                "概算原価",
                summary.estimatedCost?.toYen() ?: "計算不可",
                modifier = cardModifier
            )
            SummaryCard(
                "概算原価率",
                formatBusinessRate(summary.estimatedCostRate),
                modifier = cardModifier
            )
            SummaryCard(
                "概算粗利",
                summary.estimatedGrossProfit?.toYen() ?: "計算不可",
                modifier = cardModifier
            )
            SummaryCard(
                "概算粗利率",
                formatBusinessRate(summary.estimatedGrossMargin),
                modifier = cardModifier
            )
            SummaryCard(
                "固定費相当額（簡易）",
                summary.simpleFixedCost?.toYen() ?: "計算不可",
                modifier = cardModifier
            )
            SummaryCard(
                "概算損益分岐点売上",
                summary.estimatedBreakEvenSales?.toYen() ?: "計算不可",
                modifier = cardModifier
            )
        }
        Text("損益分岐点との差", style = MaterialTheme.typography.labelLarge)
        Text(summary.breakEvenStatusMessage(), fontWeight = FontWeight.Bold)
        Text(
            "概算値です。食材・酒類仕入を原価として計算しています。棚卸、人件費、費用の固定費・変動費分類は反映していません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable
private fun ExpenseBreakdown(categoryTotals: List<Pair<String, Long>>) {
    DashboardCard {
        Text("経費カテゴリ別内訳", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        categoryTotals.forEach { (label, total) ->
            TotalRow(label, total.toYen())
        }
    }
}
@Composable
private fun DailyBalanceList(rows: List<DailyBalanceRow>) {
    DashboardCard {
        Text("日別一覧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (rows.isEmpty()) {
            Text("対象期間の日報と支出はまだありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            rows.forEach { row ->
                TotalRow(row.reportDate, row.balance.toYen())
                Text(
                    text = "売上 ${row.salesTotal.toYen()} / 支出 ${row.expenseTotal.toYen()} / 現金差額 ${row.cashDifference.toYen()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
@Composable
private fun MonthlyOrganizationScreen(
    uiState: DashboardUiState,
    onMarkSubmitted: (String) -> Unit,
    onOpenSubmit: () -> Unit
) {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val summary = remember(uiState, selectedMonth) {
        buildMonthlyOrganizationSummary(uiState, selectedMonth)
    }

    ScreenColumn {
        ScreenTitle("月別整理", "月末に対象月を選び、税理士へ渡す前の不足を確認します。")
        ReportMonthSelector(
            selectedMonth = selectedMonth,
            onPreviousMonth = { selectedMonth = selectedMonth.minusMonths(1) },
            onNextMonth = { selectedMonth = selectedMonth.plusMonths(1) },
            onThisMonth = { selectedMonth = YearMonth.now() }
        )
        DashboardCard {
            Text("整理状況", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            AdaptiveSummaryGrid { cardModifier ->
                SummaryCard("未確認レシート", "${summary.unconfirmedReceipts}件", modifier = cardModifier)
                SummaryCard("日付未確認", "${summary.dateUnknownReceipts}件", modifier = cardModifier)
                SummaryCard("下書き日報", "${summary.draftReports}件", modifier = cardModifier)
                SummaryCard("提出状況", summary.submissionLabel, modifier = cardModifier)
            }
        }
        DashboardCard {
            Text("月別集計", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TotalRow("売上合計", summary.salesTotal.toYen())
            TotalRow("日報支出", summary.reportExpenses.toYen())
            TotalRow("レシート支出", summary.receiptExpenses.toYen())
            TotalRow("概算差額", summary.balance.toYen())
        }
        SaveActionCard(
            draftLabel = "税理士提出へ進む",
            completeLabel = "この月を提出済みにする",
            onSaveDraft = onOpenSubmit,
            onSaveComplete = { onMarkSubmitted(summary.targetMonth) }
        )
        DailyReportList(summary.monthReports.take(8))
        ReceiptList(summary.monthReceipts.take(8))
    }
}
@Composable
private fun ReportListScreen(
    uiState: DashboardUiState,
    onOpenDate: (String) -> Unit
) {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val rows = remember(uiState.reports, uiState.expenses, selectedMonth) {
        buildMonthlyReportRows(uiState.reports, uiState.expenses, selectedMonth)
    }

    ScreenColumn {
        ScreenTitle("日報一覧", "月ごとの日報を日別に確認します。")
        ReportMonthSelector(
            selectedMonth = selectedMonth,
            onPreviousMonth = { selectedMonth = selectedMonth.minusMonths(1) },
            onNextMonth = { selectedMonth = selectedMonth.plusMonths(1) },
            onThisMonth = { selectedMonth = YearMonth.now() }
        )
        MonthlyReportList(rows = rows, onOpenDate = onOpenDate)
    }
}
@Composable
private fun ReportMonthSelector(
    selectedMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onThisMonth: () -> Unit
) {
    DashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPreviousMonth) {
                Text("前月")
            }
            Text(
                text = selectedMonth.toJapaneseMonthLabel(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onNextMonth) {
                Text("翌月")
            }
        }
        Button(onClick = onThisMonth, modifier = Modifier.fillMaxWidth()) {
            Text("今月を表示")
        }
    }
}
@Composable
private fun MonthlyReportList(
    rows: List<MonthlyReportRow>,
    onOpenDate: (String) -> Unit
) {
    DashboardCard {
        Text("日別一覧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (rows.isEmpty()) {
            Text("表示できる日付はまだありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            rows.forEach { row ->
                MonthlyReportRowCard(row = row, onOpenDate = onOpenDate)
            }
        }
    }
}
@Composable
private fun MonthlyReportRowCard(
    row: MonthlyReportRow,
    onOpenDate: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDFF)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDate(row.reportDate) }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.reportDate,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SaveStatePill(row.saveState)
            }
            AdaptiveMiniGrid {
                MiniAmountCard("売上合計", row.salesTotal)
                MiniAmountCard("支出合計", row.expenseTotal)
                MiniAmountCard("差額", row.balance)
                MiniAmountCard("現金差額", row.cashDifference)
            }
        }
    }
}
@Composable
private fun SaveStatePill(saveState: String) {
    val saved = saveState != "未入力"
    Surface(
        color = if (saved) Color(0xFFEAF1FF) else Color(0xFFF1F5F9),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = saveState,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
@Composable
private fun ReportDetailScreen(
    uiState: DashboardUiState,
    reportDate: String,
    onBack: () -> Unit,
    onEntry: () -> Unit
) {
    val dayReports = remember(uiState.reports, reportDate) {
        uiState.reports.filter { it.reportDate == reportDate }
    }
    val dayReceipts = remember(uiState.receipts, reportDate) {
        uiState.receipts.filter { it.purchaseDate == reportDate }
    }
    val dayExpenses = remember(uiState.expenses, reportDate) {
        uiState.expenses.filter { it.expenseDate == reportDate }
    }
    val row = remember(dayReports, dayExpenses, reportDate) {
        buildDailyBalanceRow(reportDate, dayReports, dayExpenses)
    }

    ScreenColumn {
        ScreenTitle("日報詳細", reportDate)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack) {
                Text("一覧へ戻る")
            }
            Button(onClick = onEntry) {
                Text("この日付で入力")
            }
        }
        DashboardCard {
            TotalRow("保存状態", dailyReportSaveState(dayReports))
            TotalRow("売上合計", row.salesTotal.toYen())
            TotalRow("支出合計", row.expenseTotal.toYen())
            TotalRow("差額", row.balance.toYen())
            TotalRow("現金差額", row.cashDifference.toYen())
            if (dayReports.isEmpty()) {
                Text("この日付の日報はまだ保存されていません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        dayReports.forEachIndexed { index, report ->
            DailyReportDetailCard(index = index, report = report, expenses = dayExpenses)
        }
    }
}
@Composable
private fun DailyReportDetailCard(index: Int, report: DailyReport, expenses: List<ExpenseRecord>) {
    DashboardCard {
        Text("日報 ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TotalRow("ステータス", report.status.toReportStatusLabel())
        TotalRow("売上合計", report.totalSales().toYen())
        TotalRow("現金売上", report.cashSales.toYen())
        TotalRow("クレジットカード売上", report.cardSales.toYen())
        TotalRow("QR決済売上", report.qrSales.toYen())
        TotalRow("売掛売上", report.accountsReceivableSales.toYen())
        TotalRow("その他売上", report.otherSales.toYen())
        TotalRow("支出合計", report.totalExpense(expenses).toYen())
        TotalRow("食材仕入", report.detailExpense(expenses, FoodPurchaseCategory).toYen())
        TotalRow("酒類仕入", report.detailExpense(expenses, AlcoholPurchaseCategory).toYen())
        TotalRow("消耗品費", expenses.preferredExpenseAmount(report.reportDate, ConsumablesCategory, report.consumablesExpense).toYen())
        TotalRow(if (report.isLegacyUtilityExpense()) "水道光熱費（旧形式）" else "水道光熱費", report.utilityExpenseTotal().toYen())
        TotalRow("電気代", report.electricityExpense.toYen())
        TotalRow("ガス代", report.gasExpense.toYen())
        TotalRow("水道代", report.waterExpense.toYen())
        TotalRow("通信費", report.communicationExpense.toYen())
        TotalRow("家賃", report.rentExpense.toYen())
        TotalRow("税理士顧問料", report.accountantFeeExpense.toYen())
        TotalRow("車両・交通費", report.detailExpense(expenses, VehicleTransportCategory).toYen())
        TotalRow("雑費", report.miscellaneousExpense.toYen())
        TotalRow("その他支出", report.detailExpense(expenses, OtherExpenseCategory).toYen())
        TotalRow("営業開始時現金", report.openingCash.toYen())
        TotalRow("実際の終了時現金", if (report.hasActualClosingCash) report.actualClosingCash.toYen() else "未入力")
        TotalRow("来客数", "${report.customerCount}名")
        TotalRow("組数", "${report.groupCount}組")
        if (!report.authorName.isNullOrBlank()) {
            Text("記入者: ${report.authorName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!report.memo.isNullOrBlank()) {
            Text(report.memo, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun SubmitScreen(
    uiState: DashboardUiState,
    onMarkSubmitted: (String) -> Unit,
    onOpenMonthlyOrganization: () -> Unit
) {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val summary = remember(uiState, selectedMonth) {
        buildMonthlyOrganizationSummary(uiState, selectedMonth)
    }

    ScreenColumn {
        ScreenTitle("税理士へ提出", "月末に対象月を選んで提出するための確認画面です。")
        ReportMonthSelector(
            selectedMonth = selectedMonth,
            onPreviousMonth = { selectedMonth = selectedMonth.minusMonths(1) },
            onNextMonth = { selectedMonth = selectedMonth.plusMonths(1) },
            onThisMonth = { selectedMonth = YearMonth.now() }
        )
        DashboardCard {
            TotalRow("対象月", summary.targetMonth)
            TotalRow("売上合計", summary.salesTotal.toYen())
            TotalRow("支出合計", (summary.reportExpenses + summary.receiptExpenses).toYen())
            TotalRow("概算差額", summary.balance.toYen())
            TotalRow("提出状況", summary.submissionLabel)
            Text("CSV、PDF、ZIP出力は次フェーズで追加します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SaveActionCard(
            draftLabel = "月別整理を続ける",
            completeLabel = "提出済みにする",
            onSaveDraft = onOpenMonthlyOrganization,
            onSaveComplete = { onMarkSubmitted(summary.targetMonth) }
        )
        DailyReportList(summary.monthReports.take(5))
    }
}
@Composable
private fun SettingsScreen(
    uiState: DashboardUiState,
    onSave: (AppSettingsInput) -> Unit
) {
    val settings = uiState.appSettings
    var input by remember(settings) {
        mutableStateOf(
            AppSettingsInput(
                storeName = settings?.storeName.orEmpty(),
                ownerName = settings?.ownerName.orEmpty(),
                address = settings?.address.orEmpty(),
                accountantNote = settings?.accountantNote.orEmpty(),
                useCashPayment = settings?.useCashPayment ?: true,
                useCardPayment = settings?.useCardPayment ?: false,
                useQrPayment = settings?.useQrPayment ?: false,
                useAccountsReceivablePayment = settings?.useAccountsReceivablePayment ?: false,
                useOtherPayment = settings?.useOtherPayment ?: false
            )
        )
    }
    ScreenColumn {
        ScreenTitle("簡易設定", "店舗名と日報入力に表示する決済方法を設定します。")
        FormCard {
            Text("店舗情報", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            AppTextField("店舗名", input.storeName) { input = input.copy(storeName = it) }
        }
        FormCard {
            Text("使用する決済方法", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "ONにした決済方法だけ、日報入力の売上項目に表示します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingSwitch("現金", input.useCashPayment) {
                input = input.copy(useCashPayment = it)
            }
            SettingSwitch("クレジットカード", input.useCardPayment) {
                input = input.copy(useCardPayment = it)
            }
            SettingSwitch("QR決済", input.useQrPayment) {
                input = input.copy(useQrPayment = it)
            }
            SettingSwitch("売掛", input.useAccountsReceivablePayment) {
                input = input.copy(useAccountsReceivablePayment = it)
            }
            SettingSwitch("その他", input.useOtherPayment) {
                input = input.copy(useOtherPayment = it)
            }
            PrimaryActionButton(
                label = "設定を保存",
                onClick = { onSave(input) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
@Composable
private fun MoneySection(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    totalLabel: String,
    total: Long,
    items: List<Pair<String, Long>>
) {
    DashboardCard {
        CardHeader(title, actionLabel, onAction)
        AdaptiveMiniGrid {
            items.forEach { (label, value) -> MiniAmountCard(label, value) }
        }
        TotalRow(totalLabel, total.toYen())
    }
}
@Composable
private fun CashSection(uiState: DashboardUiState) {
    DashboardCard {
        Text("現金管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdaptiveMiniGrid {
            MiniAmountCard("開始残高", uiState.latestReport?.openingCash ?: 0L)
            MiniAmountCard("現金売上", uiState.cashSales)
            MiniAmountCard("現金支出", uiState.cashExpenses)
        }
        TotalRow("終了残高", uiState.closingCash.toYen())
    }
}
@Composable
private fun DailyReportList(reports: List<DailyReport>) {
    DashboardCard {
        Text("日報一覧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (reports.isEmpty()) {
            Text("日報がまだありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            reports.forEach { report ->
                TotalRow(
                    label = "${report.reportDate} / ${report.customerCount}名 / ${report.groupCount}組",
                    value = report.totalSales().toYen()
                )
                if (!report.memo.isNullOrBlank()) {
                    Text(report.memo, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenPadding = if (maxWidth < 720.dp) 14.dp else 24.dp
        val itemSpacing = if (maxWidth < 720.dp) 12.dp else 16.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(screenPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(itemSpacing), content = content)
            }
        }
    }
}
@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun AdaptiveGrid(content: @Composable () -> Unit) {
    BoxWithConstraints {
        if (maxWidth < 900.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}
@Composable
private fun AdaptiveMiniGrid(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}
@Composable
private fun AdaptiveSummaryGrid(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardModifier = if (maxWidth < 520.dp) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width((maxWidth - 8.dp) / 2f)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            content(cardModifier)
        }
    }
}
@Composable
private fun AdaptiveFormCardLayout(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardModifier = if (maxWidth < 720.dp) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width((maxWidth - 12.dp) / 2f)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            content(cardModifier)
        }
    }
}
@Composable
private fun AdaptiveFormFields(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fieldModifier = if (maxWidth < 520.dp) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(232.dp)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            content(fieldModifier)
        }
    }
}
@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
@Composable
private fun FormCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit
) {
    DashboardCard(modifier = modifier, content = content)
}
@Composable
private fun CardHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}
@Composable
private fun MiniAmountCard(label: String, value: Long) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDFF)),
        modifier = Modifier.width(132.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value.toYen(), fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun UtilityTotalRow(value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("水道光熱費", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("合計 $value", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable
private fun TotalRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun AppTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    clearZeroOnFocus: Boolean = false,
    onValueChange: (String) -> Unit
) {
    val shouldClearZeroOnFocus = clearZeroOnFocus && keyboardType == KeyboardType.Number
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = modifier.onFocusChanged { focusState ->
            if (shouldClearZeroOnFocus) {
                if (focusState.isFocused && !wasFocused && value == "0") {
                    onValueChange("")
                }
                if (!focusState.isFocused && wasFocused && value.isBlank()) {
                    onValueChange("0")
                }
            }
            wasFocused = focusState.isFocused
        },
        singleLine = label != "営業メモ" && label != "メモ"
    )
}

private enum class BalancePeriodMode(val label: String) {
    Today("今日"),
    Yesterday("昨日"),
    ThisMonth("今月"),
    LastMonth("先月"),
    Custom("期間指定")
}

internal data class BalancePeriod(
    val start: LocalDate,
    val end: LocalDate
) {
    val label: String
        get() = if (start == end) start.toString() else "$start 〜 $end"

    fun contains(reportDate: String): Boolean {
        val date = parseDateOrNull(reportDate) ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }
}

internal data class BalanceSummary(
    val periodLabel: String,
    val salesTotal: Long,
    val expenseTotal: Long,
    val balance: Long,
    val cashSales: Long,
    val cashExpense: Long,
    val theoreticalCashBalance: Long,
    val actualCashBalance: Long,
    val cashDifference: Long,
    val categoryTotals: List<Pair<String, Long>>,
    val unconfirmedReceiptCount: Int,
    val dailyRows: List<DailyBalanceRow>,
    val businessAnalysis: BusinessAnalysisSummary
)

internal data class DailyBalanceRow(
    val reportDate: String,
    val salesTotal: Long,
    val expenseTotal: Long,
    val balance: Long,
    val cashDifference: Long
)

private data class MonthlyReportRow(
    val reportDate: String,
    val salesTotal: Long,
    val expenseTotal: Long,
    val balance: Long,
    val cashDifference: Long,
    val saveState: String
)

private data class MonthlyOrganizationSummary(
    val targetMonth: String,
    val salesTotal: Long,
    val reportExpenses: Long,
    val receiptExpenses: Long,
    val balance: Long,
    val unconfirmedReceipts: Int,
    val dateUnknownReceipts: Int,
    val draftReports: Int,
    val submissionLabel: String,
    val monthReports: List<DailyReport>,
    val monthReceipts: List<ReceiptRecord>
)

private data class PaymentVisibility(
    val useCashPayment: Boolean,
    val useCardPayment: Boolean,
    val useQrPayment: Boolean,
    val useAccountsReceivablePayment: Boolean,
    val useOtherPayment: Boolean
)

private data class DailyReportTotals(
    val cashSales: Long,
    val totalSales: Long,
    val expenseTotal: Long,
    val todayBalance: Long,
    val cashExpense: Long,
    val theoreticalClosingCash: Long,
    val actualClosingCash: Long,
    val cashDifference: Long,
    val customerUnitPrice: Long
)

private fun selectedBalancePeriod(
    mode: BalancePeriodMode,
    customStartDate: String,
    customEndDate: String,
    today: LocalDate
): BalancePeriod {
    return when (mode) {
        BalancePeriodMode.Today -> BalancePeriod(today, today)
        BalancePeriodMode.Yesterday -> {
            val yesterday = today.minusDays(1)
            BalancePeriod(yesterday, yesterday)
        }
        BalancePeriodMode.ThisMonth -> {
            val month = YearMonth.from(today)
            BalancePeriod(month.atDay(1), month.atEndOfMonth())
        }
        BalancePeriodMode.LastMonth -> {
            val month = YearMonth.from(today).minusMonths(1)
            BalancePeriod(month.atDay(1), month.atEndOfMonth())
        }
        BalancePeriodMode.Custom -> {
            val startDate = parseDateOrNull(customStartDate)
            val endDate = parseDateOrNull(customEndDate)
            when {
                startDate != null && endDate != null -> normalizedBalancePeriod(startDate, endDate)
                startDate != null -> BalancePeriod(startDate, startDate)
                endDate != null -> BalancePeriod(endDate, endDate)
                else -> BalancePeriod(today, today)
            }
        }
    }
}

private fun normalizedBalancePeriod(startDate: LocalDate, endDate: LocalDate): BalancePeriod {
    return if (startDate.isAfter(endDate)) {
        BalancePeriod(endDate, startDate)
    } else {
        BalancePeriod(startDate, endDate)
    }
}

private fun buildMonthlyOrganizationSummary(
    uiState: DashboardUiState,
    selectedMonth: YearMonth
): MonthlyOrganizationSummary {
    val targetMonth = selectedMonth.toString()
    val monthReports = uiState.reports.filter { it.reportDate.startsWith(targetMonth) }
    val monthReceipts = uiState.receipts.filter { it.purchaseDate?.startsWith(targetMonth) == true }
    val monthExpenses = uiState.expenses.filter { it.expenseDate.startsWith(targetMonth) }
    val salesTotal = monthReports.sumOf { it.totalSales() }
    val reportExpenses = monthReports.sumOf { it.totalExpense(monthExpenses) } + expensesWithoutReportsTotal(monthReports, monthExpenses)
    val receiptExpenses = 0L
    val submitted = uiState.monthlySubmissions.any {
        it.targetMonth == targetMonth && it.status == MonthlySubmissionStatus.Submitted
    }

    return MonthlyOrganizationSummary(
        targetMonth = targetMonth,
        salesTotal = salesTotal,
        reportExpenses = reportExpenses,
        receiptExpenses = receiptExpenses,
        balance = salesTotal - reportExpenses - receiptExpenses,
        unconfirmedReceipts = monthReceipts.count { !it.isConfirmed },
        dateUnknownReceipts = uiState.receipts.count { it.purchaseDate.isNullOrBlank() },
        draftReports = monthReports.count { it.status == DailyReportStatus.Draft },
        submissionLabel = if (submitted) "提出済み" else "未提出",
        monthReports = monthReports.sortedByDescending { it.reportDate },
        monthReceipts = monthReceipts.sortedByDescending { it.purchaseDate.orEmpty() }
    )
}
private fun AppSettings?.toPaymentVisibility(): PaymentVisibility =
    PaymentVisibility(
        useCashPayment = this?.useCashPayment ?: true,
        useCardPayment = this?.useCardPayment ?: false,
        useQrPayment = this?.useQrPayment ?: false,
        useAccountsReceivablePayment = this?.useAccountsReceivablePayment ?: false,
        useOtherPayment = this?.useOtherPayment ?: false
    )

private fun DailyReportInput.calculateTotals(
    paymentVisibility: PaymentVisibility,
    expenses: List<ExpenseRecord>
): DailyReportTotals {
    val cashSales = if (paymentVisibility.useCashPayment) this.cashSales.toInputLong() else 0L
    val totalSales = listOf(
        cashSales,
        if (paymentVisibility.useCardPayment) this.cardSales.toInputLong() else 0L,
        if (paymentVisibility.useQrPayment) this.qrSales.toInputLong() else 0L,
        if (paymentVisibility.useAccountsReceivablePayment) this.accountsReceivableSales.toInputLong() else 0L,
        if (paymentVisibility.useOtherPayment) this.otherSales.toInputLong() else 0L
    ).sum()
    val directExpenseTotal =
        this.utilityExpenseTotal() +
        this.communicationExpense.toInputLong() +
        this.rentExpense.toInputLong() +
        this.accountantFeeExpense.toInputLong() +
        this.miscellaneousExpense.toInputLong()
    val expenseTotal = detailExpense(this, expenses, FoodPurchaseCategory) +
        detailExpense(this, expenses, AlcoholPurchaseCategory) +
        expenses.preferredExpenseAmount(reportDate, ConsumablesCategory, consumablesExpense.toInputLong()) +
        detailExpense(this, expenses, OtherExpenseCategory) +
        detailExpense(this, expenses, VehicleTransportCategory) +
        directExpenseTotal
    val cashExpense = cashDetailExpense(this, expenses, FoodPurchaseCategory) +
        cashDetailExpense(this, expenses, AlcoholPurchaseCategory) +
        expenses.preferredCashExpenseAmount(reportDate, ConsumablesCategory, consumablesExpense.toInputLong()) +
        cashDetailExpense(this, expenses, OtherExpenseCategory) +
        cashDetailExpense(this, expenses, VehicleTransportCategory) +
        directExpenseTotal
    val theoreticalClosingCash = calculateCashBalance(this.openingCash.toInputLong(), cashSales, cashExpense)
    val actualClosingCash = this.actualClosingCash
        .takeIf { it.isNotBlank() }
        ?.toInputLong()
        ?: theoreticalClosingCash
    val cashDifference = actualClosingCash - theoreticalClosingCash
    val customerCount = this.customerCount.toInputLong()
    val customerUnitPrice = if (customerCount > 0) totalSales / customerCount else 0L

    return DailyReportTotals(
        cashSales = cashSales,
        totalSales = totalSales,
        expenseTotal = expenseTotal,
        todayBalance = totalSales - expenseTotal,
        cashExpense = cashExpense,
        theoreticalClosingCash = theoreticalClosingCash,
        actualClosingCash = actualClosingCash,
        cashDifference = cashDifference,
        customerUnitPrice = customerUnitPrice
    )
}

private fun DailyReportInput.withHiddenPaymentsCleared(paymentVisibility: PaymentVisibility): DailyReportInput =
    copy(
        cashSales = if (paymentVisibility.useCashPayment) cashSales else "",
        cardSales = if (paymentVisibility.useCardPayment) cardSales else "",
        qrSales = if (paymentVisibility.useQrPayment) qrSales else "",
        accountsReceivableSales = if (paymentVisibility.useAccountsReceivablePayment) accountsReceivableSales else "",
        otherSales = if (paymentVisibility.useOtherPayment) otherSales else ""
    )

internal fun buildBalanceSummary(
    reports: List<DailyReport>,
    expenses: List<ExpenseRecord>,
    period: BalancePeriod
): BalanceSummary {
    val periodReports = reports.filter { period.contains(it.reportDate) }
    val periodExpenses = expenses.filter { expense -> period.contains(expense.expenseDate) }
    val salesTotal = periodReports.sumOf { it.totalSales() }
    val expenseTotal = periodReports.sumOf { it.totalExpense(periodExpenses) } + expensesWithoutReportsTotal(periodReports, periodExpenses)
    val cashSales = periodReports.sumOf { it.cashSales }
    val cashExpense = periodReports.sumOf { it.cashExpense(periodExpenses) } + cashExpensesWithoutReportsTotal(periodReports, periodExpenses)
    val firstReport = periodReports.minWithOrNull(
        compareBy<DailyReport> { it.reportDate }.thenBy { it.createdAt }
    )
    val latestReport = periodReports.maxWithOrNull(
        compareBy<DailyReport> { it.reportDate }.thenBy { it.updatedAt }
    )
    val theoreticalCashBalance = calculateCashBalance(firstReport?.openingCash ?: 0L, cashSales, cashExpense)
    val actualCashBalance = latestReport?.takeIf { it.hasActualClosingCash }?.actualClosingCash ?: theoreticalCashBalance
    val categoryTotals = buildExpenseBreakdownTotals(periodReports, periodExpenses)
    val businessAnalysis = buildBusinessAnalysisSummary(
        salesTotal = salesTotal,
        expenseTotal = expenseTotal,
        periodExpenses = periodExpenses
    )
    val dailyRows = (periodReports.map { it.reportDate } + periodExpenses.map { it.expenseDate })
        .distinct()
        .sortedDescending()
        .map { reportDate ->
            buildDailyBalanceRow(
                reportDate = reportDate,
                reports = periodReports.filter { it.reportDate == reportDate },
                expenses = periodExpenses.filter { it.expenseDate == reportDate }
            )
        }

    return BalanceSummary(
        periodLabel = period.label,
        salesTotal = salesTotal,
        expenseTotal = expenseTotal,
        balance = salesTotal - expenseTotal,
        cashSales = cashSales,
        cashExpense = cashExpense,
        theoreticalCashBalance = theoreticalCashBalance,
        actualCashBalance = actualCashBalance,
        cashDifference = actualCashBalance - theoreticalCashBalance,
        categoryTotals = categoryTotals,
        unconfirmedReceiptCount = 0,
        dailyRows = dailyRows,
        businessAnalysis = businessAnalysis
    )
}

private fun buildDailyBalanceRow(
    reportDate: String,
    reports: List<DailyReport>,
    expenses: List<ExpenseRecord> = emptyList()
): DailyBalanceRow {
    val salesTotal = reports.sumOf { it.totalSales() }
    val expenseTotal = reports.sumOf { it.totalExpense(expenses) } + expensesWithoutReportsTotal(reports, expenses)
    val cashSales = reports.sumOf { it.cashSales }
    val firstReport = reports.minByOrNull { it.createdAt }
    val latestReport = reports.maxByOrNull { it.updatedAt }
    val cashExpense = reports.sumOf { it.cashExpense(expenses) } + cashExpensesWithoutReportsTotal(reports, expenses)
    val theoreticalCashBalance = calculateCashBalance(firstReport?.openingCash ?: 0L, cashSales, cashExpense)
    val actualCashBalance = latestReport?.takeIf { it.hasActualClosingCash }?.actualClosingCash ?: theoreticalCashBalance

    return DailyBalanceRow(
        reportDate = reportDate,
        salesTotal = salesTotal,
        expenseTotal = expenseTotal,
        balance = salesTotal - expenseTotal,
        cashDifference = actualCashBalance - theoreticalCashBalance
    )
}

private fun buildMonthlyReportRows(
    reports: List<DailyReport>,
    expenses: List<ExpenseRecord>,
    month: YearMonth
): List<MonthlyReportRow> {
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val lastDay = when {
        month.isAfter(currentMonth) -> return emptyList()
        month == currentMonth -> today.dayOfMonth
        else -> month.lengthOfMonth()
    }

    return (lastDay downTo 1).map { day ->
        val reportDate = month.atDay(day).toString()
        val dayReports = reports.filter { it.reportDate == reportDate }
        val dayExpenses = expenses.filter { it.expenseDate == reportDate }
        val dailyRow = buildDailyBalanceRow(
            reportDate = reportDate,
            reports = dayReports,
            expenses = dayExpenses
        )

        MonthlyReportRow(
            reportDate = reportDate,
            salesTotal = dailyRow.salesTotal,
            expenseTotal = dailyRow.expenseTotal,
            balance = dailyRow.balance,
            cashDifference = dailyRow.cashDifference,
            saveState = dailyReportSaveState(dayReports)
        )
    }
}
private fun dailyReportSaveState(reports: List<DailyReport>): String {
    val reportCount = reports.size
    return when {
        reportCount <= 0 -> "未入力"
        reports.any { it.status == DailyReportStatus.Draft } -> "下書き"
        reportCount == 1 -> "完了"
        else -> "複数保存 ${reportCount}件"
    }
}

private fun String.toReportStatusLabel(): String =
    when (this) {
        DailyReportStatus.Completed -> "完了"
        else -> "下書き"
    }

internal fun DailyReport.toInput(): DailyReportInput =
    DailyReportInput(
        id = id,
        reportDate = reportDate,
        status = status,
        authorName = authorName.orEmpty(),
        cashSales = cashSales.toString(),
        cardSales = cardSales.toString(),
        qrSales = qrSales.toString(),
        accountsReceivableSales = accountsReceivableSales.toString(),
        otherSales = otherSales.toString(),
        foodPurchases = foodPurchases.toString(),
        alcoholPurchases = alcoholPurchases.toString(),
        consumablesExpense = consumablesExpense.toString(),
        utilitiesExpense = utilitiesExpense.toString(),
        electricityExpense = electricityExpense.toString(),
        gasExpense = gasExpense.toString(),
        waterExpense = waterExpense.toString(),
        communicationExpense = communicationExpense.toString(),
        rentExpense = rentExpense.toString(),
        accountantFeeExpense = accountantFeeExpense.toString(),
        miscellaneousExpense = miscellaneousExpense.toString(),
        otherExpense = otherExpense.toString(),
        openingCash = openingCash.toString(),
        actualClosingCash = actualClosingCash.takeIf { hasActualClosingCash }?.toString().orEmpty(),
        customerCount = customerCount.toString(),
        groupCount = groupCount.toString(),
        memo = memo.orEmpty()
    )

private fun buildExpenseBreakdownTotals(
    reports: List<DailyReport>,
    expenses: List<ExpenseRecord>
): List<Pair<String, Long>> =
    listOf(
        "食材仕入" to expenses.filter { it.category == FoodPurchaseCategory }.sumOf { it.amount },
        "酒類仕入" to expenses.filter { it.category == AlcoholPurchaseCategory }.sumOf { it.amount },
        "消耗品費" to reports.sumOf { report ->
            expenses.preferredExpenseAmount(report.reportDate, ConsumablesCategory, report.consumablesExpense)
        } + expensesWithoutReportsTotal(reports, expenses.filter { it.category == ConsumablesCategory }),
        "水道光熱費" to reports.sumOf { it.utilityExpenseTotal() },
        "通信費" to reports.sumOf { it.communicationExpense },
        "家賃" to reports.sumOf { it.rentExpense },
        "税理士顧問料" to reports.sumOf { it.accountantFeeExpense },
        "雑費" to reports.sumOf { it.miscellaneousExpense },
        "その他支出" to expenses.filter { it.category == OtherExpenseCategory }.sumOf { it.amount },
        "車両・交通費" to expenses.filter { it.category == VehicleTransportCategory }.sumOf { it.amount }
    )

private fun DailyReport.totalSales(): Long =
    cashSales + cardSales + qrSales + accountsReceivableSales + otherSales

private fun DailyReport.detailExpense(expenses: List<ExpenseRecord>, category: String): Long =
    expenseCategoryTotal(expenses, reportDate, category)

private fun DailyReport.utilityExpenseTotal(): Long {
    val breakdownTotal = electricityExpense + gasExpense + waterExpense
    return breakdownTotal.takeIf { it > 0L } ?: utilitiesExpense
}

private fun DailyReport.isLegacyUtilityExpense(): Boolean =
    utilitiesExpense > 0L && electricityExpense == 0L && gasExpense == 0L && waterExpense == 0L

private fun DailyReport.cashExpense(expenses: List<ExpenseRecord>): Long =
    cashExpenseCategoryTotal(expenses, reportDate, FoodPurchaseCategory) +
        cashExpenseCategoryTotal(expenses, reportDate, AlcoholPurchaseCategory) +
        expenses.preferredCashExpenseAmount(reportDate, ConsumablesCategory, consumablesExpense) +
        cashExpenseCategoryTotal(expenses, reportDate, OtherExpenseCategory) +
        cashExpenseCategoryTotal(expenses, reportDate, VehicleTransportCategory) +
        utilityExpenseTotal() + communicationExpense + rentExpense + accountantFeeExpense + miscellaneousExpense

private fun DailyReport.totalExpense(expenses: List<ExpenseRecord>): Long =
    detailExpense(expenses, FoodPurchaseCategory) +
        detailExpense(expenses, AlcoholPurchaseCategory) +
        expenses.preferredExpenseAmount(reportDate, ConsumablesCategory, consumablesExpense) +
        detailExpense(expenses, OtherExpenseCategory) +
        detailExpense(expenses, VehicleTransportCategory) +
        utilityExpenseTotal() + communicationExpense + rentExpense + accountantFeeExpense + miscellaneousExpense
private fun parseDateOrNull(value: String): LocalDate? =
    runCatching { LocalDate.parse(value.trim()) }.getOrNull()

private fun YearMonth.toJapaneseMonthLabel(): String = "${year}年${monthValue}月"

private fun String.toInputLong(): Long = filter { it.isDigit() }.toLongOrNull() ?: 0L
