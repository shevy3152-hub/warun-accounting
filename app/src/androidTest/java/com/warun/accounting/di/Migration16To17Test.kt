package com.warun.accounting.di

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration16To17Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationAddsFixedCostTablesAndMediaTypeWithoutBackfill() {
        val databaseName = "migration-16-17-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 16).use { database ->
            database.execSQL(
                """
                INSERT INTO daily_reports(
                    id, reportDate, status, authorName, cashSales, cardSales, qrSales,
                    accountsReceivableSales, otherSales, foodPurchases, alcoholPurchases,
                    consumablesExpense, utilitiesExpense, electricityExpense, gasExpense,
                    waterExpense, communicationExpense, rentExpense, accountantFeeExpense,
                    miscellaneousExpense, otherExpense, openingCash, actualClosingCash,
                    customerCount, groupCount, memo, createdAt, updatedAt, hasActualClosingCash
                ) VALUES ('r1', '2026-08-01', 'completed', NULL, 100, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 1, 1, NULL, 1, 1, 0)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO receipts(
                    id, purchaseDate, capturedDate, registeredAt, storeName, totalAmount,
                    taxAmount, registrationNumber, expenseCategory, isConfirmed, memo, updatedAt
                ) VALUES ('receipt1', '2026-08-01', '2026-08-01', 1, 'store', 100, 0,
                    NULL, NULL, 0, NULL, 1)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO expense_records(
                    id, expenseDate, category, supplierName, amount, paymentMethod, memo,
                    receiptId, sourceType, createdAt, updatedAt
                ) VALUES ('expense1', '2026-08-01', 'other_expense', 'store', 100, '現金',
                    NULL, 'receipt1', 'manual', 1, 1)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO monthly_submissions(targetMonth, status, submittedAt, updatedAt)
                VALUES ('2026-08', 'submitted', 1, 1)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO evidence_records(
                    id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt
                ) VALUES ('e1', 'c1', 'file:/e1.jpg', 1, '${"0".repeat(64)}', 'stored', 1, 2, 2)
                """.trimIndent()
            )
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            17,
            true,
            DatabaseModule.MIGRATION_16_17
        ).use { database ->
            assertEquals("image/jpeg", database.stringValue("SELECT mediaType FROM evidence_records WHERE id = 'e1'"))
            assertEquals(1L, database.count("daily_reports"))
            assertEquals(1L, database.count("receipts"))
            assertEquals(1L, database.count("expense_records"))
            assertEquals(1L, database.count("monthly_submissions"))
            assertTrue(database.tableExists("fixed_cost_receipt_applications"))
            assertTrue(database.tableExists("fixed_cost_evidence_links"))
            assertEquals(0L, database.count("fixed_cost_receipt_applications"))
            assertEquals(0L, database.count("fixed_cost_evidence_links"))
            assertEquals(
                setOf(
                    "index_fixed_cost_receipt_applications_receiptId",
                    "index_fixed_cost_receipt_applications_dailyReportId_fixedCostType"
                ),
                database.explicitIndexNames("fixed_cost_receipt_applications")
            )
            assertEquals(
                setOf(
                    "index_fixed_cost_evidence_links_applicationId_sortOrder",
                    "index_fixed_cost_evidence_links_evidenceId"
                ),
                database.explicitIndexNames("fixed_cost_evidence_links")
            )
        }
    }

    private fun SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringValue(sql: String): String =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.tableExists(table: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { cursor -> cursor.moveToFirst() }

    private fun SupportSQLiteDatabase.explicitIndexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                val originIndex = cursor.getColumnIndex("origin")
                while (cursor.moveToNext()) {
                    if (cursor.getString(originIndex) == "c") add(cursor.getString(nameIndex))
                }
            }
        }
}
