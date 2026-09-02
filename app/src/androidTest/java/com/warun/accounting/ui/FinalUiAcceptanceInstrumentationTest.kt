package com.warun.accounting.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import com.warun.accounting.MainActivity
import com.warun.accounting.ui.submit.ElectronicSubmissionScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class FinalUiAcceptanceInstrumentationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun reportEntryAndElectronicSubmissionLastActionsAreReachable() {
        composeRule.onNodeWithTag("nav-report_entry").performClick()
        composeRule.onNodeWithTag("report-entry-last-action")
            .performScrollTo()
            .assertIsDisplayed()

        scenario.onActivity { activity ->
            activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    ElectronicSubmissionScreen()
                }
            }
        }
        composeRule.onNodeWithTag("electronic-submission-last-action")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun septemberFirstIsTuesdayAndCalendarColumnsStayAlignedAcrossMonths() {
        composeRule.onNodeWithTag("nav-report_entry").performClick()
        composeRule.onNodeWithContentDescription("カレンダーを開く").performClick()
        waitForCalendarDay("2026-09-01")

        assertColumnAligned("calendar-weekday-火", "calendar-day-2026-09-01")
        assertColumnAligned("calendar-weekday-日", "calendar-day-2026-09-06")

        composeRule.onNodeWithText("前月").performClick()
        waitForCalendarDay("2026-08-01")
        assertColumnAligned("calendar-weekday-土", "calendar-day-2026-08-01")

        composeRule.onNodeWithText("次月").performClick()
        waitForCalendarDay("2026-09-01")
        assertColumnAligned("calendar-weekday-火", "calendar-day-2026-09-01")

        composeRule.onNodeWithText("次月").performClick()
        waitForCalendarDay("2026-10-01")
        assertColumnAligned("calendar-weekday-木", "calendar-day-2026-10-01")
    }

    private fun waitForCalendarDay(date: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("calendar-day-$date").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("calendar-day-$date").assertIsDisplayed()
    }

    private fun assertColumnAligned(weekdayTag: String, dayTag: String) {
        val weekdayBounds = composeRule.onNodeWithTag(weekdayTag).getUnclippedBoundsInRoot()
        val dayBounds = composeRule.onNodeWithTag(dayTag).getUnclippedBoundsInRoot()
        val weekdayX = (weekdayBounds.left.value + weekdayBounds.right.value) / 2f
        val dayX = (dayBounds.left.value + dayBounds.right.value) / 2f
        assertTrue("$weekdayTag and $dayTag column mismatch", abs(weekdayX - dayX) <= 1f)
    }
}
