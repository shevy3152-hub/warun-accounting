package com.warun.accounting.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration11To12Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesAccountingDataAndCreatesConstrainedEmptyLedger() {
        val databaseName = "migration-11-12-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 11).use { database ->
            insertExistingAccountingData(database)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            12,
            true,
            DatabaseModule.MIGRATION_11_12
        ).use { database ->
            assertExistingAccountingData(database)
            assertInitialLedger(database)
            assertIndexesAndForeignKeys(database)
            assertUniqueConstraints(database)

            DatabaseModule.MIGRATION_11_12.migrate(database)
            assertEquals(2L, database.count("prepaid_accounts"))
        }
    }

    private fun insertExistingAccountingData(database: SupportSQLiteDatabase) {
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
                'existing-report', '2026-07-25', 'completed', 'owner', 10000, 2000, 3000,
                4000, 5000, 0, 0, 500, 300, 100, 100, 100, 200, 300, 400,
                500, 0, 100000, 110000, 10, 5, 'existing memo', 1, 2, 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO receipts (
                id, purchaseDate, capturedDate, registeredAt, storeName, totalAmount,
                taxAmount, registrationNumber, expenseCategory, isConfirmed, memo, updatedAt
            ) VALUES (
                'existing-receipt', '2026-07-25', '2026-07-25', 1, 'existing-store',
                1540, 140, 'T123', 'food_purchase', 1, 'receipt memo', 2
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod,
                memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (
                'existing-expense', '2026-07-25', 'food_purchase', 'existing-store',
                1540, '現金', 'expense memo', 'existing-receipt', 'manual', 1, 2
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO evidence_records (
                id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt
            ) VALUES (
                'existing-evidence', 'existing-capture',
                'file:/stored/existing-evidence.jpg', 100, 'existing-sha',
                'stored', 1, 2, 2
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO expense_evidence_links (expenseId, evidenceId, linkedAt)
            VALUES ('existing-expense', 'existing-evidence', 2)
            """.trimIndent()
        )
    }

    private fun assertExistingAccountingData(database: SupportSQLiteDatabase) {
        assertEquals(1L, database.count("daily_reports"))
        assertEquals(
            10_000L,
            database.longValue(
                "SELECT cashSales FROM daily_reports WHERE id = 'existing-report'"
            )
        )
        assertEquals(1L, database.count("receipts"))
        assertEquals(
            1_540L,
            database.longValue(
                "SELECT totalAmount FROM receipts WHERE id = 'existing-receipt'"
            )
        )
        assertEquals(1L, database.count("expense_records"))
        assertEquals(
            1_540L,
            database.longValue(
                "SELECT amount FROM expense_records WHERE id = 'existing-expense'"
            )
        )
        assertEquals(1L, database.count("evidence_records"))
        assertEquals(
            100L,
            database.longValue(
                "SELECT byteSize FROM evidence_records WHERE id = 'existing-evidence'"
            )
        )
        assertEquals(1L, database.count("expense_evidence_links"))
    }

    private fun assertInitialLedger(database: SupportSQLiteDatabase) {
        assertEquals(2L, database.count("prepaid_accounts"))
        assertEquals(
            PrepaidAccountType.Majica,
            database.stringValue(
                "SELECT type FROM prepaid_accounts WHERE id = '${PrepaidAccountId.Majica}'"
            )
        )
        assertEquals(
            PrepaidAccountType.AuPayPrepaid,
            database.stringValue(
                "SELECT type FROM prepaid_accounts WHERE id = '${PrepaidAccountId.AuPayPrepaid}'"
            )
        )
        assertEquals(2L, database.longValue("SELECT COUNT(DISTINCT type) FROM prepaid_accounts"))
        assertEquals(0L, database.count("prepaid_transactions"))
        assertEquals(0L, database.count("expense_prepaid_links"))
        assertEquals(
            0L,
            database.longValue(
                """
                SELECT COALESCE(SUM(transaction_record.balanceDelta), 0)
                FROM prepaid_accounts AS account
                LEFT JOIN prepaid_transactions AS transaction_record
                    ON transaction_record.accountId = account.id
                WHERE account.id = '${PrepaidAccountId.Majica}'
                """.trimIndent()
            )
        )
    }

    private fun assertIndexesAndForeignKeys(database: SupportSQLiteDatabase) {
        val transactionIndexes = database.indexNames("prepaid_transactions")
        assertTrue(
            "account type index missing",
            "index_prepaid_accounts_type" in database.indexNames("prepaid_accounts")
        )
        assertTrue("operationKey index missing", "index_prepaid_transactions_operationKey" in transactionIndexes)
        assertTrue(
            "account/date index missing",
            "index_prepaid_transactions_accountId_transactionDate" in transactionIndexes
        )
        assertTrue("expense index missing", "index_prepaid_transactions_expenseId" in transactionIndexes)
        assertTrue(
            "reversal index missing",
            "index_prepaid_transactions_reversalOfTransactionId" in transactionIndexes
        )
        assertTrue(
            "purchase link index missing",
            "index_expense_prepaid_links_purchaseTransactionId" in
                database.indexNames("expense_prepaid_links")
        )

        val transactionForeignKeys = database.foreignKeyTargets("prepaid_transactions")
        assertTrue("account FK missing", "prepaid_accounts" in transactionForeignKeys)
        assertTrue("expense FK missing", "expense_records" in transactionForeignKeys)
        assertTrue("reversal FK missing", "prepaid_transactions" in transactionForeignKeys)
        val linkForeignKeys = database.foreignKeyTargets("expense_prepaid_links")
        assertTrue("link expense FK missing", "expense_records" in linkForeignKeys)
        assertTrue("link transaction FK missing", "prepaid_transactions" in linkForeignKeys)
    }

    private fun assertUniqueConstraints(database: SupportSQLiteDatabase) {
        assertSqlFails {
            database.execSQL(
                """
                INSERT INTO prepaid_accounts (
                    id, type, name, isActive, createdAt, updatedAt
                ) VALUES ('duplicate-type', 'MAJICA', 'duplicate', 1, 0, 0)
                """.trimIndent()
            )
        }
        insertTransaction(
            database = database,
            id = "charge-1",
            operationKey = "operation-charge-1",
            delta = 10_000L
        )
        insertTransaction(
            database = database,
            id = "charge-2",
            operationKey = "operation-charge-2",
            delta = 100L
        )
        assertEquals(
            2L,
            database.longValue(
                "SELECT COUNT(*) FROM prepaid_transactions WHERE reversalOfTransactionId IS NULL"
            )
        )

        assertSqlFails {
            insertTransaction(
                database = database,
                id = "duplicate-operation",
                operationKey = "operation-charge-1",
                delta = 1L
            )
        }

        insertTransaction(
            database = database,
            id = "reversal-1",
            operationKey = "operation-reversal-1",
            delta = -10_000L,
            transactionType = "REVERSAL",
            reversalOf = "charge-1"
        )
        assertSqlFails {
            insertTransaction(
                database = database,
                id = "reversal-2",
                operationKey = "operation-reversal-2",
                delta = -10_000L,
                transactionType = "REVERSAL",
                reversalOf = "charge-1"
            )
        }

        insertExpense(database, "second-expense")
        insertExpense(database, "third-expense")
        insertTransaction(
            database = database,
            id = "purchase-1",
            operationKey = "operation-purchase-1",
            delta = -100L,
            transactionType = "PURCHASE",
            expenseId = "second-expense"
        )
        database.execSQL(
            """
            INSERT INTO expense_prepaid_links (
                expenseId, purchaseTransactionId, linkedAt, updatedAt
            ) VALUES ('second-expense', 'purchase-1', 3, 3)
            """.trimIndent()
        )
        assertSqlFails {
            database.execSQL(
                """
                INSERT INTO expense_prepaid_links (
                    expenseId, purchaseTransactionId, linkedAt, updatedAt
                ) VALUES ('third-expense', 'purchase-1', 4, 4)
                """.trimIndent()
            )
        }
    }

    private fun insertTransaction(
        database: SupportSQLiteDatabase,
        id: String,
        operationKey: String,
        delta: Long,
        transactionType: String = "CHARGE",
        reversalOf: String? = null,
        expenseId: String? = null
    ) {
        database.execSQL(
            """
            INSERT INTO prepaid_transactions (
                id, accountId, transactionDate, transactionType, balanceDelta,
                expenseId, chargeSource, reversalOfTransactionId, operationKey, memo, createdAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                PrepaidAccountId.Majica,
                "2026-07-26",
                transactionType,
                delta,
                expenseId,
                if (transactionType == "CHARGE") "CASH" else null,
                reversalOf,
                operationKey,
                3L
            )
        )
    }

    private fun insertExpense(database: SupportSQLiteDatabase, id: String) {
        database.execSQL(
            """
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod,
                memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (?, '2026-07-26', 'other_expense', NULL, 100, '現金',
                NULL, NULL, 'manual', 3, 3)
            """.trimIndent(),
            arrayOf(id)
        )
    }

    private fun assertSqlFails(block: () -> Unit) {
        assertTrue("Expected SQLite constraint failure", runCatching(block).isFailure)
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
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }

    private fun SupportSQLiteDatabase.foreignKeyTargets(table: String): Set<String> =
        query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildSet {
                val tableIndex = cursor.getColumnIndex("table")
                while (cursor.moveToNext()) {
                    add(cursor.getString(tableIndex))
                }
            }
        }
}
