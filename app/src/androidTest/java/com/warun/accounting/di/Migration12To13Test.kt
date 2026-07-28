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
class Migration12To13Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesVersion12DataAndAddsEmptyEditOperationTable() {
        val databaseName = "migration-12-13-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 12).use(::insertVersion12Data)

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            13,
            true,
            DatabaseModule.MIGRATION_12_13
        ).use { database ->
            assertEquals(1L, database.count("daily_reports"))
            assertEquals(1L, database.count("receipts"))
            assertEquals(1L, database.count("expense_records"))
            assertEquals(1L, database.count("evidence_records"))
            assertEquals(1L, database.count("expense_evidence_links"))
            assertEquals(1L, database.count("prepaid_accounts"))
            assertEquals(1L, database.count("prepaid_transactions"))
            assertEquals(1L, database.count("expense_prepaid_links"))
            assertEquals(0L, database.count("expense_edit_operations"))
            assertEquals(
                500L,
                database.longValue(
                    "SELECT amount FROM expense_records WHERE id = 'existing-expense'"
                )
            )
            assertEquals(
                -500L,
                database.longValue(
                    "SELECT balanceDelta FROM prepaid_transactions WHERE id = 'existing-purchase'"
                )
            )
            assertEquals(
                "existing-purchase",
                database.stringValue(
                    """
                    SELECT purchaseTransactionId FROM expense_prepaid_links
                    WHERE expenseId = 'existing-expense'
                    """.trimIndent()
                )
            )
            assertTrue(
                "expenseId index missing",
                "index_expense_edit_operations_expenseId" in
                    database.indexNames("expense_edit_operations")
            )
            val columns = database.columnNames("expense_edit_operations")
            assertTrue(
                columns.containsAll(
                    setOf(
                        "operationKey",
                        "expenseId",
                        "requestFingerprint",
                        "status",
                        "createdAt",
                        "updatedAt",
                        "completedAt",
                        "resultPaymentMethod",
                        "resultPrepaidAccountId",
                        "resultAmount"
                    )
                )
            )
            assertSqlFails {
                database.execSQL(
                    """
                    INSERT INTO expense_edit_operations (
                        operationKey, expenseId, requestFingerprint, status,
                        createdAt, updatedAt, completedAt, resultPaymentMethod,
                        resultPrepaidAccountId, resultAmount
                    ) VALUES (
                        'duplicate-key', 'existing-expense', 'fingerprint-1', 'STARTED',
                        1, 1, NULL, NULL, NULL, NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO expense_edit_operations (
                        operationKey, expenseId, requestFingerprint, status,
                        createdAt, updatedAt, completedAt, resultPaymentMethod,
                        resultPrepaidAccountId, resultAmount
                    ) VALUES (
                        'duplicate-key', 'existing-expense', 'fingerprint-2', 'STARTED',
                        2, 2, NULL, NULL, NULL, NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun insertVersion12Data(database: SupportSQLiteDatabase) {
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
                'existing-report', '2026-07-28', 'completed', 'owner', 1000, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100000, 101000,
                1, 1, 'existing', 1, 1, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO receipts (
                id, purchaseDate, capturedDate, registeredAt, storeName, totalAmount,
                taxAmount, registrationNumber, expenseCategory, isConfirmed, memo, updatedAt
            ) VALUES (
                'existing-receipt', '2026-07-28', '2026-07-28', 1, 'store',
                500, 0, NULL, 'other_expense', 1, NULL, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod,
                memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (
                'existing-expense', '2026-07-28', 'other_expense', 'store', 500,
                'プリペイド', 'memo', 'existing-receipt', 'manual', 1, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO evidence_records (
                id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt
            ) VALUES (
                'existing-evidence', 'existing-capture', 'file:/existing.jpg',
                100, 'sha', 'stored', 1, 1, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_evidence_links (expenseId, evidenceId, linkedAt)
            VALUES ('existing-expense', 'existing-evidence', 1)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO prepaid_accounts (id, type, name, isActive, createdAt, updatedAt)
            VALUES ('prepaid-majica', 'MAJICA', 'majica', 1, 0, 0)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO prepaid_transactions (
                id, accountId, transactionDate, transactionType, balanceDelta,
                expenseId, chargeSource, reversalOfTransactionId, operationKey, memo, createdAt
            ) VALUES (
                'existing-purchase', 'prepaid-majica', '2026-07-28', 'PURCHASE', -500,
                'existing-expense', NULL, NULL, 'existing-operation', '', 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_prepaid_links (
                expenseId, purchaseTransactionId, linkedAt, updatedAt
            ) VALUES ('existing-expense', 'existing-purchase', 1, 1)
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Long =
        longValue("SELECT COUNT(*) FROM $table")

    private fun SupportSQLiteDatabase.longValue(sql: String): Long =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringValue(sql: String): String =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SupportSQLiteDatabase.columnNames(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun assertSqlFails(block: () -> Unit) {
        assertTrue("Expected SQLite constraint failure", runCatching(block).isFailure)
    }
}
