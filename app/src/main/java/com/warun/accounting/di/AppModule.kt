package com.warun.accounting.di

import android.content.Context

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.OfflineAccountingRepository
import com.warun.accounting.data.prepaid.OfflinePrepaidRepository
import com.warun.accounting.data.prepaid.PrepaidRepository
import com.warun.accounting.camera.ReceiptImageImportGateway
import com.warun.accounting.camera.ReceiptPendingImageImporter
import com.warun.accounting.data.local.ExpensePrepaidLinkDao
import com.warun.accounting.data.local.InitialPrepaidAccounts
import com.warun.accounting.data.local.PrepaidAccountDao
import com.warun.accounting.data.local.PrepaidTransactionDao
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.evidence.EvidenceFileStore
import com.warun.accounting.evidence.EvidenceFilePromoter
import com.warun.accounting.evidence.EvidenceFinalizationJournal
import com.warun.accounting.evidence.EvidenceRecoveryNoticeController
import com.warun.accounting.evidence.SharedPreferencesEvidenceRecoveryAcknowledgementStore
import com.warun.accounting.future.ReceiptOcrGateway
import com.warun.accounting.ocr.MlKitReceiptOcrGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DatabaseName = "warun-accounting.db"

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("daily_reports", "electricityExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "gasExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "waterExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "communicationExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "rentExpense", "INTEGER NOT NULL DEFAULT 0")
            db.createExpenseRecordsTableIfMissing()
            db.createExpenseRecordIndexes()
            db.migrateLegacyExpenseAmounts()
            db.execSQL("UPDATE daily_reports SET foodPurchases = 0, alcoholPurchases = 0, otherExpense = 0")
        }
    }

    internal val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("daily_reports", "electricityExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "gasExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "waterExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "communicationExpense", "INTEGER NOT NULL DEFAULT 0")
            db.addColumnIfMissing("daily_reports", "rentExpense", "INTEGER NOT NULL DEFAULT 0")
            db.createExpenseRecordsTableIfMissing()
            db.createExpenseRecordIndexes()
            db.migrateLegacyExpenseAmounts()
            db.execSQL("UPDATE daily_reports SET foodPurchases = 0, alcoholPurchases = 0, otherExpense = 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS supplier_candidates (
                    id TEXT NOT NULL,
                    category TEXT NOT NULL,
                    name TEXT NOT NULL,
                    paymentMethod TEXT,
                    isDefault INTEGER NOT NULL,
                    isHidden INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_supplier_candidates_category_name ON supplier_candidates(category, name)")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_reports ADD COLUMN accountantFeeExpense INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE daily_reports ADD COLUMN hasActualClosingCash INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE daily_reports SET hasActualClosingCash = 1 WHERE actualClosingCash <> 0"
            )
        }
    }

    internal val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS evidence_records (
                    id TEXT NOT NULL,
                    captureId TEXT NOT NULL,
                    storedUri TEXT NOT NULL,
                    byteSize INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    state TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    storedAt INTEGER,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_evidence_records_captureId ON evidence_records(captureId)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_evidence_records_storedUri ON evidence_records(storedUri)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS expense_evidence_links (
                    expenseId TEXT NOT NULL,
                    evidenceId TEXT NOT NULL,
                    linkedAt INTEGER NOT NULL,
                    PRIMARY KEY(expenseId, evidenceId),
                    FOREIGN KEY(expenseId) REFERENCES expense_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(evidenceId) REFERENCES evidence_records(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_expense_evidence_links_evidenceId ON expense_evidence_links(evidenceId)"
            )
        }
    }

    internal val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.createPrepaidTables()
            db.insertInitialPrepaidAccounts()
        }
    }

    internal val PREPAID_DATABASE_CALLBACK = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.insertInitialPrepaidAccounts()
        }
    }

    private fun SupportSQLiteDatabase.addColumnIfMissing(
        tableName: String,
        columnName: String,
        columnDefinition: String
    ) {
        if (!hasColumn(tableName, columnName)) {
            execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnDefinition")
        }
    }

    private fun SupportSQLiteDatabase.createExpenseRecordsTableIfMissing() {
        execSQL(
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
    }

    private fun SupportSQLiteDatabase.createExpenseRecordIndexes() {
        execSQL("CREATE INDEX IF NOT EXISTS index_expense_records_expenseDate ON expense_records(expenseDate)")
        execSQL("CREATE INDEX IF NOT EXISTS index_expense_records_category ON expense_records(category)")
        execSQL("CREATE INDEX IF NOT EXISTS index_expense_records_receiptId ON expense_records(receiptId)")
    }

    private data class LegacyExpenseInput(
        val id: String,
        val expenseDate: String,
        val category: String,
        val amount: Long,
        val memo: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    private data class ExistingExpenseValues(
        val expenseDate: String,
        val category: String,
        val amount: Long,
        val sourceType: String
    )

    private fun SupportSQLiteDatabase.migrateLegacyExpenseAmounts() {
        migrateLegacyExpenseCategory(
            amountColumn = "foodPurchases",
            idPrefix = "legacy-food-",
            category = "food_purchase",
            memo = "legacy DailyReport foodPurchases"
        )
        migrateLegacyExpenseCategory(
            amountColumn = "alcoholPurchases",
            idPrefix = "legacy-alcohol-",
            category = "alcohol_purchase",
            memo = "legacy DailyReport alcoholPurchases"
        )
        migrateLegacyExpenseCategory(
            amountColumn = "otherExpense",
            idPrefix = "legacy-other-",
            category = "other_expense",
            memo = "legacy DailyReport otherExpense"
        )
    }

    private fun SupportSQLiteDatabase.migrateLegacyExpenseCategory(
        amountColumn: String,
        idPrefix: String,
        category: String,
        memo: String
    ) {
        val legacyExpenses = mutableListOf<LegacyExpenseInput>()
        query(
            """
            SELECT id, reportDate, $amountColumn, createdAt, updatedAt
            FROM daily_reports
            WHERE $amountColumn > 0
            """.trimIndent()
        ).use { cursor ->
            val idIndex = cursor.getColumnIndex("id")
            val dateIndex = cursor.getColumnIndex("reportDate")
            val amountIndex = cursor.getColumnIndex(amountColumn)
            val createdAtIndex = cursor.getColumnIndex("createdAt")
            val updatedAtIndex = cursor.getColumnIndex("updatedAt")
            while (cursor.moveToNext()) {
                legacyExpenses += LegacyExpenseInput(
                    id = idPrefix + cursor.getString(idIndex),
                    expenseDate = cursor.getString(dateIndex),
                    category = category,
                    amount = cursor.getLong(amountIndex),
                    memo = memo,
                    createdAt = cursor.getLong(createdAtIndex),
                    updatedAt = cursor.getLong(updatedAtIndex)
                )
            }
        }

        legacyExpenses.forEach { legacyExpense ->
            ensureLegacyExpenseRecord(legacyExpense)
        }
    }

    private fun SupportSQLiteDatabase.ensureLegacyExpenseRecord(
        legacyExpense: LegacyExpenseInput
    ) {
        val existing = query(
            """
            SELECT expenseDate, category, amount, sourceType
            FROM expense_records
            WHERE id = ?
            """.trimIndent(),
            arrayOf(legacyExpense.id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ExistingExpenseValues(
                    expenseDate = cursor.getString(0),
                    category = cursor.getString(1),
                    amount = cursor.getLong(2),
                    sourceType = cursor.getString(3)
                )
            }
        }

        if (existing != null) {
            val matches = existing.expenseDate == legacyExpense.expenseDate &&
                existing.category == legacyExpense.category &&
                existing.amount == legacyExpense.amount &&
                existing.sourceType == "legacy_migration"
            if (!matches) {
                throw IllegalStateException(
                    "Legacy ExpenseRecord conflict: ${legacyExpense.id}"
                )
            }
            return
        }

        execSQL(
            """
            INSERT INTO expense_records (
                id, expenseDate, category, supplierName, amount, paymentMethod, memo, receiptId, sourceType, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                legacyExpense.id,
                legacyExpense.expenseDate,
                legacyExpense.category,
                null,
                legacyExpense.amount,
                null,
                legacyExpense.memo,
                null,
                "legacy_migration",
                legacyExpense.createdAt,
                legacyExpense.updatedAt
            )
        )
    }
    private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
        val cursor = query("PRAGMA table_info($tableName)")
        var found = false
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == columnName) {
                    found = true
                    break
                }
            }
        }
        return found
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WarunDatabase {
        return Room.databaseBuilder(context, WarunDatabase::class.java, DatabaseName)
            .addMigrations(
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12
            )
            .addCallback(PREPAID_DATABASE_CALLBACK)
            .build()
    }

    @Provides
    fun provideDao(database: WarunDatabase): WarunDao = database.warunDao()

    @Provides
    fun providePrepaidAccountDao(database: WarunDatabase): PrepaidAccountDao =
        database.prepaidAccountDao()

    @Provides
    fun providePrepaidTransactionDao(database: WarunDatabase): PrepaidTransactionDao =
        database.prepaidTransactionDao()

    @Provides
    fun provideExpensePrepaidLinkDao(database: WarunDatabase): ExpensePrepaidLinkDao =
        database.expensePrepaidLinkDao()
}

private fun SupportSQLiteDatabase.createPrepaidTables() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS prepaid_accounts (
            id TEXT NOT NULL,
            type TEXT NOT NULL,
            name TEXT NOT NULL,
            isActive INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_prepaid_accounts_type
        ON prepaid_accounts(type)
        """.trimIndent()
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS prepaid_transactions (
            id TEXT NOT NULL,
            accountId TEXT NOT NULL,
            transactionDate TEXT NOT NULL,
            transactionType TEXT NOT NULL,
            balanceDelta INTEGER NOT NULL,
            expenseId TEXT,
            chargeSource TEXT,
            reversalOfTransactionId TEXT,
            operationKey TEXT NOT NULL,
            memo TEXT NOT NULL DEFAULT '',
            createdAt INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(accountId) REFERENCES prepaid_accounts(id)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(expenseId) REFERENCES expense_records(id)
                ON UPDATE NO ACTION ON DELETE SET NULL,
            FOREIGN KEY(reversalOfTransactionId) REFERENCES prepaid_transactions(id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_prepaid_transactions_operationKey
        ON prepaid_transactions(operationKey)
        """.trimIndent()
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_prepaid_transactions_accountId_transactionDate
        ON prepaid_transactions(accountId, transactionDate)
        """.trimIndent()
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_prepaid_transactions_expenseId
        ON prepaid_transactions(expenseId)
        """.trimIndent()
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_prepaid_transactions_reversalOfTransactionId
        ON prepaid_transactions(reversalOfTransactionId)
        """.trimIndent()
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS expense_prepaid_links (
            expenseId TEXT NOT NULL,
            purchaseTransactionId TEXT NOT NULL,
            linkedAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY(expenseId),
            FOREIGN KEY(expenseId) REFERENCES expense_records(id)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(purchaseTransactionId) REFERENCES prepaid_transactions(id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent()
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_expense_prepaid_links_purchaseTransactionId
        ON expense_prepaid_links(purchaseTransactionId)
        """.trimIndent()
    )
}

private fun SupportSQLiteDatabase.insertInitialPrepaidAccounts() {
    InitialPrepaidAccounts.Records.forEach { account ->
        execSQL(
            """
            INSERT OR IGNORE INTO prepaid_accounts (
                id, type, name, isActive, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                account.id,
                account.type,
                account.name,
                if (account.isActive) 1 else 0,
                account.createdAt,
                account.updatedAt
            )
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAccountingRepository(
        repository: OfflineAccountingRepository
    ): AccountingRepository

    @Binds
    @Singleton
    abstract fun bindPrepaidRepository(
        repository: OfflinePrepaidRepository
    ): PrepaidRepository

    @Binds
    @Singleton
    abstract fun bindReceiptOcrGateway(
        gateway: MlKitReceiptOcrGateway
    ): ReceiptOcrGateway

    @Binds
    @Singleton
    abstract fun bindReceiptImageImportGateway(
        importer: ReceiptPendingImageImporter
    ): ReceiptImageImportGateway
}

@Module
@InstallIn(SingletonComponent::class)
object EvidenceStorageModule {
    @Provides
    @Singleton
    fun provideEvidenceFileStore(@ApplicationContext context: Context): EvidenceFileStore =
        EvidenceFileStore(
            pendingDirectory = File(context.filesDir, "receipt-images/pending"),
            storedDirectory = File(context.filesDir, "accounting-evidence/stored")
        )

    @Provides
    @Singleton
    fun provideEvidenceFilePromoter(store: EvidenceFileStore): EvidenceFilePromoter = store

    @Provides
    @Singleton
    fun provideEvidenceFinalizationJournal(
        @ApplicationContext context: Context
    ): EvidenceFinalizationJournal = EvidenceFinalizationJournal(
        journalDirectory = File(context.filesDir, "accounting-evidence/finalization-journal")
    )

    @Provides
    @Singleton
    fun provideEvidenceRecoveryNoticeController(
        @ApplicationContext context: Context
    ): EvidenceRecoveryNoticeController = EvidenceRecoveryNoticeController(
        SharedPreferencesEvidenceRecoveryAcknowledgementStore(
            context.getSharedPreferences("evidence-recovery-notices", Context.MODE_PRIVATE)
        )
    )
}
