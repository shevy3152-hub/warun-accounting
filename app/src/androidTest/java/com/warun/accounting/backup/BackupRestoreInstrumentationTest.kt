package com.warun.accounting.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.ElectronicSubmissionStatus
import com.warun.accounting.data.local.ExpenseCancellationRecord
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.local.WarunDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private lateinit var paths: BackupPaths
    private lateinit var database: WarunDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val token = UUID.randomUUID().toString()
        databaseName = "backup-restore-$token.db"
        root = File(context.cacheDir, "backup-restore-$token")
        paths = BackupPaths(
            databaseFile = context.getDatabasePath(databaseName),
            liveEvidenceDirectory = File(root, "accounting-evidence/stored"),
            workRoot = File(root, "accounting-backup")
        )
        database = openDatabase()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(databaseName)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun backupAndRestorePreserveReportsExpensesEvidenceCancellationAndLedger() = runBlocking {
        insertCompleteFixture(database)
        val wal = File(paths.databaseFile.absolutePath + "-wal")
        assertTrue("WAL must exist for this snapshot test", wal.isFile && wal.length() > 0L)
        val manager = manager()
        val archive = ByteArrayOutputStream().also(manager::createBackup).toByteArray()

        database.warunDao().insertDailyReport(report("report-after-backup", "2026-08-09"))
        database.prepaidTransactionDao().insert(
            prepaidTransaction(
                id = "charge-after-backup",
                type = PrepaidTransactionType.Charge,
                delta = 700L,
                operationKey = "charge-after-backup",
                expenseId = null
            )
        )
        assertEquals(2_200L, database.prepaidTransactionDao().getBalance("backup-account"))

        val preview = manager.stageRestore(ByteArrayInputStream(archive))
        assertEquals(1L, preview.summary.dailyReportCount)
        assertEquals(2L, preview.summary.expenseCount)
        assertEquals(2L, preview.summary.evidenceCount)
        assertEquals(1L, preview.summary.cancellationCount)
        assertEquals(2L, preview.summary.prepaidTransactionCount)
        assertEquals(RestoreExecutionOutcome.Applied, manager.restore(preview.token).outcome)

        RestoreStartupRecovery(paths).recoverIfNeeded()
        database = openDatabase()
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("expense_records"))
        assertEquals(2L, count("evidence_records"))
        assertEquals(2L, count("expense_evidence_links"))
        assertEquals(1L, count("expense_cancellations"))
        assertEquals(2L, count("prepaid_transactions"))
        assertEquals(1L, count("electronic_submission_records"))
        assertEquals(
            "2026年8月_日報.xlsx",
            valueString(
                "SELECT dailyReportFileName FROM electronic_submission_records " +
                    "WHERE id = 'electronic-submission-1'"
            )
        )
        assertEquals(1_500L, database.prepaidTransactionDao().getBalance("backup-account"))
        assertNotNull(database.expenseCancellationDao().getByExpenseId("cancelled-expense"))
        assertEquals(
            "evidence-1",
            database.warunDao().getEvidenceLinksForExpense("cancelled-expense").single().evidenceId
        )
        val evidence = File(paths.liveEvidenceDirectory, "evidence_evidence-1.jpg")
        val secondEvidence = File(paths.liveEvidenceDirectory, "evidence_evidence-2.jpg")
        assertTrue(evidence.isFile)
        assertTrue(secondEvidence.isFile)
        assertEquals(
            BackupArchive.sha256(evidence),
            evidenceRecord("evidence-1", TestJpeg).sha256
        )
        assertEquals(
            BackupArchive.sha256(secondEvidence),
            evidenceRecord("evidence-2", TestJpeg2).sha256
        )
        assertEquals(
            listOf("prepaid-expense"),
            database.warunDao().observeExpenseRecords().first().map(ExpenseRecord::id)
        )
        assertEquals(0L, value("SELECT COUNT(*) FROM daily_reports WHERE id = 'report-after-backup'"))
    }

    @Test
    fun backupIncludesFixedCostEvidenceAndRejectsConflictingDuplicateSource() = runBlocking {
        insertCompleteFixture(database)
        val fixedBytes = "%PDF-1.7\nfixed-cost\n%%EOF".toByteArray()
        val fixedId = "fixed-evidence-1"
        val fixedFile = File(paths.fixedCostEvidenceDirectory, "evidence_$fixedId.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(fixedBytes)
        }
        database.warunDao().insertEvidenceRecord(
            EvidenceRecord(
                id = fixedId,
                captureId = "capture-$fixedId",
                storedUri = fixedFile.toURI().toString(),
                byteSize = fixedBytes.size.toLong(),
                sha256 = sha256(fixedBytes),
                state = EvidenceRecordState.Stored,
                createdAt = 2L,
                storedAt = 3L,
                updatedAt = 3L,
                mediaType = "application/pdf"
            )
        )

        val archive = ByteArrayOutputStream().also(manager()::createBackup).toByteArray()
        val extracted = BackupArchive.extractAndValidate(ByteArrayInputStream(archive), File(root, "fixed-candidate"))
        assertEquals(3, extracted.manifest.evidence.size)
        assertEquals(fixedBytes.toList(), extracted.evidenceFiles.getValue(fixedId).readBytes().toList())

        val duplicate = File(paths.liveEvidenceDirectory, "evidence_$fixedId.pdf")
            .apply { parentFile?.mkdirs(); writeBytes("%PDF-1.7\nconflict\n%%EOF".toByteArray()) }
        val error = org.junit.Assert.assertThrows(BackupException::class.java) {
            manager().createBackup(ByteArrayOutputStream())
        }
        assertEquals(BackupFailure.EvidenceMismatch, error.failure)
        assertTrue(duplicate.isFile)
    }

    @Test
    fun missingFormalEvidenceRejectsBackupWithoutChangingDatabase() = runBlocking {
        insertCompleteFixture(database)
        val before = count("expense_records")
        assertTrue(File(paths.liveEvidenceDirectory, "evidence_evidence-1.jpg").delete())

        val error = runCatching {
            manager().createBackup(ByteArrayOutputStream())
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.EvidenceMissing, error?.failure)
        assertEquals(before, count("expense_records"))
        assertEquals(1L, count("expense_cancellations"))
        assertEquals(2L, count("prepaid_transactions"))
    }

    @Test
    fun outputFailureDoesNotChangeProductionData() = runBlocking {
        insertCompleteFixture(database)
        val manager = manager()
        val error = runCatching {
            manager.createBackup(object : OutputStream() {
                override fun write(value: Int) = throw IOException("forced output failure")
                override fun write(buffer: ByteArray, offset: Int, length: Int) =
                    throw IOException("forced output failure")
            })
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.OutputFailure, error?.failure)
        assertEquals(BackupStage.OutputWrite, error?.stage)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("expense_records"))
        assertEquals(2L, count("evidence_records"))
        assertEquals(2L, count("prepaid_transactions"))
    }

    @Test
    fun flushFailureIsClassifiedWithoutChangingProductionData() = runBlocking {
        insertCompleteFixture(database)
        val error = runCatching {
            manager().createBackup(object : ByteArrayOutputStream() {
                override fun flush() = throw IOException("forced flush failure")
            })
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.OutputFailure, error?.failure)
        assertEquals(BackupStage.OutputFlush, error?.stage)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("evidence_records"))
    }

    @Test
    fun closeFailureIsClassifiedWithoutChangingProductionData() = runBlocking {
        insertCompleteFixture(database)
        val error = runCatching {
            manager().createBackup(object : ByteArrayOutputStream() {
                override fun close() = throw IOException("forced close failure")
            })
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.OutputFailure, error?.failure)
        assertEquals(BackupStage.OutputClose, error?.stage)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("evidence_records"))
    }

    @Test
    fun safProviderUsingWtModeCreatesAndVerifiesCompleteArchive() = runBlocking {
        insertCompleteFixture(database)
        var requestedMode: String? = null
        val output = ByteArrayOutputStream()
        val result = manager(
            outputStreamFactory = { _, mode ->
                requestedMode = mode
                output
            },
            inputStreamFactory = { ByteArrayInputStream(output.toByteArray()) }
        ).createBackup(Uri.parse("content://test/backup"))

        assertEquals("wt", requestedMode)
        assertTrue(output.size() > 0)
        assertEquals(1L, result.summary.dailyReportCount)
        assertEquals(2L, result.summary.evidenceCount)
    }

    @Test
    fun safOutputOpenFailureIsClassifiedAndDoesNotChangeProductionData() = runBlocking {
        insertCompleteFixture(database)
        val error = runCatching {
            manager(
                outputStreamFactory = { _, _ -> throw SecurityException("permission denied") }
            ).createBackup(Uri.parse("content://test/backup"))
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.OutputFailure, error?.failure)
        assertEquals(BackupStage.OutputOpen, error?.stage)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("evidence_records"))
    }

    @Test
    fun safOutputVerificationFailureIsNotReportedAsSuccess() = runBlocking {
        insertCompleteFixture(database)
        val output = ByteArrayOutputStream()
        val error = runCatching {
            manager(
                outputStreamFactory = { _, _ -> output },
                inputStreamFactory = { ByteArrayInputStream("not a zip".toByteArray()) }
            ).createBackup(Uri.parse("content://test/backup"))
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.OutputFailure, error?.failure)
        assertEquals(BackupStage.OutputVerify, error?.stage)
        assertTrue(output.size() > 0)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("evidence_records"))
    }

    @Test
    fun corruptSqliteIsRejectedDuringStagingWithoutChangingProduction() = runBlocking {
        insertCompleteFixture(database)
        val fakeDatabase = File(root, "not-sqlite.db").apply {
            writeText("not a sqlite database")
        }
        val manifest = BackupManifest(
            formatVersion = BackupContract.FormatVersion,
            createdAtEpochMillis = 1L,
            appVersion = "test",
            roomSchemaVersion = BackupContract.CurrentRoomSchemaVersion,
            database = BackupArchiveEntry(
                BackupContract.DatabaseEntry,
                fakeDatabase.length(),
                BackupArchive.sha256(fakeDatabase)
            ),
            evidence = emptyList(),
            summary = BackupSummary(0, 0, 0, 0, 0, 0, 0)
        )
        val archive = ByteArrayOutputStream().also { output ->
            BackupArchive.write(output, manifest, fakeDatabase, emptyMap())
        }.toByteArray()

        val error = runCatching {
            manager().stageRestore(ByteArrayInputStream(archive))
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.CorruptDatabase, error?.failure)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("expense_records"))
        assertEquals(2L, count("evidence_records"))
        assertEquals(2L, count("prepaid_transactions"))
    }

    @Test
    fun wrongRoomIdentityHashIsRejectedWithoutChangingProduction() = runBlocking {
        insertCompleteFixture(database)
        val sourceDirectory = File(
            paths.restoreRoot,
            "candidate-wrong-schema-${UUID.randomUUID()}"
        )
        val original = BackupBundleBuilder(
            paths,
            DatabaseSnapshotter(database, paths.databaseFile),
            BackupDatabaseInspector(),
            appVersion = "test",
            now = { 40L }
        ).build(sourceDirectory)
        SQLiteDatabase.openDatabase(
            original.databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { stagedDatabase ->
            stagedDatabase.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf("00000000000000000000000000000000")
            )
        }
        val alteredManifest = original.manifest.copy(
            database = original.manifest.database.copy(
                size = original.databaseFile.length(),
                sha256 = BackupArchive.sha256(original.databaseFile)
            )
        )
        val archive = ByteArrayOutputStream().also { output ->
            BackupArchive.write(
                output,
                alteredManifest,
                original.databaseFile,
                original.evidenceFiles
            )
        }.toByteArray()

        val error = runCatching {
            manager().stageRestore(ByteArrayInputStream(archive))
        }.exceptionOrNull() as? BackupException

        assertEquals(BackupFailure.UnsupportedSchema, error?.failure)
        assertEquals(1L, count("daily_reports"))
        assertEquals(2L, count("expense_records"))
        assertEquals(2L, count("evidence_records"))
        assertEquals(2L, count("prepaid_transactions"))
    }

    @Test
    fun forcedInstallFailureRollsBackToPreRestoreState() = runBlocking {
        insertCompleteFixture(database)
        val goodManager = manager()
        val archive = ByteArrayOutputStream().also(goodManager::createBackup).toByteArray()
        database.warunDao().insertDailyReport(report("current-only", "2026-08-10"))

        val failingManager = manager(
            RestoreInstallInterceptor { throw IOException("forced after Evidence switch") }
        )
        val preview = failingManager.stageRestore(ByteArrayInputStream(archive))
        val result = failingManager.restore(preview.token)

        assertEquals(RestoreExecutionOutcome.RolledBackAfterFailure, result.outcome)
        database = openDatabase()
        assertEquals(2L, count("daily_reports"))
        assertEquals(1L, value("SELECT COUNT(*) FROM daily_reports WHERE id = 'current-only'"))
        assertEquals(1L, count("expense_cancellations"))
        assertEquals(1_500L, database.prepaidTransactionDao().getBalance("backup-account"))
    }

    @Test
    fun preparedJournalAfterPartialApplyRestoresRollbackOnNextStartup() = runBlocking {
        insertCompleteFixture(database)
        val manager = manager()
        val archive = ByteArrayOutputStream().also(manager::createBackup).toByteArray()
        database.warunDao().insertDailyReport(report("must-survive-crash", "2026-08-11"))
        val preview = manager.stageRestore(ByteArrayInputStream(archive))

        val inspector = BackupDatabaseInspector()
        val candidateDirectory = File(paths.restoreRoot, "candidate-${preview.token}")
        val candidate = BackupBundleLoader(paths, inspector).load(candidateDirectory)
        val rollbackDirectory = File(paths.restoreRoot, "rollback-crash-test")
        val rollback = BackupBundleBuilder(
            paths,
            DatabaseSnapshotter(database, paths.databaseFile),
            inspector,
            appVersion = "test",
            now = { 50L }
        ).build(rollbackDirectory)
        RestoreJournalStore(paths).write(
            RestoreJournal(
                RestoreJournalPhase.Prepared,
                candidateDirectory.name,
                rollbackDirectory.name
            )
        )
        database.close()
        BackupLiveInstaller(paths, inspector).install(candidate)

        RestoreStartupRecovery(paths, inspector).recoverIfNeeded()
        database = openDatabase()
        assertEquals(2L, count("daily_reports"))
        assertEquals(
            1L,
            value("SELECT COUNT(*) FROM daily_reports WHERE id = 'must-survive-crash'")
        )
        assertEquals(rollback.manifest.summary, BackupDatabaseInspector().inspect(paths.databaseFile).summary)
    }

    @Test
    fun stagedRestoreRebasesFormalEvidenceUrisForTheCurrentEnvironment() = runBlocking {
        insertCompleteFixture(database)
        val inspector = BackupDatabaseInspector()
        val candidateDirectory = File(
            paths.restoreRoot,
            "candidate-old-environment-${UUID.randomUUID()}"
        )
        val original = BackupBundleBuilder(
            paths,
            DatabaseSnapshotter(database, paths.databaseFile),
            inspector,
            appVersion = "test",
            now = { 60L }
        ).build(candidateDirectory)
        val oldEvidence = original.manifest.evidence.map { item ->
            item.copy(storedUri = "file:/data/user/99/old-app/evidence_${item.evidenceId}.jpg")
        }
        SQLiteDatabase.openDatabase(
            original.databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { stagedDatabase ->
            oldEvidence.forEach { item ->
                stagedDatabase.execSQL(
                    "UPDATE evidence_records SET storedUri = ? WHERE id = ?",
                    arrayOf(item.storedUri, item.evidenceId)
                )
            }
        }
        val oldManifest = original.manifest.copy(
            database = original.manifest.database.copy(
                size = original.databaseFile.length(),
                sha256 = BackupArchive.sha256(original.databaseFile)
            ),
            evidence = oldEvidence
        )
        FileOutputStream(File(candidateDirectory, BackupContract.ManifestEntry)).use { output ->
            BackupManifestXml.write(oldManifest, output)
            output.fd.sync()
        }
        val oldBundle = original.copy(manifest = oldManifest)
        inspector.validateBundle(oldBundle)

        val rebased = StagedEvidenceUriRebaser(paths, inspector).rebase(oldBundle)

        rebased.manifest.evidence.forEach { item ->
            assertEquals(
                File(
                    paths.liveEvidenceDirectory,
                    "evidence_${item.evidenceId}.jpg"
                ).toURI().toString(),
                item.storedUri
            )
        }
        assertEquals(
            rebased.manifest.evidence.map { it.storedUri },
            inspector.inspect(rebased.databaseFile).evidence.map { it.storedUri }
        )
    }

    private fun manager(
        interceptor: RestoreInstallInterceptor = RestoreInstallInterceptor.None,
        outputStreamFactory: ((Uri, String) -> OutputStream?)? = null,
        inputStreamFactory: ((Uri) -> InputStream?)? = null
    ) = BackupRestoreManager(
        context = context,
        database = database,
            paths = paths,
            inspector = BackupDatabaseInspector(),
            restoreInterceptor = interceptor,
            stagedUpgradeInterceptor = StagedDatabaseUpgradeInterceptor.None,
            outputStreamFactory = outputStreamFactory ?: { uri, mode ->
                context.contentResolver.openOutputStream(uri, mode)
            },
            inputStreamFactory = inputStreamFactory ?: { uri ->
                context.contentResolver.openInputStream(uri)
            }
        )

    private fun openDatabase(): WarunDatabase = Room.databaseBuilder(
        context,
        WarunDatabase::class.java,
        databaseName
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()

    private suspend fun insertCompleteFixture(target: WarunDatabase) {
        target.warunDao().insertDailyReport(report("report-1", "2026-08-08"))
        target.warunDao().insertElectronicSubmissionRecord(
            ElectronicSubmissionRecord(
                id = "electronic-submission-1",
                targetMonth = "2026-08",
                generatedAt = 20L,
                dailyReportFileName = "2026年8月_日報.xlsx",
                expenseDetailFileName = "2026年8月_支出明細.xlsx",
                receiptPdfFileName = "2026年8月_レシート.pdf",
                status = ElectronicSubmissionStatus.NotSubmitted,
                submittedAt = null,
                note = null,
                createdAt = 20L,
                updatedAt = 20L
            )
        )
        val cancelledExpense = expense("cancelled-expense", "現金", 600L)
        val prepaidExpense = expense("prepaid-expense", "プリペイド", 500L)
        target.warunDao().insertExpenseRecord(cancelledExpense)
        target.warunDao().insertExpenseRecord(prepaidExpense)
        addEvidence(target, cancelledExpense.id, "evidence-1", TestJpeg)
        addEvidence(target, prepaidExpense.id, "evidence-2", TestJpeg2)
        target.expenseCancellationDao().insert(
            ExpenseCancellationRecord(
                expenseId = cancelledExpense.id,
                operationKey = "expense-cancel:123e4567-e89b-42d3-a456-426614174700",
                requestFingerprint = "a".repeat(64),
                originalPurchaseTransactionId = null,
                reversalTransactionId = null,
                cancellationDate = "2026-08-08",
                cancelledAt = 10L,
                reason = "backup test"
            )
        )
        target.prepaidAccountDao().insert(
            PrepaidAccountRecord(
                id = "backup-account",
                type = PrepaidAccountType.userDefined("backup-account"),
                name = "Backup account",
                isActive = true,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        target.prepaidTransactionDao().insert(
            prepaidTransaction(
                id = "charge-1",
                type = PrepaidTransactionType.Charge,
                delta = 2_000L,
                operationKey = "charge-1",
                expenseId = null
            )
        )
        target.prepaidTransactionDao().insert(
            prepaidTransaction(
                id = "purchase-1",
                type = PrepaidTransactionType.Purchase,
                delta = -500L,
                operationKey = "purchase-1",
                expenseId = prepaidExpense.id
            )
        )
        target.expensePrepaidLinkDao().insert(
            ExpensePrepaidLinkRecord(prepaidExpense.id, "purchase-1", 2L, 2L)
        )
    }

    private fun report(id: String, date: String) = DailyReport(
        id = id,
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = "tester",
        cashSales = 3_000L,
        cardSales = 0L,
        qrSales = 0L,
        accountsReceivableSales = 0L,
        otherSales = 0L,
        foodPurchases = 0L,
        alcoholPurchases = 0L,
        consumablesExpense = 0L,
        utilitiesExpense = 0L,
        electricityExpense = 0L,
        gasExpense = 0L,
        waterExpense = 0L,
        communicationExpense = 0L,
        rentExpense = 0L,
        accountantFeeExpense = 0L,
        miscellaneousExpense = 0L,
        otherExpense = 0L,
        openingCash = 10_000L,
        actualClosingCash = 12_400L,
        customerCount = 3,
        groupCount = 2,
        memo = "backup fixture",
        createdAt = 1L,
        updatedAt = 1L,
        hasActualClosingCash = true
    )

    private fun expense(id: String, paymentMethod: String, amount: Long) = ExpenseRecord(
        id = id,
        expenseDate = "2026-08-08",
        category = ExpenseCategory.FoodPurchase,
        supplierName = "backup supplier",
        amount = amount,
        paymentMethod = paymentMethod,
        memo = "backup fixture",
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 2L,
        updatedAt = 2L
    )

    private suspend fun addEvidence(
        target: WarunDatabase,
        expenseId: String,
        evidenceId: String,
        bytes: ByteArray
    ) {
        val evidence = evidenceRecord(evidenceId, bytes)
        val evidenceFile = File(paths.liveEvidenceDirectory, "evidence_$evidenceId.jpg")
        evidenceFile.parentFile?.mkdirs()
        evidenceFile.writeBytes(bytes)
        evidenceFile.setLastModified(3L)
        assertEquals(evidence.sha256, BackupArchive.sha256(evidenceFile))
        target.warunDao().addEvidenceToExpense(
            expenseId,
            evidence,
            ExpenseEvidenceLinkRecord(expenseId, evidence.id, 3L)
        )
    }

    private fun evidenceRecord(evidenceId: String, bytes: ByteArray) = EvidenceRecord(
        id = evidenceId,
        captureId = "capture-$evidenceId",
        storedUri = File(paths.liveEvidenceDirectory, "evidence_$evidenceId.jpg").toURI().toString(),
        byteSize = bytes.size.toLong(),
        sha256 = sha256(bytes),
        state = EvidenceRecordState.Stored,
        createdAt = 2L,
        storedAt = 3L,
        updatedAt = 3L
    )

    private fun prepaidTransaction(
        id: String,
        type: String,
        delta: Long,
        operationKey: String,
        expenseId: String?
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = "backup-account",
        transactionDate = "2026-08-08",
        transactionType = type,
        balanceDelta = delta,
        expenseId = expenseId,
        chargeSource = if (type == PrepaidTransactionType.Charge) PrepaidChargeSource.Cash else null,
        reversalOfTransactionId = null,
        operationKey = operationKey,
        memo = "backup fixture",
        createdAt = 2L
    )

    private fun count(table: String): Long = value("SELECT COUNT(*) FROM `$table`")

    private fun value(sql: String): Long = database.openHelper.readableDatabase.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

    private fun valueString(sql: String): String =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    companion object {
        private val TestJpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x01, 0x02, 0x03, 0xff.toByte(), 0xd9.toByte()
        )
        private val TestJpeg2 = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x04, 0x05, 0x06, 0xff.toByte(), 0xd9.toByte()
        )
        private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
