package com.warun.accounting.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
    private const val DatabaseName = "warun-accounting.db"

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN electricityExpense INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN gasExpense INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN waterExpense INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN communicationExpense INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN rentExpense INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS expense_records (
                    id TEXT NOT NULL,
                    expenseDate TEXT NOT NULL,
                    category TEXT NOT NULL,
                    supplierName TEXT,
                    amount INTEGER NOT NULL,
                    paymentMethod TEXT,
                    memo TEXT,
                    receiptId TEXT,
                    sourceType TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(receiptId) REFERENCES receipts(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_records_expenseDate ON expense_records(expenseDate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_records_category ON expense_records(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_records_receiptId ON expense_records(receiptId)")
            db.execSQL(
                """
                INSERT INTO expense_records (
                    id, expenseDate, category, supplierName, amount, paymentMethod, memo, receiptId, sourceType, createdAt, updatedAt
                )
                SELECT
                    'legacy-food-' || id,
                    reportDate,
                    'food_purchase',
                    NULL,
                    foodPurchases,
                    NULL,
                    'v6 DailyReport foodPurchases',
                    NULL,
                    'legacy_migration',
                    createdAt,
                    updatedAt
                FROM daily_reports
                WHERE foodPurchases > 0
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO expense_records (
                    id, expenseDate, category, supplierName, amount, paymentMethod, memo, receiptId, sourceType, createdAt, updatedAt
                )
                SELECT
                    'legacy-alcohol-' || id,
                    reportDate,
                    'alcohol_purchase',
                    NULL,
                    alcoholPurchases,
                    NULL,
                    'v6 DailyReport alcoholPurchases',
                    NULL,
                    'legacy_migration',
                    createdAt,
                    updatedAt
                FROM daily_reports
                WHERE alcoholPurchases > 0
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO expense_records (
                    id, expenseDate, category, supplierName, amount, paymentMethod, memo, receiptId, sourceType, createdAt, updatedAt
                )
                SELECT
                    'legacy-other-' || id,
                    reportDate,
                    'other_expense',
                    NULL,
                    otherExpense,
                    NULL,
                    'v6 DailyReport otherExpense',
                    NULL,
                    'legacy_migration',
                    createdAt,
                    updatedAt
                FROM daily_reports
                WHERE otherExpense > 0
                """.trimIndent()
            )
            db.execSQL("UPDATE daily_reports SET foodPurchases = 0, alcoholPurchases = 0, otherExpense = 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WarunDatabase {
        resetIncompatibleDevV7Database(context)
        return Room.databaseBuilder(context, WarunDatabase::class.java, DatabaseName)
            .addMigrations(MIGRATION_6_7)
            .build()
    }

    private fun resetIncompatibleDevV7Database(context: Context) {
        val dbFile = context.getDatabasePath(DatabaseName)
        if (!dbFile.exists()) return

        var shouldDelete = false
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            shouldDelete = database.version == 7 &&
                (!database.hasTable("expense_records") ||
                    !database.hasColumns(
                        "daily_reports",
                        setOf(
                            "electricityExpense",
                            "gasExpense",
                            "waterExpense",
                            "communicationExpense",
                            "rentExpense"
                        )
                    ))
        } catch (_: Exception) {
            shouldDelete = false
        } finally {
            database?.close()
        }

        if (shouldDelete) {
            context.deleteDatabase(DatabaseName)
        }
    }

    private fun SQLiteDatabase.hasTable(tableName: String): Boolean {
        val cursor = rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        )
        return cursor.use { it.moveToFirst() }
    }

    private fun SQLiteDatabase.hasColumns(tableName: String, requiredColumns: Set<String>): Boolean {
        val columns = mutableSetOf<String>()
        val cursor = rawQuery("PRAGMA table_info(" + tableName + ")", null)
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                columns += it.getString(nameIndex)
            }
        }
        return columns.containsAll(requiredColumns)
    }

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