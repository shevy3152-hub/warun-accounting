package com.warun.accounting.data.metrics

import com.warun.accounting.domain.metrics.MetricPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessMetricRequestFactoryTest {
    @Test
    fun requestUsesInjectedInstantAndZoneWithoutReadingSystemClock() {
        val clock = object : BusinessMetricRequestClock {
            override fun now(): Instant = Instant.parse("2026-07-29T15:00:00Z")
            override val zone: ZoneId = ZoneId.of("Asia/Tokyo")
        }
        val factory = BusinessMetricSnapshotRequestFactory(clock)

        val request = factory.create(
            MetricPeriod.Daily(LocalDate.parse("2026-07-01")),
        )

        assertEquals(Instant.parse("2026-07-29T15:00:00Z"), request.calculatedAt)
        assertEquals(LocalDate.parse("2026-07-30"), request.evaluationDate)
    }
}
