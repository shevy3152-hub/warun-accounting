package com.warun.accounting.ui.model

import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.ui.util.currentMonthString
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperSubmissionSafetyTest {
    @Test
    fun statusLabelsDescribeAPaperSubmissionRecord() {
        assertEquals("紙提出未記録", PaperSubmissionCopy.statusLabel(recorded = false))
        assertEquals("紙提出済み（記録）", PaperSubmissionCopy.statusLabel(recorded = true))
    }

    @Test
    fun existingSubmittedFlagIsPresentedAsAPaperRecord() {
        val targetMonth = currentMonthString()
        val state = DashboardUiState(
            monthlySubmissions = listOf(
                MonthlySubmission(
                    targetMonth = targetMonth,
                    status = MonthlySubmissionStatus.Submitted,
                    submittedAt = 10L,
                    updatedAt = 10L
                )
            )
        )

        assertEquals("紙提出済み（記録）", state.currentMonthSubmissionLabel)
    }

    @Test
    fun existingMonthlySubmissionRemainsIdentifiedAsPastPaperRecord() {
        val record = MonthlySubmission(
            targetMonth = "2026-06",
            status = MonthlySubmissionStatus.Submitted,
            submittedAt = 10L,
            updatedAt = 10L
        )

        assertEquals("2026-06", record.targetMonth)
        assertEquals("紙提出済み（記録）", PaperSubmissionCopy.statusLabel(recorded = true))
    }
}
