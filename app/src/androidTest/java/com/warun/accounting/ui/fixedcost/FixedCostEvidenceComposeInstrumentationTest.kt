package com.warun.accounting.ui.fixedcost

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.MainActivity
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FixedCostEvidenceComposeInstrumentationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var database: WarunDatabase
    private lateinit var receipt: ReceiptRecord
    private lateinit var report: DailyReport

    @Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.databaseBuilder(context, WarunDatabase::class.java, "warun-accounting-instrumented.db").build()
        val now = System.currentTimeMillis()
        val date = java.time.LocalDate.now().toString()
        report = DailyReport(
            id = "c3b-report-${UUID.randomUUID()}", reportDate = date, status = "draft", authorName = null,
            cashSales = 0, cardSales = 0, qrSales = 0, accountsReceivableSales = 0, otherSales = 0,
            foodPurchases = 0, alcoholPurchases = 0, consumablesExpense = 0, utilitiesExpense = 0,
            electricityExpense = 0, gasExpense = 0, waterExpense = 0, communicationExpense = 0,
            rentExpense = 0, accountantFeeExpense = 0, miscellaneousExpense = 0, otherExpense = 0,
            openingCash = 0, actualClosingCash = 0, customerCount = 0, groupCount = 0, memo = null,
            createdAt = now, updatedAt = now, hasActualClosingCash = false
        )
        receipt = ReceiptRecord(
            id = "c3b-receipt-${UUID.randomUUID()}", purchaseDate = date, capturedDate = date,
            registeredAt = now, storeName = "C3Bテスト支払先", totalAmount = 7000, taxAmount = 0,
            registrationNumber = null, expenseCategory = null, isConfirmed = false, memo = null, updatedAt = now
        )
        kotlinx.coroutines.runBlocking {
            database.warunDao().insertDailyReport(report)
            database.warunDao().insertReceipt(receipt)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        kotlinx.coroutines.runBlocking {
            database.warunDao().deleteReceipt(receipt)
            database.warunDao().deleteDailyReport(report)
        }
        database.close()
    }

    @Test
    fun unconfirmedReceiptRowNavigatesWithReceiptIdAndShowsFixedCostDetail() {
        composeRule.onNodeWithTag("nav-monthly_organization").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("monthly-unconfirmed-receipts").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("1件").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("monthly-unconfirmed-receipts").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("C3Bテスト支払先").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("固定費かどうかは未判定です").assertExists()
        composeRule.onNodeWithTag("receipt-row-${receipt.id}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("fixed-cost-type").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("fixed-cost-not-found").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithTag("fixed-cost-not-found").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("fixed-cost-type").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-report-date").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-existing-amount").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-evidence-add").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("fixed-cost-save").performScrollTo().assertIsDisplayed()

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("receipt-row-${receipt.id}").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("receipt-row-${receipt.id}").performScrollTo().performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("fixed-cost-screen").fetchSemanticsNodes().isNotEmpty() }
    }
}
