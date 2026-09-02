package com.warun.accounting.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.MainActivity
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.ui.theme.WarunTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptDeletionComposeInstrumentationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var database: WarunDatabase
    private lateinit var receipt: ReceiptRecord
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WarunDatabase::class.java
        ).allowMainThreadQueries().build()
        receipt = ReceiptRecord(
            id = "ui-delete-receipt",
            purchaseDate = "2026-09-01",
            capturedDate = null,
            registeredAt = 1L,
            storeName = "UIテスト支払先",
            totalAmount = 12_345L,
            taxAmount = 0L,
            registrationNumber = null,
            expenseCategory = null,
            isConfirmed = false,
            memo = null,
            updatedAt = 1L
        )
        runBlocking { database.warunDao().insertReceipt(receipt) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        database.close()
    }

    @Test
    fun deleteConfirmationCancelLeavesReceiptAndDoesNotInvokeDelete() {
        val before = count("receipts")
        var deleteCalls = 0
        scenario.onActivity { activity ->
            activity.setContent {
            var selected by remember { mutableStateOf<ReceiptRecord?>(null) }
            WarunTheme {
                ReceiptList(
                    receipts = listOf(receipt),
                    unconfirmedOnly = true,
                    onRequestDelete = { selected = it }
                )
                selected?.let {
                    ReceiptDeletionDialog(
                        receipt = it,
                        isDeleting = false,
                        onDismiss = { selected = null },
                        onConfirm = { deleteCalls++ }
                    )
                }
            }
            }
        }

        composeRule.onNodeWithTag("receipt-delete-${receipt.id}").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("レシートを削除しますか？").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, composeRule.onAllNodesWithText("支払先: UIテスト支払先").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("日付: 2026-09-01").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("金額: ￥12,345").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("この操作は取り消せません。").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("キャンセル").performClick()

        assertEquals(0, composeRule.onAllNodesWithText("レシートを削除しますか？").fetchSemanticsNodes().size)
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("receipt-row-${receipt.id}").fetchSemanticsNodes().size
        )
        assertEquals(0, deleteCalls)
        assertEquals(before, count("receipts"))
        assertEquals(0L, count("fixed_cost_receipt_applications"))
        assertEquals(0L, count("fixed_cost_evidence_links"))
        assertEquals(0L, count("expense_records"))
        assertEquals(0L, count("evidence_records"))
    }

    private fun count(table: String): Long = database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getLong(0)
    }
}
