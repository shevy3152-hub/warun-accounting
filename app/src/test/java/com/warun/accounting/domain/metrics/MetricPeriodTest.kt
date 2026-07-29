package com.warun.accounting.domain.metrics

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetricPeriodTest {
    @Test
    fun dailyUsesTheSameInclusiveStartAndEndDate() {
        val date = LocalDate.of(2026, 7, 29)

        val period = MetricPeriod.Daily(date)

        assertEquals(date, period.startDate)
        assertEquals(date, period.endDateInclusive)
    }

    @Test
    fun monthlyUsesCalendarMonthBoundaries() {
        val period = MetricPeriod.Monthly(YearMonth.of(2024, 2))

        assertEquals(LocalDate.of(2024, 2, 1), period.startDate)
        assertEquals(LocalDate.of(2024, 2, 29), period.endDateInclusive)
    }

    @Test
    fun customRangeUsesInclusiveBoundaries() {
        val period = MetricPeriod.CustomRange(
            startDate = LocalDate.of(2026, 6, 30),
            endDateInclusive = LocalDate.of(2026, 7, 2),
        )

        assertEquals(LocalDate.of(2026, 6, 30), period.startDate)
        assertEquals(LocalDate.of(2026, 7, 2), period.endDateInclusive)
    }

    @Test
    fun customRangeRejectsReversedBoundaries() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricPeriod.CustomRange(
                startDate = LocalDate.of(2026, 7, 2),
                endDateInclusive = LocalDate.of(2026, 6, 30),
            )
        }
    }
}
