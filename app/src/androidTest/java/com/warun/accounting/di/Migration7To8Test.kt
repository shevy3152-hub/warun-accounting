package com.warun.accounting.di

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesLegacyFoodPurchase() = withDatabase { databaseName ->
        createV7Database(databaseName) {
            insertDailyReport(foodPurchases = 12_000)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            DatabaseModule.MIGRATION_7_8
        ).use { database ->
            assertLegacyExpense(
                database = database,
                id = "legacy-food-report-1",
                expectedDate = REPORT_DATE,
                expectedCategory = "food_purchase",
                expectedAmount = 12_000
            )
            assertEquals(0, database.longValue("SELECT foodPurchases FROM daily_reports WHERE id = 'report-1'"))
            assertEquals(1, database.longValue("SELECT COUNT(*) FROM expense_records"))
        }
    }

    @Test
    fun matchingExistingRecordIsAcceptedWithoutDuplicate() = withDatabase { databaseName ->
        createV7Database(databaseName) {
            insertDailyReport(foodPurchases = 12_000)
            insertExpenseRecord(
                id = "legacy-food-report-1",
                category = "food_purchase",
                amount = 12_000
            )
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            DatabaseModule.MIGRATION_7_8
        ).use { database ->
            assertEquals(1, database.longValue("SELECT COUNT(*) FROM expense_records WHERE id = 'legacy-food-report-1'"))
            assertEquals(0, database.longValue("SELECT foodPurchases FROM daily_reports WHERE id = 'report-1'"))
        }
    }

    @Test
    fun amountConflictFailsAndPreservesData() = verifyConflictRollback(
        existingDate = REPORT_DATE,
        existingCategory = "food_purchase",
        existingAmount = 11_999,
        existingSourceType = "legacy_migration"
    )

    @Test
    fun categoryConflictFailsAndPreservesData() = verifyConflictRollback(
        existingDate = REPORT_DATE,
        existingCategory = "alcohol_purchase",
        existingAmount = 12_000,
        existingSourceType = "legacy_migration"
    )

    @Test
    fun dateConflictFailsAndPreservesData() = verifyConflictRollback(
        existingDate = "2026-06-30",
        existingCategory = "food_purchase",
        existingAmount = 12_000,
        existingSourceType = "legacy_migration"
    )

    @Test
    fun sourceTypeConflictFailsAndPreservesData() = verifyConflictRollback(
        existingDate = REPORT_DATE,
        existingCategory = "food_purchase",
        existingAmount = 12_000,
        existingSourceType = "manual"
    )

    @Test
    fun migratesLegacyAlcoholPurchase() = withDatabase { databaseName ->
        createV7Database(databaseName) {
            insertDailyReport(alcoholPurchases = 18_000)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            DatabaseModule.MIGRATION_7_8
        ).use { database ->
            assertLegacyExpense(
                database = database,
                id = "legacy-alcohol-report-1",
                expectedDate = REPORT_DATE,
                expectedCategory = "alcohol_purchase",
                expectedAmount = 18_000
            )
            assertEquals(0, database.longValue("SELECT alcoholPurchases FROM daily_reports WHERE id = 'report-1'"))
        }
    }

    @Test
    fun migratesLegacyOtherExpense() = withDatabase { databaseName ->
        createV7Database(databaseName) {
            insertDailyReport(otherExpense = 3_000)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            DatabaseModule.MIGRATION_7_8
        ).use { database ->
            assertLegacyExpense(
                database = database,
                id = "legacy-other-report-1",
                expectedDate = REPORT_DATE,
                expectedCategory = "other_expense",
                expectedAmount = 3_000
            )
            assertEquals(0, database.longValue("SELECT otherExpense FROM daily_reports WHERE id = 'report-1'"))
        }
    }

    @Test
    fun laterConflictRollsBackEarlierInsert() = withDatabase { databaseName ->
        createV7Database(databaseName) {
            insertDailyReport(foodPurchases = 12_000, alcoholPurchases = 18_000)
            insertExpenseRecord(
                id = "legacy-alcohol-report-1",
                category = "alcohol_purchase",
                amount = 17_999
            )
        }

        val failure = runCatching {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                8,
                true,
                DatabaseModule.MIGRATION_7_8
            ).close()
        }.exceptionOrNull()

        assertMigrationConflict(failure)
        openReadOnly(databaseName).use { database ->
            assertEquals(12_000, database.longValue("SELECT foodPurchases FROM daily_reports WHERE id = 'report-1'"))
            assertEquals(18_000, database.longValue("SELECT alcoholPurchases FROM daily_reports WHERE id = 'report-1'"))
            assertEquals(0, database.longValue("SELECT COUNT(*) FROM expense_records WHERE id = 'legacy-food-report-1'"))
            assertEquals(17_999, database.longValue("SELECT amount FROM expense_records WHERE id = 'legacy-alcohol-report-1'"))
            assertEquals(7, database.version)
        }
    }

    private fun verifyConflictRollback(
        existingDate: String,
        existingCategory: String,
        existingAmount: Long,
        existingSourceType: String
    ) = withDatabase { databaseName ->
        createV7Database(databaseName) {
            insertDailyReport(foodPurchases = 12_000)
            insertExpenseRecord(
                id = "legacy-food-report-1",
                expenseDate = existingDate,
                category = existingCategory,
                amount = existingAmount,
                sourceType = existingSourceType
            )
        }

        val failure = runCatching {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                8,
                true,
                DatabaseModule.MIGRATION_7_8
            ).close()
        }.exceptionOrNull()

        assertMigrationConflict(failure)
        openReadOnly(databaseName).use { database ->
            assertEquals(12_000, database.longValue("SELECT foodPurchases FROM daily_reports WHERE id = 'report-1'"))
            assertEquals(existingDate, database.stringValue("SELECT expenseDate FROM expense_records WHERE id = 'legacy-food-report-1'"))
            assertEquals(existingCategory, database.stringValue("SELECT category FROM expense_records WHERE id = 'legacy-food-report-1'"))
            assertEquals(existingAmount, database.longValue("SELECT amount FROM expense_records WHERE id = 'legacy-food-report-1'"))
            assertEquals(existingSourceType, database.stringValue("SELECT sourceType FROM expense_records WHERE id = 'legacy-food-report-1'"))
            assertEquals(7, database.version)
        }
    }

    private fun createV7Database(
        databaseName: String,
        populate: SupportSQLiteDatabase.() -> Unit
    ) {
        migrationHelper.createDatabase(databaseName, 7).use { database ->
            database.populate()
        }
    }

    private fun SupportSQLiteDatabase.insertDailyReport(
        foodPurchases: Long = 0,
        alcoholPurchases: Long = 0,
        otherExpense: Long = 0
    ) {
        execSQL(
            """
            INSERT INTO daily_reports (
                id, reportDate, status, authorName, cashSales, cardSales, qrSales, accountsReceivableSales, otherSales,
                foodPurchases, alcoholPurchases, consumablesExpense, utilitiesExpense,
                electricityExpense, gasExpense, waterExpense, communicationExpense,
                rentExpense, miscellaneousExpense, otherExpense, openingCash,
                actualClosingCash, customerCount, groupCount, memo, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "report-1", REPORT_DATE, "Draft", "本人",
                0, 0, 0, 0, 0,
                foodPurchases, alcoholPurchases, 0, 0,
                0, 0, 0, 0,
                0, 0, otherExpense, 0,
                0, 0, 0, null, 1_000, 1_000
            )
        )
    }

    private fun SupportSQLiteDatabase.insertExpenseRecord(
        id: String,
        expenseDate: String = REPORT_DATE,
        category: String,
        amount: Long,
        sourceType: String = "legacy_migration"
    ) {
        execSQL(
            """
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod,
                memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                id, expenseDate, category, null, amount, null,
                "Legacy total migrated from DailyReport", null, sourceType, 1_000, 1_000
            )
        )
    }

    private fun assertLegacyExpense(
        database: SupportSQLiteDatabase,
        id: String,
        expectedDate: String,
        expectedCategory: String,
        expectedAmount: Long
    ) {
        database.query(
            "SELECT expenseDate, category, amount, sourceType FROM expense_records WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedDate, cursor.getString(0))
            assertEquals(expectedCategory, cursor.getString(1))
            assertEquals(expectedAmount, cursor.getLong(2))
            assertEquals("legacy_migration", cursor.getString(3))
        }
    }

    private fun assertMigrationConflict(failure: Throwable?) {
        assertNotNull("Migration was expected to fail", failure)
        assertTrue(
            "Expected legacy conflict but was: $failure",
            generateSequence(failure) { it.cause }
                .mapNotNull { it.message }
                .any { it.contains("Legacy ExpenseRecord conflict") }
        )
    }

    private fun SupportSQLiteDatabase.longValue(sql: String): Long =
        query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringValue(sql: String): String =
        query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.longValue(sql: String): Long =
        rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SQLiteDatabase.stringValue(sql: String): String =
        rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun openReadOnly(databaseName: String): SQLiteDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    private inline fun withDatabase(block: (String) -> Unit) {
        val databaseName = "migration-7-8-" + UUID.randomUUID() + ".db"
        block(databaseName)
    }

    private companion object {
        const val REPORT_DATE = "2026-07-01"
    }
}
