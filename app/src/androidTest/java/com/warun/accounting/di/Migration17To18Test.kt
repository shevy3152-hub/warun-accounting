package com.warun.accounting.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
class Migration17To18Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationBackfillsCurrentAssignmentWithoutCreatingAuditHistory() {
        val databaseName = "migration-17-18-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 17).use { database ->
            database.execSQL(
                """
                INSERT INTO daily_reports(
                    id, reportDate, status, authorName, cashSales, cardSales, qrSales,
                    accountsReceivableSales, otherSales, foodPurchases, alcoholPurchases,
                    consumablesExpense, utilitiesExpense, electricityExpense, gasExpense,
                    waterExpense, communicationExpense, rentExpense, accountantFeeExpense,
                    miscellaneousExpense, otherExpense, openingCash, actualClosingCash,
                    customerCount, groupCount, memo, createdAt, updatedAt, hasActualClosingCash
                ) VALUES ('report-v17', '2026-08-23', 'completed', NULL, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 41617, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, NULL, 10, 11, 0)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO receipts(
                    id, purchaseDate, capturedDate, registeredAt, storeName, totalAmount,
                    taxAmount, registrationNumber, expenseCategory, isConfirmed, memo, updatedAt
                ) VALUES ('receipt-v17', '2026-08-23', '2026-08-23', 10, 'fixed-cost', 41617, 0,
                    NULL, NULL, 1, NULL, 11)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO fixed_cost_receipt_applications(
                    applicationId, receiptId, dailyReportId, fixedCostType, paymentMethod,
                    appliedAt, updatedAt
                ) VALUES ('application-v17', 'receipt-v17', 'report-v17', 'electricity', '現金', 20, 21)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO evidence_records(
                    id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt, mediaType
                ) VALUES ('evidence-v17', 'capture-v17', 'file:/evidence-v17.jpg', 1,
                    '${"0".repeat(64)}', 'stored', 30, 31, 32, 'image/jpeg')
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO fixed_cost_evidence_links(applicationId, evidenceId, sortOrder, linkedAt)
                VALUES ('application-v17', 'evidence-v17', 4, 33)
                """.trimIndent()
            )
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            18,
            true,
            DatabaseModule.MIGRATION_17_18
        ).use { database ->
            assertTrue(database.tableExists("fixed_cost_evidence_assignments"))
            assertTrue(database.tableExists("fixed_cost_evidence_assignment_audits"))
            assertEquals(
                "report-v17",
                database.stringValue("SELECT dailyReportId FROM fixed_cost_evidence_assignments WHERE evidenceId = 'evidence-v17'")
            )
            assertEquals(
                "electricity",
                database.stringValue("SELECT fixedCostType FROM fixed_cost_evidence_assignments WHERE evidenceId = 'evidence-v17'")
            )
            assertEquals(4L, database.longValue("SELECT sortOrder FROM fixed_cost_evidence_assignments WHERE evidenceId = 'evidence-v17'"))
            assertEquals(33L, database.longValue("SELECT assignedAt FROM fixed_cost_evidence_assignments WHERE evidenceId = 'evidence-v17'"))
            assertEquals(0L, database.longValue("SELECT COUNT(*) FROM fixed_cost_evidence_assignment_audits"))
            assertEquals(1L, database.longValue("SELECT COUNT(*) FROM fixed_cost_evidence_links"))
            assertEquals(1L, database.longValue("SELECT COUNT(*) FROM evidence_records"))
            assertEquals(41617L, database.longValue("SELECT electricityExpense FROM daily_reports WHERE id = 'report-v17'"))
        }
    }

    private fun SupportSQLiteDatabase.longValue(sql: String): Long = query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.stringValue(sql: String): String = query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getString(0)
    }

    private fun SupportSQLiteDatabase.tableExists(table: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { cursor -> cursor.moveToFirst() }
}
