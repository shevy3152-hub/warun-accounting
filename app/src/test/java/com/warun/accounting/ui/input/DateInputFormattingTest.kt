package com.warun.accounting.ui.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateInputFormattingTest {
    @Test
    fun insertsSeparatorsIntoEightDigits() {
        assertEquals("2026-07-24", formatDateInput("20260724", currentYear = 2026))
    }

    @Test
    fun insertsSeparatorsWhileDigitsArriveOneAtATime() {
        var value = ""
        "20260724".forEach { digit ->
            value = formatDateInput(value + digit, currentYear = 2026)
        }

        assertEquals("2026-07-24", value)
    }

    @Test
    fun formattedTextFieldKeepsCursorAfterInsertedSeparators() {
        var value = TextFieldValue("")
        "20260724".forEach { digit ->
            val cursor = value.selection.end
            val incomingText = value.text.substring(0, cursor) +
                digit +
                value.text.substring(cursor)
            value = formatDateFieldValue(
                incoming = TextFieldValue(
                    text = incomingText,
                    selection = TextRange(cursor + 1)
                ),
                currentYear = 2026
            )
            assertEquals(value.text.length, value.selection.end)
        }

        assertEquals("2026-07-24", value.text)
    }

    @Test
    fun normalizesFullWidthDigitsAndJapaneseSeparators() {
        assertEquals("2026-07-24", formatDateInput("２０２６年７月２４日", currentYear = 2026))
    }

    @Test
    fun acceptsSlashDotMiddleDotAndLongDash() {
        assertEquals("2026-07-24", formatDateInput("2026/7/24", currentYear = 2026))
        assertEquals("2026-07-24", formatDateInput("2026．7．24", currentYear = 2026))
        assertEquals("2026-07-24", formatDateInput("2026・7・24", currentYear = 2026))
        assertEquals("2026-07-24", formatDateInput("2026ー7ー24", currentYear = 2026))
    }

    @Test
    fun prefixesDynamicCurrentYearForMonthAndDay() {
        assertEquals("2031-07-24", formatDateInput("0724", currentYear = 2031))
        assertEquals("2031-07-24", formatDateInput("7/24", currentYear = 2031))
        assertEquals("${LocalDate.now().year}-07-24", formatDateInput("0724"))
    }

    @Test
    fun keepsPartialYearEntryEditableWithoutDuplicateSeparators() {
        assertEquals("2", formatDateInput("2", currentYear = 2026))
        assertEquals("2026", formatDateInput("2026", currentYear = 2026))
        assertEquals("2026-", formatDateInput("2026-", currentYear = 2026))
        assertEquals("2026-0", formatDateInput("2026-0", currentYear = 2026))
        assertEquals("2026-07-", formatDateInput("2026-07-", currentYear = 2026))
    }

    @Test
    fun ignoresNonDateCharactersAndLimitsDigits() {
        assertEquals("2026-07-24", formatDateInput("a2026072499z", currentYear = 2026))
    }

    @Test
    fun pastedDateAndExistingOcrDateKeepTheSameMeaning() {
        assertEquals("2026-07-24", formatDateInput("20260724", currentYear = 2031))
        assertEquals("2026-07-24", formatDateInput("2026-07-24", currentYear = 2031))
    }

    @Test
    fun impossibleCalendarDateIsNotConvertedIntoAnotherValidDate() {
        val formatted = formatDateInput("20260230", currentYear = 2026)

        assertEquals("2026-02-30", formatted)
        assertTrue(runCatching { LocalDate.parse(formatted) }.isFailure)
    }

    @Test
    fun backspaceCanDeleteAFormattedDateCompletely() {
        var value = TextFieldValue(
            text = "2026-07-24",
            selection = TextRange("2026-07-24".length)
        )
        repeat(10) {
            val cursor = value.selection.end
            value = formatDateFieldValue(
                incoming = TextFieldValue(
                    text = value.text.removeRange(cursor - 1, cursor),
                    selection = TextRange(cursor - 1)
                ),
                currentYear = 2026
            )
        }

        assertEquals("", value.text)
        assertEquals(0, value.selection.end)
    }
}
