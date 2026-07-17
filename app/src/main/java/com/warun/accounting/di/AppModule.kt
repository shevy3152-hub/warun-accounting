package com.warun.accounting.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN paymentMethod TEXT")
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN expenseInputSchemaVersion INTEGER NOT NULL DEFAULT 1")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WarunDatabase =
        Room.databaseBuilder(context, WarunDatabase::class.java, "warun-accounting.db")
            .addMigrations(MIGRATION_6_7)
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
