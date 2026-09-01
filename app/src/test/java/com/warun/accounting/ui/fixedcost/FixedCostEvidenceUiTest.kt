package com.warun.accounting.ui.fixedcost

import com.warun.accounting.data.fixedcost.ExistingAmountState
import com.warun.accounting.data.fixedcost.FixedCostDetailSnapshot
import com.warun.accounting.data.local.ReceiptRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedCostEvidenceUiTest {
    @Test
    fun duplicateUrisAreRemovedAndSortOrderIsReassigned() {
        val items = listOf(
            FixedCostEvidenceAttachment("content://a", "a.jpg", "image/jpeg", 1, 9),
            FixedCostEvidenceAttachment("content://a", "a.jpg", "image/jpeg", 1, 4),
            FixedCostEvidenceAttachment("content://b", "b.pdf", "application/pdf", 2, 8)
        )
        assertEquals(listOf("content://a", "content://b"), normalizeFixedCostEvidenceAttachments(items).map { it.uri })
        assertEquals(listOf(0, 1), normalizeFixedCostEvidenceAttachments(items).map { it.sortOrder })
    }

    @Test
    fun saveRequiresReportEvidenceAndNonConflict() {
        val receipt = ReceiptRecord("r", "2026-09-01", "2026-09-01", 1, "店", 100, 0, null, null, false, null, 1)
        val snapshot = FixedCostDetailSnapshot(receipt, null, "electricity", null, null, null, emptyList(), emptyList(), false, true)
        val attachment = FixedCostEvidenceAttachment("content://a", "a.jpg", "image/jpeg", 1, 0)
        assertFalse(fixedCostEvidenceCanSave(snapshot, listOf(attachment), false))
        assertFalse(fixedCostEvidenceCanSave(null, listOf(attachment), false))
    }

    @Test
    fun saveRejectsOversizedAttachmentAndSavingState() {
        val report = com.warun.accounting.data.local.DailyReport(
            id = "d", reportDate = "2026-09-01", status = "draft", authorName = null,
            cashSales = 0, cardSales = 0, qrSales = 0, accountsReceivableSales = 0, otherSales = 0,
            foodPurchases = 0, alcoholPurchases = 0, consumablesExpense = 0, utilitiesExpense = 0,
            electricityExpense = 0, gasExpense = 0, waterExpense = 0, communicationExpense = 0,
            rentExpense = 0, accountantFeeExpense = 0, miscellaneousExpense = 0, otherExpense = 0,
            openingCash = 0, actualClosingCash = 0, customerCount = 0, groupCount = 0, memo = null,
            createdAt = 1, updatedAt = 1, hasActualClosingCash = false
        )
        val receipt = ReceiptRecord("r", "2026-09-01", "2026-09-01", 1, "店", 100, 0, null, null, false, null, 1)
        val snapshot = FixedCostDetailSnapshot(receipt, report, "electricity", 0, ExistingAmountState.EMPTY, null, emptyList(), emptyList(), false, false)
        val oversized = FixedCostEvidenceAttachment("content://a", "a.jpg", "image/jpeg", 50L * 1024L * 1024L + 1, 0)
        assertFalse(fixedCostEvidenceCanSave(snapshot, listOf(oversized), false))
        assertFalse(fixedCostEvidenceCanSave(snapshot, listOf(oversized.copy(byteSize = 1)), true))
        assertTrue(fixedCostEvidenceCanSave(snapshot, listOf(oversized.copy(byteSize = 1)), false))
    }
}
