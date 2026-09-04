package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportSaveActionLabelTest {
    @Test
    fun savedDraftUsesReeditLabel() {
        assertEquals(
            "✓ 下書き保存済み（再編集）",
            reportSaveActionLabel(DailyReportStatus.Draft, null, true)
        )
    }

    @Test
    fun savedCompletedUsesReeditLabel() {
        assertEquals(
            "✓ 保存済み（再編集）",
            reportSaveActionLabel(DailyReportStatus.Completed, null, true)
        )
    }

    @Test
    fun savingAndUnsavedStatesKeepActionLabels() {
        assertEquals(
            "保存中…",
            reportSaveActionLabel(DailyReportStatus.Draft, DailyReportStatus.Draft, true)
        )
        assertEquals(
            "下書き保存",
            reportSaveActionLabel(DailyReportStatus.Draft, null, false)
        )
    }
}
