package com.warun.accounting.data.metrics

import com.warun.accounting.domain.metrics.MetricPeriod
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

interface BusinessMetricRequestClock {
    fun now(): Instant

    val zone: ZoneId
        get() = ZoneId.systemDefault()
}

class SystemBusinessMetricRequestClock @Inject constructor() : BusinessMetricRequestClock {
    override fun now(): Instant = Instant.now()
}

class BusinessMetricSnapshotRequestFactory @Inject constructor(
    private val clock: BusinessMetricRequestClock,
) {
    fun create(period: MetricPeriod): BusinessMetricSnapshotRequest {
        val calculatedAt = clock.now()
        return BusinessMetricSnapshotRequest(
            period = period,
            evaluationDate = calculatedAt.atZone(clock.zone).toLocalDate(),
            calculatedAt = calculatedAt,
        )
    }
}
