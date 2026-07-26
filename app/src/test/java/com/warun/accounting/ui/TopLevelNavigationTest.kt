package com.warun.accounting.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavigationTest {
    @Test
    fun reportListAndReportEntryUseDifferentExpectedRoutes() {
        val listRoute = topLevelRouteForLabel("日報一覧")
        val entryRoute = topLevelRouteForLabel("日報入力")

        assertEquals("report_list", listRoute)
        assertEquals("report_entry", entryRoute)
        assertNotEquals(entryRoute, listRoute)
    }

    @Test
    fun topLevelNavigationDoesNotRestoreAnotherSavedDestination() {
        assertFalse(topLevelNavigationPolicy.saveState)
        assertFalse(topLevelNavigationPolicy.restoreState)
    }

    @Test
    fun reportDetailAndDatedEntryRemainSeparateRoutes() {
        assertEquals("report_detail/2026-07-24", ReportRoutes.detail("2026-07-24"))
        assertEquals("report_entry/2026-07-24", ReportRoutes.entry("2026-07-24"))
        assertNotEquals(
            ReportRoutes.detail("2026-07-24"),
            ReportRoutes.entry("2026-07-24")
        )
    }

    @Test
    fun onlyDatedReportEntryShowsDetailBackAction() {
        assertTrue(shouldShowDatedReportBack("2026-07-24"))
        assertFalse(shouldShowDatedReportBack(null))
        assertFalse(shouldShowDatedReportBack(""))
    }
}
