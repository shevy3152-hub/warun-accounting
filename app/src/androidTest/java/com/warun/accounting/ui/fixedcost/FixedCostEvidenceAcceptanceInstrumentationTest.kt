package com.warun.accounting.ui.fixedcost

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.OfflineAccountingRepository
import com.warun.accounting.MainActivity
import com.warun.accounting.data.fixedcost.FixedCostEvidenceStatus
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.FixedCostEvidenceLinkRecord
import com.warun.accounting.data.local.FixedCostReceiptApplicationRecord
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedCostEvidenceAcceptanceInstrumentationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var database: WarunDatabase
    private val ids = mutableListOf<String>()
    private lateinit var firstDate: String
    private lateinit var secondDate: String
    private lateinit var firstReport: DailyReport
    private lateinit var secondReport: DailyReport

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(
            context,
            WarunDatabase::class.java,
            "warun-accounting-instrumented.db"
        ).build()
        val now = System.currentTimeMillis()
        firstDate = LocalDate.now().minusDays(1).toString()
        secondDate = LocalDate.now().toString()
        firstReport = report("report-1", firstDate, communication = 3_000L, gas = 7_000L, water = 4_000L)
        secondReport = report("report-2", secondDate, electricity = 2_000L)
        runBlocking {
            database.warunDao().insertDailyReport(firstReport)
            database.warunDao().insertDailyReport(secondReport)
            insertApplication(
                report = firstReport,
                type = "communication",
                receiptId = "receipt-communication",
                evidence = listOf(fileEvidence("communication-jpg", "image/jpeg", 0))
            )
            insertApplication(
                report = firstReport,
                type = "gas",
                receiptId = "receipt-gas",
                evidence = listOf(
                    fileEvidence("gas-png", "image/png", 0),
                    fileEvidence("gas-pdf", "application/pdf", 1)
                )
            )
            insertApplication(
                report = firstReport,
                type = "water",
                receiptId = "receipt-water",
                evidence = listOf(pendingEvidence("water-pending"))
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (this::scenario.isInitialized) scenario.close()
        if (this::database.isInitialized) database.close()
        context.deleteDatabase("warun-accounting-instrumented.db")
        File(context.filesDir, "fixed-cost-evidence").deleteRecursively()
    }

    @Test
    fun roomRepositoryKeepsFixedCostEvidenceSeparatedAndStoredOnly() = runBlocking {
        val statuses = OfflineAccountingRepository(
            database.warunDao(),
            database.expensePrepaidLinkDao()
        ).observeFixedCostEvidenceStatuses().first()
        val first = statuses.filter { it.dailyReportId == firstReport.id }
        assertEquals(listOf("communication", "gas", "water"), first.map { it.fixedCostType })
        assertEquals(listOf("communication-jpg"), first.first { it.fixedCostType == "communication" }.evidence.map { it.id })
        assertEquals(listOf("gas-png", "gas-pdf"), first.first { it.fixedCostType == "gas" }.evidence.map { it.id })
        assertEquals(emptyList<String>(), first.first { it.fixedCostType == "water" }.evidence.map { it.id })
        assertEquals(emptyList<FixedCostEvidenceStatus>(), statuses.filter { it.dailyReportId == secondReport.id })
    }

    @Test
    fun statusComposableIsIndependentAndReturnsTypeAndEvidence() {
        val callbacks = mutableListOf<Pair<String, List<EvidenceRecord>>>()
        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    FixedCostEvidenceStatusRows(
                        report = firstReport,
                        statuses = listOf(
                            FixedCostEvidenceStatus(firstReport.id, "communication", "application-communication", listOf(fileEvidence("communication-jpg", "image/jpeg", 0))),
                            FixedCostEvidenceStatus(firstReport.id, "water", "application-water", emptyList())
                        ),
                        onOpenEvidence = { type, evidence -> callbacks += type to evidence }
                    )
                }
            }
        }
        composeRule.onNodeWithTag("fixed-cost-evidence-missing-electricity").assertDoesNotExist()
        composeRule.onNodeWithTag("fixed-cost-evidence-registered-communication").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("fixed-cost-evidence-review-water").assertIsDisplayed()
        assertEquals("communication", callbacks.single().first)
        assertEquals(listOf("communication-jpg"), callbacks.single().second.map { it.id })
    }

    @Test
    fun viewerReadsRealFilesAndHandlesMissingOrCorruptFiles() {
        val evidence = listOf(
            fileEvidence("gas-png", "image/png", 0),
            fileEvidence("gas-pdf", "application/pdf", 1)
        )
        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    FixedCostEvidenceViewer(evidence = evidence, initialIndex = 0, onDismiss = {})
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("fixed-cost-viewer-content").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("evidence_gas-png.png").assertIsDisplayed()
        composeRule.onNodeWithText("MIME: image/png").assertIsDisplayed()
        composeRule.onNodeWithText("サイズ: ", substring = true).assertExists()
        composeRule.onNodeWithText("位置: 1 / 2").assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-viewer-next").performClick()
        composeRule.onNodeWithText("evidence_gas-pdf.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("MIME: application/pdf").assertIsDisplayed()
        composeRule.onNodeWithText("位置: 2 / 2").assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-viewer-previous").performClick()
        composeRule.onNodeWithTag("fixed-cost-viewer-close").performClick()
    }

    @Test
    fun viewerShowsUnavailableForMissingOrCorruptFile() {
        val missing = evidence("missing-file", "image/jpeg", 12L)
        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    FixedCostEvidenceViewer(evidence = listOf(missing), initialIndex = 0, onDismiss = {})
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("fixed-cost-viewer-unavailable").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("表示できません").assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-viewer-close").performClick()
    }

    @Test
    fun managementViewerShowsActionsOnlyWithExplicitContextAndCancelKeepsViewer() {
        val evidence = listOf(fileEvidence("managed-one", "image/jpeg", 0), fileEvidence("managed-two", "image/jpeg", 1))
        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    FixedCostEvidenceViewer(
                        evidence = evidence,
                        initialIndex = 1,
                        onDismiss = {},
                        managementContext = FixedCostEvidenceManagementContext("managed-two", firstReport.id, "communication")
                    )
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("fixed-cost-viewer-content").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("fixed-cost-viewer-reassign").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("証憑画像の登録先だけを変更します。日報の金額は自動変更されません。").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.onNodeWithTag("fixed-cost-viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-viewer-unlink").performClick()
        composeRule.onNodeWithText("日報との関連付けだけを解除します。保存済みの証憑画像と日報の金額は削除されません。").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
    }

    @Test
    fun newReportFixedCostEvidencePromptsSaveAndUsesSavedReportId() {
        val targetDate = LocalDate.now().plusDays(1).toString()
        composeRule.onNodeWithTag("nav-report_entry").performClick()
        composeRule.onNodeWithContentDescription("カレンダーを開く").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("calendar-day-$targetDate").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("calendar-day-$targetDate").performClick()
        composeRule.onNodeWithTag("fixed-cost-field-electricity")
            .performScrollTo()
            .performTextInput("3000")
        composeRule.onNodeWithTag("fixed-cost-direct-electricity")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("日報を保存して証憑を追加").assertIsDisplayed()
        composeRule.onNodeWithText("証憑を登録するには、現在の入力内容を日報として保存します。").assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-save-cancel").performClick()
        composeRule.onNodeWithText("日報を保存して証憑を追加").assertDoesNotExist()

        composeRule.onNodeWithTag("fixed-cost-direct-electricity")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("fixed-cost-save-and-continue").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("fixed-cost-direct-screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("fixed-cost-direct-screen").assertIsDisplayed()
        composeRule.onNodeWithText("日報金額：3000円").assertIsDisplayed()
    }

    @Test
    fun ordinaryViewerDoesNotExposeManagementActions() {
        val evidence = listOf(fileEvidence("display-only", "image/jpeg", 0))
        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    FixedCostEvidenceViewer(evidence = evidence, initialIndex = 0, onDismiss = {})
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("fixed-cost-viewer-content").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("fixed-cost-viewer-reassign").assertDoesNotExist()
        composeRule.onNodeWithTag("fixed-cost-viewer-unlink").assertDoesNotExist()
    }

    @Test
    fun unclassifiedPickerUsesEvidenceIdWhenSelectingAmongMultiple() {
        val evidence = listOf(fileEvidence("unclassified-one", "image/jpeg", 0), fileEvidence("unclassified-two", "image/jpeg", 1))
        var selected: String? = null
        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    UnclassifiedEvidencePickerDialog(
                        evidence = evidence,
                        targetDate = firstDate,
                        targetType = "communication",
                        onConfirm = { selected = it },
                        onDismiss = {}
                    )
                }
            }
        }
        composeRule.onNodeWithTag("unclassified-evidence-unclassified-two").performClick()
        composeRule.onNodeWithTag("unclassified-evidence-confirm").performClick()
        assertEquals("unclassified-two", selected)
    }

    private fun report(
        id: String,
        date: String,
        electricity: Long = 0L,
        gas: Long = 0L,
        water: Long = 0L,
        communication: Long = 0L
    ) = DailyReport(
        id = id, reportDate = date, status = "completed", authorName = null,
        cashSales = 0, cardSales = 0, qrSales = 0, accountsReceivableSales = 0, otherSales = 0,
        foodPurchases = 0, alcoholPurchases = 0, consumablesExpense = 0, utilitiesExpense = 0,
        electricityExpense = electricity, gasExpense = gas, waterExpense = water,
        communicationExpense = communication, rentExpense = 0, accountantFeeExpense = 0,
        miscellaneousExpense = 0, otherExpense = 0, openingCash = 0, actualClosingCash = 0,
        customerCount = 0, groupCount = 0, memo = null, createdAt = 1, updatedAt = 1,
        hasActualClosingCash = false
    )

    private suspend fun insertApplication(
        report: DailyReport,
        type: String,
        receiptId: String,
        evidence: List<EvidenceRecord>
    ) {
        ids += receiptId
        val now = System.currentTimeMillis()
        database.warunDao().insertReceipt(
            ReceiptRecord(receiptId, report.reportDate, report.reportDate, now, type, 1_000, 0, null, null, true, null, now)
        )
        val applicationId = "application-$type"
        database.warunDao().insertFixedCostReceiptApplication(
            FixedCostReceiptApplicationRecord(applicationId, receiptId, report.id, type, "銀行振込", now, now)
        )
        evidence.forEach { item ->
            database.warunDao().insertEvidenceRecord(item)
            database.warunDao().insertFixedCostEvidenceLink(
                FixedCostEvidenceLinkRecord(applicationId, item.id, evidence.indexOf(item), now)
            )
            database.warunDao().insertFixedCostEvidenceAssignment(
                com.warun.accounting.data.local.FixedCostEvidenceAssignmentRecord(
                    evidenceId = item.id,
                    dailyReportId = report.id,
                    fixedCostType = type,
                    sortOrder = evidence.indexOf(item),
                    assignedAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private fun fileEvidence(id: String, mediaType: String, sortOrder: Int): EvidenceRecord {
        val directory = File(context.filesDir, "fixed-cost-evidence/stored").also { it.mkdirs() }
        val extension = when (mediaType) {
            "image/png" -> "png"
            "application/pdf" -> "pdf"
            else -> "jpg"
        }
        val file = File(directory, "evidence_$id.$extension")
        if (mediaType == "application/pdf") {
            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(240, 320, 1).create())
            page.canvas.drawText("固定費テストPDF", 24f, 48f, android.graphics.Paint())
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()
        } else if (mediaType == "image/png") {
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                FileOutputStream(file).use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
        } else {
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                FileOutputStream(file).use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
                recycle()
            }
        }
        return evidence(id, mediaType, file.length())
    }

    private fun pendingEvidence(id: String): EvidenceRecord = evidence(id, "image/jpeg", 10L).copy(state = EvidenceRecordState.Pending, storedAt = null)

    private fun evidence(id: String, mediaType: String, size: Long) = EvidenceRecord(
        id = id, captureId = "capture-$id", storedUri = "file:///fixed-cost/$id",
        byteSize = size, sha256 = "a".repeat(64), state = EvidenceRecordState.Stored,
        createdAt = 1, storedAt = 2, updatedAt = 2, mediaType = mediaType
    )
}
