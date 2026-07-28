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
class Migration13To14Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesVersion13DataAndAddsEmptyCancellationTable() {
        val databaseName = "migration-13-14-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 13).use(::insertVersion13Data)

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            DatabaseModule.MIGRATION_13_14
        ).use { database ->
            assertEquals(1L, database.count("daily_reports"))
            assertEquals(1L, database.count("expense_records"))
            assertEquals(1L, database.count("evidence_records"))
            assertEquals(1L, database.count("expense_evidence_links"))
            assertEquals(1L, database.count("prepaid_accounts"))
            assertEquals(1L, database.count("prepaid_transactions"))
            assertEquals(1L, database.count("expense_prepaid_links"))
            assertEquals(1L, database.count("expense_edit_operations"))
            assertEquals(0L, database.count("expense_cancellations"))
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

            val indices = database.indexNames("expense_cancellations")
            assertTrue("operationKey index missing", "index_expense_cancellations_operationKey" in indices)
            assertTrue(
                "purchase index missing",
                "index_expense_cancellations_originalPurchaseTransactionId" in indices
            )
            assertTrue(
                "reversal index missing",
                "index_expense_cancellations_reversalTransactionId" in indices
            )
            assertEquals(
                setOf(
                    "expenseId->expense_records:RESTRICT:RESTRICT",
                    "originalPurchaseTransactionId->prepaid_transactions:RESTRICT:RESTRICT",
                    "reversalTransactionId->prepaid_transactions:RESTRICT:RESTRICT"
                ),
                database.foreignKeys("expense_cancellations")
            )

            database.execSQL(
                """
                INSERT INTO prepaid_transactions (
                    id, accountId, transactionDate, transactionType, balanceDelta,
                    expenseId, chargeSource, reversalOfTransactionId, operationKey,
                    memo, createdAt
                ) VALUES (
                    'existing-reversal', 'prepaid-majica', '2026-07-29', 'REVERSAL', 500,
                    'existing-expense', NULL, 'existing-purchase',
                    'expense-cancel-reversal', '', 2
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO expense_cancellations (
                    expenseId, operationKey, requestFingerprint,
                    originalPurchaseTransactionId, reversalTransactionId,
                    cancellationDate, cancelledAt, reason
                ) VALUES (
                    'existing-expense',
                    'expense-cancel:123e4567-e89b-42d3-a456-426614174000',
                    '${"a".repeat(64)}',
                    'existing-purchase', 'existing-reversal',
                    '2026-07-29', 2, NULL
                )
                """.trimIndent()
            )
            assertEquals(1L, database.count("expense_cancellations"))
        }
    }

    private fun insertVersion13Data(database: SupportSQLiteDatabase) {
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
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod,
                memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (
                'existing-expense', '2026-07-28', 'other_expense', 'store', 500,
                'プリペイド', 'memo', NULL, 'manual', 1, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO evidence_records (
                id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt
            ) VALUES (
                'existing-evidence', 'existing-capture', 'file:/existing.jpg',
                100, '${"b".repeat(64)}', 'stored', 1, 1, 1
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
                expenseId, chargeSource, reversalOfTransactionId, operationKey,
                memo, createdAt
            ) VALUES (
                'existing-purchase', 'prepaid-majica', '2026-07-28', 'PURCHASE', -500,
                'existing-expense', NULL, NULL, 'existing-purchase-operation', '', 1
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
        database.execSQL(
            """
            INSERT INTO expense_edit_operations (
                operationKey, expenseId, requestFingerprint, status,
                createdAt, updatedAt, completedAt, resultPaymentMethod,
                resultPrepaidAccountId, resultAmount
            ) VALUES (
                'existing-edit-operation', 'existing-expense', '${"c".repeat(64)}',
                'COMPLETED', 1, 1, 1, 'プリペイド', 'prepaid-majica', 500
            )
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

    private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SupportSQLiteDatabase.foreignKeys(table: String): Set<String> =
        query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildSet {
                val tableIndex = cursor.getColumnIndex("table")
                val fromIndex = cursor.getColumnIndex("from")
                val onUpdateIndex = cursor.getColumnIndex("on_update")
                val onDeleteIndex = cursor.getColumnIndex("on_delete")
                while (cursor.moveToNext()) {
                    add(
                        "${cursor.getString(fromIndex)}->${cursor.getString(tableIndex)}:" +
                            "${cursor.getString(onUpdateIndex)}:${cursor.getString(onDeleteIndex)}"
                    )
                }
            }
        }
}
