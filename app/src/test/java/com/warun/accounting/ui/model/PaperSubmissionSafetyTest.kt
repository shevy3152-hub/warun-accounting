package com.warun.accounting.ui.model

import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.ui.util.currentMonthString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun noticesExplicitlyRejectElectronicGenerationAndSending() {
        assertTrue(PaperSubmissionCopy.ElectronicSendNotice.contains("電子ファイルの生成"))
        assertTrue(PaperSubmissionCopy.ElectronicSendNotice.contains("メール送信"))
        assertTrue(PaperSubmissionCopy.ElectronicSendNotice.contains("行いません"))
        assertFalse(PaperSubmissionCopy.ElectronicSendNotice.contains("送信しました"))

        val confirmation = PaperSubmissionCopy.confirmationMessage("2026-08")
        assertTrue(confirmation.contains("2026-08"))
        assertTrue(confirmation.contains("この端末内だけ"))
        assertTrue(confirmation.contains("行われません"))
    }
}
