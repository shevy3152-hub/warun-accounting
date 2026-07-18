@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.warun.accounting.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CalendarMonth
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.ui.util.toYen
import com.warun.accounting.util.isSupportedPaymentMethod
import com.warun.accounting.util.normalizePaymentMethod
import com.warun.accounting.util.paymentMethodOptions
import com.warun.accounting.ui.viewmodel.AppSettingsInput
import com.warun.accounting.ui.viewmodel.DailyReportInput
import com.warun.accounting.ui.viewmodel.DashboardViewModel
import com.warun.accounting.ui.viewmodel.ExpenseInput
import com.warun.accounting.ui.viewmodel.ReceiptInput
import java.time.LocalDate
import java.time.YearMonth

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

private object ReportRoutes {
    const val ReportDateArg = "reportDate"
    const val Detail = "report_detail/{reportDate}"
    const val Entry = "report_entry/{reportDate}"

    fun detail(reportDate: String): String = "report_detail/$reportDate"
    fun entry(reportDate: String): String = "report_entry/$reportDate"
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
    expenses.filter { it.expenseDate == reportDate && it.category == category }.sumOf { it.amount }

private fun detailExpense(input: DailyReportInput, expenses: List<ExpenseRecord>, category: String): Long =
    expenseCategoryTotal(expenses, input.reportDate, category)

private fun DailyReportInput.utilityExpenseTotal(): Long {
    val breakdownTotal = electricityExpense.toInputLong() + gasExpense.toInputLong() + waterExpense.toInputLong()
    return breakdownTotal.takeIf { it > 0L } ?: utilitiesExpense.toInputLong()
}

private fun expensesWithoutReportsTotal(reports: List<DailyReport>, expenses: List<ExpenseRecord>): Long {
    val reportDates = reports.map { it.reportDate }.toSet()
    return expenses.filterNot { it.expenseDate in reportDates }.sumOf { it.amount }
}
@Composable
fun WarunApp(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Home.route

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
                        onSelect = navController::navigateSingleTop
                    )
                }
            ) { padding ->
                AppNavHost(
                    uiState = uiState,
                    viewModel = viewModel,
                    navController = navController,
                    contentPadding = padding
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                SideNavigation(
                    uiState = uiState,
                    currentRoute = currentRoute,
                    onSelect = navController::navigateSingleTop
                )
                AppNavHost(
                    uiState = uiState,
                    viewModel = viewModel,
                    navController = navController,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
@Composable
private fun AppNavHost(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        composable(AppDestination.Home.route) {
            HomeScreen(uiState = uiState, onNavigate = navController::navigateSingleTop)
        }
        composable(AppDestination.ReportEntry.route) {
            ReportEntryScreen(
                uiState = uiState,
                onSaveReport = viewModel::saveDailyReport,
                onSaveExpense = viewModel::saveExpense,
                onDeleteExpense = viewModel::deleteExpense,
                onAddSupplierCandidate = viewModel::addSupplierCandidate,
                onHideSupplierCandidate = viewModel::hideSupplierCandidate
            )
        }
        composable(AppDestination.Receipt.route) {
            ReceiptScreen(
                uiState = uiState,
                onNavigate = navController::navigateSingleTop,
                onSaveReceipt = viewModel::saveReceipt
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
                    navController.navigateSingleTop(AppDestination.Submit.route)
                }
            )
        }
        composable(AppDestination.ReportList.route) {
            ReportListScreen(
                uiState = uiState,
                onOpenDate = { reportDate ->
                    navController.navigate(ReportRoutes.detail(reportDate))
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
                onBack = { navController.popBackStack() },
                onEntry = { navController.navigate(ReportRoutes.entry(reportDate)) }
            )
        }
        composable(
            route = ReportRoutes.Entry,
            arguments = listOf(navArgument(ReportRoutes.ReportDateArg) { type = NavType.StringType })
        ) { backStackEntry ->
            ReportEntryScreen(
                uiState = uiState,
                initialDate = backStackEntry.arguments?.getString(ReportRoutes.ReportDateArg),
                onSaveReport = viewModel::saveDailyReport,
                onSaveExpense = viewModel::saveExpense,
                onDeleteExpense = viewModel::deleteExpense,
                onAddSupplierCandidate = viewModel::addSupplierCandidate,
                onHideSupplierCandidate = viewModel::hideSupplierCandidate
            )
        }
        composable(AppDestination.Submit.route) {
            SubmitScreen(
                uiState = uiState,
                onMarkSubmitted = viewModel::markMonthSubmitted,
                onOpenMonthlyOrganization = {
                    navController.navigateSingleTop(AppDestination.MonthlyOrganization.route)
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
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
@Composable
private fun SideNavigation(
    uiState: DashboardUiState,
    currentRoute: String,
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
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            SidebarSummary(uiState)
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
private fun SidebarSummary(uiState: DashboardUiState) {
    DashboardCard(containerColor = Color(0xFF182538)) {
        Text("今日のサマリー", color = Color.White, fontWeight = FontWeight.Bold)
        SummaryLine("売上合計", uiState.salesTotal.toYen(), Color.White)
        SummaryLine("概算利益", (uiState.salesTotal - uiState.expenseTotal).toYen(), Color(0xFF6EE78A))
        SummaryLine("現金残高", uiState.closingCash.toYen(), Color.White)
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
    onNavigate: (String) -> Unit
) {
    ScreenColumn {
        ScreenTitle("ホーム", "今日と今月の状況をすぐ確認できます。")
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
    modifier: Modifier = Modifier
) {
    Button(onClick = onClick, modifier = modifier.height(52.dp)) {
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
    onSaveComplete: () -> Unit
) {
    DashboardCard(containerColor = Color(0xFFEFF6FF)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 520.dp
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onSaveDraft, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(draftLabel)
                    }
                    PrimaryActionButton(
                        label = completeLabel,
                        onClick = onSaveComplete,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onSaveDraft, modifier = Modifier.width(180.dp).height(52.dp)) {
                        Text(draftLabel)
                    }
                    Spacer(Modifier.width(10.dp))
                    PrimaryActionButton(
                        label = completeLabel,
                        onClick = onSaveComplete,
                        modifier = Modifier.width(220.dp)
                    )
                }
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
    onSaveReport: (DailyReportInput) -> Unit,
    onSaveExpense: (ExpenseInput) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit
) {
    val initialReportDate = remember(initialDate) { initialDate ?: DailyReportInput().reportDate }

    fun inputForDate(reportDate: String): DailyReportInput =
        uiState.reports.firstOrNull { it.reportDate == reportDate }?.toInput()
            ?: DailyReportInput(reportDate = reportDate)

    var reportInput by remember(initialReportDate) {
        mutableStateOf(inputForDate(initialReportDate))
    }
    var cleanReportInput by remember(initialReportDate) {
        mutableStateOf(inputForDate(initialReportDate))
    }
    var pendingReportDate by remember { mutableStateOf<String?>(null) }
    var expenseFormDirty by remember { mutableStateOf(false) }

    val paymentVisibility = uiState.appSettings.toPaymentVisibility()
    val reportExpenses = uiState.expenses.filter { it.expenseDate == reportInput.reportDate }
    val enteredReportDates = remember(uiState.reports) { uiState.reports.map { it.reportDate }.toSet() }
    val totals = reportInput.calculateTotals(paymentVisibility, reportExpenses)

    fun openReportDate(reportDate: String) {
        val nextInput = inputForDate(reportDate)
        reportInput = nextInput
        cleanReportInput = nextInput
        expenseFormDirty = false
    }

    fun requestOpenReportDate(reportDate: String) {
        if (reportDate == reportInput.reportDate) return
        if (reportInput != cleanReportInput || expenseFormDirty) {
            pendingReportDate = reportDate
        } else {
            openReportDate(reportDate)
        }
    }

    pendingReportDate?.let { targetDate ->
        AlertDialog(
            onDismissRequest = { pendingReportDate = null },
            title = { Text("未保存の内容があります") },
            text = { Text("保存せずに別の日報を開きますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReportDate = null
                        openReportDate(targetDate)
                    }
                ) {
                    Text("続行")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingReportDate = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    ScreenColumn {
        ScreenTitle("日報入力", "空いた時間に任意の日付で入力できます。途中でも下書き保存できます。")
        DailyReportForm(
            input = reportInput,
            paymentVisibility = paymentVisibility,
            totals = totals,
            expenses = reportExpenses,
            supplierCandidates = uiState.supplierCandidates,
            enteredReportDates = enteredReportDates,
            onInputChange = { reportInput = it },
            onCalendarDateSelected = { requestOpenReportDate(it) },
            onSaveExpense = onSaveExpense,
            onDeleteExpense = onDeleteExpense,
            onAddSupplierCandidate = onAddSupplierCandidate,
            onHideSupplierCandidate = onHideSupplierCandidate,
            onExpenseFormDirtyChanged = { expenseFormDirty = it },
            onSave = { status ->
                val savedInput = reportInput
                    .copy(status = status)
                    .withHiddenPaymentsCleared(paymentVisibility)
                onSaveReport(savedInput)
                reportInput = savedInput
                cleanReportInput = savedInput
            }
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
    supplierCandidates: List<SupplierCandidateRecord>,
    enteredReportDates: Set<String>,
    onInputChange: (DailyReportInput) -> Unit,
    onCalendarDateSelected: (String) -> Unit,
    onSaveExpense: (ExpenseInput) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onExpenseFormDirtyChanged: (Boolean) -> Unit,
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
                supplierCandidates = supplierCandidates,
                expenseTotal = totals.expenseTotal,
                todayBalance = totals.todayBalance,
                onInputChange = onInputChange,
                onSaveExpense = onSaveExpense,
                onDeleteExpense = onDeleteExpense,
                onAddSupplierCandidate = onAddSupplierCandidate,
                onHideSupplierCandidate = onHideSupplierCandidate,
                onExpenseFormDirtyChanged = onExpenseFormDirtyChanged,
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
            draftLabel = "下書き保存",
            completeLabel = "入力完了で保存",
            onSaveDraft = { onSave(DailyReportStatus.Draft) },
            onSaveComplete = { onSave(DailyReportStatus.Completed) }
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

    OutlinedTextField(
        value = value,
        onValueChange = onDateChange,
        label = { Text("日付") },
        trailingIcon = {
            IconButton(onClick = { showCalendar = true }) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = "カレンダーを開く")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        modifier = modifier,
        singleLine = true
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
                AppTextField("現金売上", input.cashSales, KeyboardType.Number, fieldModifier) {
                    onInputChange(input.copy(cashSales = it))
                }
            }
            if (paymentVisibility.useCardPayment) {
                AppTextField("クレジットカード売上", input.cardSales, KeyboardType.Number, fieldModifier) {
                    onInputChange(input.copy(cardSales = it))
                }
            }
            if (paymentVisibility.useQrPayment) {
                AppTextField("QR決済売上", input.qrSales, KeyboardType.Number, fieldModifier) {
                    onInputChange(input.copy(qrSales = it))
                }
            }
            if (paymentVisibility.useAccountsReceivablePayment) {
                AppTextField("売掛売上", input.accountsReceivableSales, KeyboardType.Number, fieldModifier) {
                    onInputChange(input.copy(accountsReceivableSales = it))
                }
            }
            if (paymentVisibility.useOtherPayment) {
                AppTextField("その他売上", input.otherSales, KeyboardType.Number, fieldModifier) {
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
    expenseTotal: Long,
    todayBalance: Long,
    onInputChange: (DailyReportInput) -> Unit,
    onSaveExpense: (ExpenseInput) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    supplierCandidates: List<SupplierCandidateRecord>,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onExpenseFormDirtyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var selectedCategory by remember(input.reportDate) { mutableStateOf<String?>(null) }
    val foodTotal = detailExpense(input, expenses, FoodPurchaseCategory)
    val alcoholTotal = detailExpense(input, expenses, AlcoholPurchaseCategory)
    val consumablesTotal = detailExpense(input, expenses, ConsumablesCategory)
    val otherExpenseTotal = detailExpense(input, expenses, OtherExpenseCategory)
    val vehicleTransportTotal = detailExpense(input, expenses, VehicleTransportCategory)

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
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        supplierCandidates = supplierCandidates,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onDirtyChanged = onExpenseFormDirtyChanged
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
                DetailedExpenseCategoryRow(ConsumablesCategory, consumablesTotal + input.consumablesExpense.toInputLong()) {
                    selectedCategory = ConsumablesCategory
                }
                if (selectedCategory == ConsumablesCategory) {
                    ExpenseDetailPanel(
                        reportDate = input.reportDate,
                        category = ConsumablesCategory,
                        expenses = expenses.filter { it.category == ConsumablesCategory },
                        supplierCandidates = supplierCandidates,
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onDirtyChanged = onExpenseFormDirtyChanged
                    )
                }
                AdaptiveFormFields { fieldModifier ->
                    AppTextField("電気代（中部電力）", input.electricityExpense, KeyboardType.Number, fieldModifier) {
                        onInputChange(input.copy(electricityExpense = it))
                    }
                    AppTextField("ガス代（丸栄ガス）", input.gasExpense, KeyboardType.Number, fieldModifier) {
                        onInputChange(input.copy(gasExpense = it))
                    }
                    AppTextField("水道代（水道）", input.waterExpense, KeyboardType.Number, fieldModifier) {
                        onInputChange(input.copy(waterExpense = it))
                    }
                }
                TotalRow("水道光熱費", input.utilityExpenseTotal().toYen())
                AppTextField("通信費（NTT）", input.communicationExpense, KeyboardType.Number) {
                    onInputChange(input.copy(communicationExpense = it))
                }
                AppTextField("家賃（ヒロセフサコ）", input.rentExpense, KeyboardType.Number) {
                    onInputChange(input.copy(rentExpense = it))
                }
                AppTextField("税理士顧問料", input.accountantFeeExpense, KeyboardType.Number) {
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
                        supplierCandidates = supplierCandidates,
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onDirtyChanged = onExpenseFormDirtyChanged
                    )
                }
                AppTextField("雑費", input.miscellaneousExpense, KeyboardType.Number) {
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
                        onClose = { selectedCategory = null },
                        onSaveExpense = onSaveExpense,
                        onDeleteExpense = onDeleteExpense,
                        supplierCandidates = supplierCandidates,
                        onAddSupplierCandidate = onAddSupplierCandidate,
                        onHideSupplierCandidate = onHideSupplierCandidate,
                        onDirtyChanged = onExpenseFormDirtyChanged
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
    onClose: () -> Unit,
    onSaveExpense: (ExpenseInput) -> Unit,
    onDeleteExpense: (ExpenseRecord) -> Unit,
    supplierCandidates: List<SupplierCandidateRecord>,
    onAddSupplierCandidate: (String, String, String) -> Unit,
    onHideSupplierCandidate: (SupplierCandidateRecord) -> Unit,
    onDirtyChanged: (Boolean) -> Unit
) {
    var editingExpense by remember(reportDate, category) { mutableStateOf<ExpenseRecord?>(null) }
    var showForm by remember(reportDate, category) { mutableStateOf(true) }
    var formResetKey by remember(reportDate, category) { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${expenseCategoryLabel(category)} 明細", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                onDirtyChanged(false)
                onClose()
            }) { Text("閉じる") }
        }
        if (expenses.isEmpty()) {
            Text("明細はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            expenses.sortedByDescending { it.createdAt }.forEach { expense ->
                ExpenseRecordRow(
                    expense = expense,
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
                onDirtyChanged = onDirtyChanged,
                onClose = {
                    onDirtyChanged(false)
                    onClose()
                },
                onCancel = {
                    onDirtyChanged(false)
                    editingExpense = null
                    showForm = false
                },
                onSave = { expenseInput ->
                    val wasEditing = editingExpense != null
                    onDirtyChanged(false)
                    onSaveExpense(expenseInput)
                    editingExpense = null
                    if (wasEditing) {
                        showForm = false
                    } else {
                        formResetKey++
                        showForm = true
                    }
                }
            )
        }
    }
}
@Composable
private fun ExpenseRecordRow(
    expense: ExpenseRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(10.dp)) {
            Text(expense.supplierName.orEmpty().ifBlank { "支払先未入力" }, fontWeight = FontWeight.Bold)
            Text("${expense.amount.toYen()} / ${expense.paymentMethod.orEmpty().ifBlank { "支払方法未入力" }}")
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
    onDirtyChanged: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: (ExpenseInput) -> Unit
) {
    var supplier by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(editingExpense?.supplierName.orEmpty()) }
    var category by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(editingExpense?.category ?: initialCategory) }
    var paymentMethod by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(normalizePaymentMethod(editingExpense?.paymentMethod)) }
    var amount by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(editingExpense?.amount?.takeIf { it > 0L }?.toString().orEmpty()) }
    var memo by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(editingExpense?.memo.orEmpty()) }
    var isCustomSupplier by remember(editingExpense, initialCategory, resetKey) { mutableStateOf(false) }
    val supplierFocusRequester = remember { FocusRequester() }
    val amountFocusRequester = remember { FocusRequester() }
    var candidateToHide by remember { mutableStateOf<SupplierCandidateRecord?>(null) }
    val candidates = remember(category, supplierCandidates) { supplierCandidatesFor(category, supplierCandidates) }
    val canAddCandidate = isCustomSupplier && supplier.trim().isNotBlank() && candidates.none { it.name == supplier.trim() }
    val initialSupplier = editingExpense?.supplierName.orEmpty()
    val initialCategoryValue = editingExpense?.category ?: initialCategory
    val initialPaymentMethod = normalizePaymentMethod(editingExpense?.paymentMethod)
    val initialAmount = editingExpense?.amount?.takeIf { it > 0L }?.toString().orEmpty()
    val initialMemo = editingExpense?.memo.orEmpty()
    val formDirty = supplier != initialSupplier ||
        category != initialCategoryValue ||
        paymentMethod != initialPaymentMethod ||
        amount != initialAmount ||
        memo != initialMemo
    val parsedAmount = amount.toLongOrNull()
    val isAmountValid = parsedAmount != null && parsedAmount > 0L

    LaunchedEffect(formDirty) {
        onDirtyChanged(formDirty)
    }
    DisposableEffect(reportDate, initialCategory, editingExpense?.id, resetKey) {
        onDispose { onDirtyChanged(false) }
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
            AppTextField("金額", amount, KeyboardType.Number, Modifier.focusRequester(amountFocusRequester)) { value ->
                amount = value.filter { it.isDigit() }
            }
            if (amount.isBlank()) {
                Text("1円以上の金額を入力してください", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            } else if (!isAmountValid) {
                Text("1円以上の金額を入力してください", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            AppTextField("メモ", memo) { memo = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = isAmountValid && isSupportedPaymentMethod(paymentMethod),
                    onClick = {
                        onSave(
                            ExpenseInput(
                                id = editingExpense?.id.orEmpty(),
                                expenseDate = reportDate,
                                category = category,
                                supplierName = supplier,
                                amount = amount,
                                paymentMethod = normalizePaymentMethod(paymentMethod),
                                memo = memo,
                                receiptId = editingExpense?.receiptId.orEmpty(),
                                sourceType = editingExpense?.sourceType ?: ExpenseSourceType.Manual,
                                createdAt = editingExpense?.createdAt
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
            AppTextField("営業開始時現金", input.openingCash, KeyboardType.Number, fieldModifier) {
                onInputChange(input.copy(openingCash = it))
            }
            AppTextField("実際の終了時現金", input.actualClosingCash, KeyboardType.Number, fieldModifier) {
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
            AppTextField("来客数", input.customerCount, KeyboardType.Number, fieldModifier) {
                onInputChange(input.copy(customerCount = it))
            }
            AppTextField("組数", input.groupCount, KeyboardType.Number, fieldModifier) {
                onInputChange(input.copy(groupCount = it))
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
    onSaveReceipt: (ReceiptInput) -> Unit
) {
    var input by remember {
        mutableStateOf(ReceiptInput(capturedDate = LocalDate.now().toString()))
    }

    ScreenColumn {
        ScreenTitle("レシート", "撮影とOCRは次フェーズです。購入日ベースで仮登録できます。")
        FormCard {
            Text("仮レシート登録", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "購入日が不明な場合は空欄のまま保存すると、日付未確認として月別整理に表示します。登録時刻は保存時に自動記録します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AdaptiveFormFields { fieldModifier ->
                AppTextField("購入日 yyyy-MM-dd", input.purchaseDate, modifier = fieldModifier) {
                    input = input.copy(purchaseDate = it)
                }
                AppTextField("撮影日 yyyy-MM-dd", input.capturedDate, modifier = fieldModifier) {
                    input = input.copy(capturedDate = it)
                }
                AppTextField("店名", input.storeName, modifier = fieldModifier) {
                    input = input.copy(storeName = it)
                }
                AppTextField("合計金額", input.totalAmount, KeyboardType.Number, fieldModifier) {
                    input = input.copy(totalAmount = it)
                }
                AppTextField("消費税", input.taxAmount, KeyboardType.Number, fieldModifier) {
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
            draftLabel = "日付未確認で保存",
            completeLabel = "確認済みで保存",
            onSaveDraft = {
                onSaveReceipt(input.copy(isConfirmed = false))
                input = ReceiptInput(capturedDate = LocalDate.now().toString())
            },
            onSaveComplete = {
                onSaveReceipt(input.copy(isConfirmed = true))
                input = ReceiptInput(capturedDate = LocalDate.now().toString())
            }
        )
        DashboardCard {
            Text("CameraX撮影とML Kit OCRは次フェーズで追加します。OCR結果は自動確定せず候補表示にします。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ResponsivePrimaryAction("日報入力へ移動", onClick = { onNavigate(AppDestination.ReportEntry.route) })
        }
        ReceiptList(uiState.receipts.take(8))
    }
}
@Composable
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
                AppTextField("開始日 yyyy-MM-dd", customStartDate) {
                    onCustomStartChange(it)
                }
                AppTextField("終了日 yyyy-MM-dd", customEndDate) {
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
        TotalRow("消耗品費", (report.consumablesExpense + report.detailExpense(expenses, ConsumablesCategory)).toYen())
        TotalRow("水道光熱費", report.utilitiesExpense.toYen())
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
        TotalRow("実際の終了時現金", report.actualClosingCash.toYen())
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
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = modifier,
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

private data class BalancePeriod(
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

private data class BalanceSummary(
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
    val dailyRows: List<DailyBalanceRow>
)

private data class DailyBalanceRow(
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

private fun DailyReportInput.calculateTotals(paymentVisibility: PaymentVisibility, expenses: List<ExpenseRecord>): DailyReportTotals {
    val cashSales = if (paymentVisibility.useCashPayment) this.cashSales.toInputLong() else 0L
    val totalSales = listOf(
        cashSales,
        if (paymentVisibility.useCardPayment) this.cardSales.toInputLong() else 0L,
        if (paymentVisibility.useQrPayment) this.qrSales.toInputLong() else 0L,
        if (paymentVisibility.useAccountsReceivablePayment) this.accountsReceivableSales.toInputLong() else 0L,
        if (paymentVisibility.useOtherPayment) this.otherSales.toInputLong() else 0L
    ).sum()
    val expenseTotal = detailExpense(this, expenses, FoodPurchaseCategory) +
        detailExpense(this, expenses, AlcoholPurchaseCategory) +
        detailExpense(this, expenses, ConsumablesCategory) +
        detailExpense(this, expenses, OtherExpenseCategory) +
        detailExpense(this, expenses, VehicleTransportCategory) +
        this.consumablesExpense.toInputLong() +
        this.utilityExpenseTotal() +
        this.communicationExpense.toInputLong() +
        this.rentExpense.toInputLong() +
        this.accountantFeeExpense.toInputLong() +
        this.miscellaneousExpense.toInputLong()
    val cashExpense = expenseTotal
    val theoreticalClosingCash = this.openingCash.toInputLong() + cashSales - cashExpense
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

private fun buildBalanceSummary(
    reports: List<DailyReport>,
    expenses: List<ExpenseRecord>,
    period: BalancePeriod
): BalanceSummary {
    val periodReports = reports.filter { period.contains(it.reportDate) }
    val periodExpenses = expenses.filter { expense -> period.contains(expense.expenseDate) }
    val salesTotal = periodReports.sumOf { it.totalSales() }
    val expenseTotal = periodReports.sumOf { it.totalExpense(periodExpenses) } + expensesWithoutReportsTotal(periodReports, periodExpenses)
    val cashSales = periodReports.sumOf { it.cashSales }
    val cashExpense = expenseTotal
    val firstReport = periodReports.minWithOrNull(
        compareBy<DailyReport> { it.reportDate }.thenBy { it.createdAt }
    )
    val latestReport = periodReports.maxWithOrNull(
        compareBy<DailyReport> { it.reportDate }.thenBy { it.updatedAt }
    )
    val theoreticalCashBalance = (firstReport?.openingCash ?: 0L) + cashSales - cashExpense
    val actualCashBalance = latestReport?.actualClosingCash?.takeIf { it > 0 } ?: theoreticalCashBalance
    val categoryTotals = buildExpenseBreakdownTotals(periodReports, periodExpenses)
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
        dailyRows = dailyRows
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
    val theoreticalCashBalance = (firstReport?.openingCash ?: 0L) + cashSales - expenseTotal
    val actualCashBalance = latestReport?.actualClosingCash?.takeIf { it > 0 } ?: theoreticalCashBalance

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

private fun DailyReport.toInput(): DailyReportInput =
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
        actualClosingCash = actualClosingCash.toString(),
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
        "消耗品費" to (reports.sumOf { it.consumablesExpense } + expenses.filter { it.category == ConsumablesCategory }.sumOf { it.amount }),
        "水道光熱費" to reports.sumOf { it.utilitiesExpense },
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

private fun DailyReport.totalExpense(expenses: List<ExpenseRecord>): Long =
    detailExpense(expenses, FoodPurchaseCategory) +
        detailExpense(expenses, AlcoholPurchaseCategory) +
        detailExpense(expenses, ConsumablesCategory) +
        detailExpense(expenses, OtherExpenseCategory) +
        detailExpense(expenses, VehicleTransportCategory) +
        consumablesExpense + utilitiesExpense + communicationExpense + rentExpense + accountantFeeExpense + miscellaneousExpense
private fun parseDateOrNull(value: String): LocalDate? =
    runCatching { LocalDate.parse(value.trim()) }.getOrNull()

private fun YearMonth.toJapaneseMonthLabel(): String = "${year}年${monthValue}月"

private fun String.toInputLong(): Long = filter { it.isDigit() }.toLongOrNull() ?: 0L
