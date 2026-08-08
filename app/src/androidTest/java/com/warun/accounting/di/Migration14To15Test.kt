package com.warun.accounting.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration14To15Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesPrepaidCancellationLedgerExpenseAndEvidence() {
        val databaseName = "migration-14-15-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 14).use(::insertVersion14Data)

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            15,
            true,
            DatabaseModule.MIGRATION_14_15
        ).use { database ->
            assertEquals(1L, database.count("expense_records"))
            assertEquals(1L, database.count("expense_cancellations"))
            assertEquals(2L, database.count("prepaid_transactions"))
            assertEquals(1L, database.count("expense_prepaid_links"))
            assertEquals(1L, database.count("evidence_records"))
            assertEquals(1L, database.count("expense_evidence_links"))
            assertEquals(
                "existing-purchase",
                database.stringValue(
                    "SELECT originalPurchaseTransactionId FROM expense_cancellations " +
                        "WHERE expenseId = 'existing-prepaid-expense'"
                )
            )
            assertEquals(
                "existing-reversal",
                database.stringValue(
                    "SELECT reversalTransactionId FROM expense_cancellations " +
                        "WHERE expenseId = 'existing-prepaid-expense'"
                )
            )
            assertEquals(
                "existing-evidence",
                database.stringValue(
                    "SELECT evidenceId FROM expense_evidence_links " +
                        "WHERE expenseId = 'existing-prepaid-expense'"
                )
            )
            assertEquals(
                setOf(
                    "expenseId->expense_records:RESTRICT:RESTRICT",
                    "originalPurchaseTransactionId->prepaid_transactions:RESTRICT:RESTRICT",
                    "reversalTransactionId->prepaid_transactions:RESTRICT:RESTRICT"
                ),
                database.foreignKeys("expense_cancellations")
            )
            assertTrue(
                "original purchase must be nullable",
                !database.columnIsNotNull(
                    "expense_cancellations",
                    "originalPurchaseTransactionId"
                )
            )
            assertTrue(
                "reversal must be nullable",
                !database.columnIsNotNull("expense_cancellations", "reversalTransactionId")
            )
            assertEquals(
                setOf(
                    "index_expense_cancellations_operationKey",
                    "index_expense_cancellations_originalPurchaseTransactionId",
                    "index_expense_cancellations_reversalTransactionId"
                ),
                database.indexNames("expense_cancellations")
                    .filterNot { it.startsWith("sqlite_autoindex") }
                    .toSet()
            )

            database.execSQL(
                """
                INSERT INTO expense_records (
                    id, expenseDate, category, supplierName, amount, paymentMethod,
                    memo, receiptId, sourceType, createdAt, updatedAt
                ) VALUES (
                    'cash-expense', '2026-08-08', 'other_expense', 'cash store',
                    300, '現金', '', NULL, 'manual', 3, 3
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
                    'cash-expense',
                    'expense-cancel:123e4567-e89b-42d3-a456-426614174001',
                    '${"d".repeat(64)}', NULL, NULL, '2026-08-08', 4, NULL
                )
                """.trimIndent()
            )
            database.query(
                "SELECT originalPurchaseTransactionId, reversalTransactionId " +
                    "FROM expense_cancellations WHERE expenseId = 'cash-expense'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
                assertNull(cursor.getString(1))
            }
        }
    }

    private fun insertVersion14Data(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod,
                memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (
                'existing-prepaid-expense', '2026-07-29', 'food_purchase', 'store',
                500, 'プリペイド', 'memo', NULL, 'manual', 1, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO evidence_records (
                id, captureId, storedUri, byteSize, sha256, state,
                createdAt, storedAt, updatedAt
            ) VALUES (
                'existing-evidence', 'existing-capture', 'file:/existing.jpg',
                100, '${"b".repeat(64)}', 'stored', 1, 1, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_evidence_links (expenseId, evidenceId, linkedAt)
            VALUES ('existing-prepaid-expense', 'existing-evidence', 1)
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
            ) VALUES
                ('existing-purchase', 'prepaid-majica', '2026-07-29', 'PURCHASE', -500,
                 'existing-prepaid-expense', NULL, NULL, 'existing-purchase-operation', '', 1),
                ('existing-reversal', 'prepaid-majica', '2026-07-30', 'REVERSAL', 500,
                 'existing-prepaid-expense', NULL, 'existing-purchase',
                 'expense-cancel:123e4567-e89b-42d3-a456-426614174000:reversal', '', 2)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_prepaid_links (
                expenseId, purchaseTransactionId, linkedAt, updatedAt
            ) VALUES ('existing-prepaid-expense', 'existing-purchase', 1, 1)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_cancellations (
                expenseId, operationKey, requestFingerprint,
                originalPurchaseTransactionId, reversalTransactionId,
                cancellationDate, cancelledAt, reason
            ) VALUES (
                'existing-prepaid-expense',
                'expense-cancel:123e4567-e89b-42d3-a456-426614174000',
                '${"a".repeat(64)}', 'existing-purchase', 'existing-reversal',
                '2026-07-30', 2, NULL
            )
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
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

    private fun SupportSQLiteDatabase.columnIsNotNull(
        table: String,
        column: String
    ): Boolean = query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        val notNullIndex = cursor.getColumnIndex("notnull")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                return@use cursor.getInt(notNullIndex) == 1
            }
        }
        error("Missing column $table.$column")
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
