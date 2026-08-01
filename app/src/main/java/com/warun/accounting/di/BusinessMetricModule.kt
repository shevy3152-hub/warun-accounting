package com.warun.accounting.di

import com.warun.accounting.data.metrics.BusinessMetricRequestClock
import com.warun.accounting.data.metrics.SystemBusinessMetricRequestClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BusinessMetricModule {
    @Binds
    abstract fun bindBusinessMetricRequestClock(
        clock: SystemBusinessMetricRequestClock,
    ): BusinessMetricRequestClock
}
