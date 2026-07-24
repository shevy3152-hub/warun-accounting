package com.warun.accounting.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun existingExpenseIsRetainedAndEvidenceTablesStartEmpty() {
        val databaseName = "migration-10-11-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 10).use { database ->
            database.execSQL(
                """
                INSERT INTO daily_reports (
                    id, reportDate, status, authorName, cashSales, cardSales, qrSales,
                    accountsReceivableSales, otherSales, foodPurchases, alcoholPurchases,
                    consumablesExpense, utilitiesExpense, electricityExpense, gasExpense,
                    waterExpense, communicationExpense, rentExpense, accountantFeeExpense,
                    miscellaneousExpense, otherExpense, openingCash, actualClosingCash,
                    customerCount, groupCount, memo, createdAt, updatedAt, hasActualClosingCash
                ) VALUES (
                    'existing-report', '2026-07-22', 'completed', 'owner', 10000, 2000, 0,
                    0, 0, 0, 0, 500, 300, 100, 100, 100, 0, 0, 0, 0, 0, 5000, 16000,
                    10, 5, 'existing memo', 1, 2, 1
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO receipts (
                    id, purchaseDate, capturedDate, registeredAt, storeName, totalAmount,
                    taxAmount, registrationNumber, expenseCategory, isConfirmed, memo, updatedAt
                ) VALUES (
                    'existing-receipt', '2026-07-22', '2026-07-22', 1, 'existing-store',
                    1540, 140, NULL, 'food_purchase', 1, NULL, 2
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO expense_records (
                    id, expenseDate, category, supplierName, amount, paymentMethod,
                    memo, receiptId, sourceType, createdAt, updatedAt
                ) VALUES ('existing-expense', '2026-07-22', 'food_purchase',
                    'existing-store', 1540, '現金', NULL, NULL, 'manual', 1, 2)
                """.trimIndent()
            )
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            DatabaseModule.MIGRATION_10_11
        ).use { database ->
            assertEquals(1L, database.count("daily_reports"))
            assertEquals(10_000L, database.value("SELECT cashSales FROM daily_reports WHERE id = 'existing-report'"))
            assertEquals(1L, database.count("receipts"))
            assertEquals(1_540L, database.value("SELECT totalAmount FROM receipts WHERE id = 'existing-receipt'"))
            assertEquals(1L, database.count("expense_records"))
            assertEquals(1_540L, database.value("SELECT amount FROM expense_records WHERE id = 'existing-expense'"))
            assertEquals(0L, database.count("evidence_records"))
            assertEquals(0L, database.count("expense_evidence_links"))

            database.execSQL(
                """
                INSERT INTO evidence_records (
                    id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt
                ) VALUES (
                    'new-evidence', 'new-evidence', 'file:/stored/new-evidence.jpg', 100,
                    'sha-new-evidence', 'stored', 3, 4, 4
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO expense_evidence_links (expenseId, evidenceId, linkedAt)
                VALUES ('existing-expense', 'new-evidence', 4)
                """.trimIndent()
            )
            assertEquals(1L, database.count("evidence_records"))
            assertEquals(1L, database.count("expense_evidence_links"))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Long =
        value("SELECT COUNT(*) FROM $table")

    private fun androidx.sqlite.db.SupportSQLiteDatabase.value(sql: String): Long =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
}
