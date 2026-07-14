package com.warun.accounting.di

import android.content.Context
import androidx.room.Room
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.OfflineAccountingRepository
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.local.WarunDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WarunDatabase =
        Room.databaseBuilder(context, WarunDatabase::class.java, "warun-accounting.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDao(database: WarunDatabase): WarunDao = database.warunDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAccountingRepository(
        repository: OfflineAccountingRepository
    ): AccountingRepository
}
