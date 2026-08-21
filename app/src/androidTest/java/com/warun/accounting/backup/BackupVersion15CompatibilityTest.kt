package com.warun.accounting.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.di.DatabaseModule
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupVersion15CompatibilityTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun version15BackupDatabaseIsAcceptedAndMigratesToVersion16() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "backup-version-15-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 15).use { database ->
            database.execSQL(
                """
                INSERT INTO monthly_submissions(targetMonth, status, submittedAt, updatedAt)
                VALUES ('2026-05', 'submitted', 10, 11)
                """.trimIndent()
            )
        }
        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.isFile)

        val inspector = BackupDatabaseInspector()
        val inspection = inspector.inspect(databaseFile)
        assertEquals(BackupContract.PreviousRoomSchemaVersion, inspection.schemaVersion)
        val manifest = BackupManifest(
            formatVersion = BackupContract.FormatVersion,
            createdAtEpochMillis = 1L,
            appVersion = "test-v15",
            roomSchemaVersion = BackupContract.PreviousRoomSchemaVersion,
            database = BackupArchiveEntry(
                path = BackupContract.DatabaseEntry,
                size = databaseFile.length(),
                sha256 = BackupArchive.sha256(databaseFile)
            ),
            evidence = emptyList(),
            summary = inspection.summary
        )
        inspector.validateBundle(
            BackupBundle(
                rootDirectory = databaseFile.parentFile ?: File("."),
                manifest = manifest,
                databaseFile = databaseFile,
                evidenceFiles = emptyMap()
            )
        )

        val migrated = Room.databaseBuilder(context, WarunDatabase::class.java, databaseName)
            .addMigrations(DatabaseModule.MIGRATION_15_16)
            .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            assertEquals(16, sqlite.version)
            assertEquals(
                1L,
                sqlite.query("SELECT COUNT(*) FROM monthly_submissions").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            )
            assertEquals(
                0L,
                sqlite.query("SELECT COUNT(*) FROM electronic_submission_records").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun stageRestoreUpgradesV15CandidateBeforeInstallAndPreservesPaperAndRebasedEvidence() =
        runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val sourceName = "backup-version-15-source-$token"
        val liveName = "backup-version-15-live-$token"
        val root = File(context.cacheDir, "backup-version-15-restore-$token")
        val paths = BackupPaths(
            databaseFile = context.getDatabasePath(liveName),
            liveEvidenceDirectory = File(root, "accounting-evidence/stored"),
            workRoot = File(root, "accounting-backup")
        )
        val archive = version15Archive(context, sourceName, root)
        var live = Room.databaseBuilder(context, WarunDatabase::class.java, liveName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            live.openHelper.writableDatabase
            val manager = BackupRestoreManager(
                context,
                live,
                paths,
                BackupDatabaseInspector(),
                RestoreInstallInterceptor.None,
                StagedDatabaseUpgradeInterceptor.None
            )
            val preview = manager.stageRestore(ByteArrayInputStream(archive))

            assertEquals(BackupContract.CurrentRoomSchemaVersion, preview.roomSchemaVersion)
            val candidate = BackupBundleLoader(paths, BackupDatabaseInspector()).load(
                File(paths.restoreRoot, "candidate-${preview.token}")
            )
            assertEquals(BackupContract.CurrentRoomSchemaVersion, candidate.manifest.roomSchemaVersion)
            assertEquals(candidate.databaseFile.length(), candidate.manifest.database.size)
            assertEquals(BackupArchive.sha256(candidate.databaseFile), candidate.manifest.database.sha256)
            assertEquals(16, BackupDatabaseInspector().inspect(candidate.databaseFile).schemaVersion)
            SQLiteDatabase.openDatabase(
                candidate.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { staged ->
                assertEquals(1L, staged.longValue("SELECT COUNT(*) FROM monthly_submissions"))
                assertEquals(0L, staged.longValue("SELECT COUNT(*) FROM electronic_submission_records"))
                assertEquals(
                    File(paths.liveEvidenceDirectory, "evidence_evidence-v15.jpg").toURI().toString(),
                    staged.stringValue(
                        "SELECT storedUri FROM evidence_records WHERE id = 'evidence-v15'"
                    )
                )
            }

            assertEquals(RestoreExecutionOutcome.Applied, manager.restore(preview.token).outcome)
            live = Room.databaseBuilder(context, WarunDatabase::class.java, liveName).build()
            val sqlite = live.openHelper.writableDatabase
            assertEquals(16, sqlite.version)
            assertEquals(
                1L,
                sqlite.query("SELECT COUNT(*) FROM monthly_submissions").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            )
            assertEquals(
                0L,
                sqlite.query("SELECT COUNT(*) FROM electronic_submission_records").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            )
            assertTrue(File(paths.liveEvidenceDirectory, "evidence_evidence-v15.jpg").isFile)
        } finally {
            if (live.isOpen) live.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(liveName)
            root.deleteRecursively()
        }
        }

    @Test
    fun stagedV15UpgradeFailureDeletesCandidateAndDoesNotChangeLiveDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val sourceName = "backup-version-15-failing-source-$token"
        val liveName = "backup-version-15-failing-live-$token"
        val root = File(context.cacheDir, "backup-version-15-failing-$token")
        val paths = BackupPaths(
            databaseFile = context.getDatabasePath(liveName),
            liveEvidenceDirectory = File(root, "accounting-evidence/stored"),
            workRoot = File(root, "accounting-backup")
        )
        val archive = version15Archive(context, sourceName, root)
        val live = Room.databaseBuilder(context, WarunDatabase::class.java, liveName).build()
        try {
            live.openHelper.writableDatabase.execSQL(
                "INSERT INTO monthly_submissions(targetMonth, status, submittedAt, updatedAt) " +
                    "VALUES ('2099-01', 'submitted', 1, 1)"
            )
            val manager = BackupRestoreManager(
                context,
                live,
                paths,
                BackupDatabaseInspector(),
                RestoreInstallInterceptor.None,
                StagedDatabaseUpgradeInterceptor {
                    throw IOException("forced staged upgrade failure")
                }
            )

            val error = runCatching {
                manager.stageRestore(ByteArrayInputStream(archive))
            }.exceptionOrNull() as? BackupException

            assertEquals(BackupFailure.RestoreFailure, error?.failure)
            assertEquals(
                1L,
                live.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM monthly_submissions WHERE targetMonth = '2099-01'"
                ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
            )
            assertTrue(
                paths.restoreRoot.listFiles().orEmpty().none { it.name.startsWith("candidate-") }
            )
            assertEquals(16, live.openHelper.readableDatabase.version)
        } finally {
            live.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(liveName)
            root.deleteRecursively()
        }
    }

    private fun version15Archive(context: Context, databaseName: String, root: File): ByteArray {
        val oldUri = "file:/data/user/99/old-warun/evidence_evidence-v15.jpg"
        val evidenceBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val evidenceSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(evidenceBytes)
            .joinToString("") { "%02x".format(it) }
        migrationHelper.createDatabase(databaseName, 15).use { database ->
            database.execSQL(
                "INSERT INTO monthly_submissions(targetMonth, status, submittedAt, updatedAt) " +
                    "VALUES ('2026-05', 'submitted', 10, 11)"
            )
            database.execSQL(
                "INSERT INTO evidence_records(" +
                    "id, captureId, storedUri, byteSize, sha256, state, createdAt, storedAt, updatedAt" +
                    ") VALUES (?, ?, ?, ?, ?, 'stored', 1, 2, 2)",
                arrayOf("evidence-v15", "capture-v15", oldUri, evidenceBytes.size, evidenceSha)
            )
        }
        val databaseFile = context.getDatabasePath(databaseName)
        val inspector = BackupDatabaseInspector()
        val inspection = inspector.inspect(databaseFile)
        val evidenceFile = File(root, "archive-evidence-v15.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(evidenceBytes)
        }
        val evidenceEntry = BackupEvidenceEntry(
            evidenceId = "evidence-v15",
            storedUri = oldUri,
            archiveEntry = BackupArchiveEntry(
                BackupContract.evidenceEntry("evidence-v15"),
                evidenceFile.length(),
                evidenceSha
            )
        )
        val manifest = BackupManifest(
            formatVersion = BackupContract.FormatVersion,
            createdAtEpochMillis = 1L,
            appVersion = "test-v15",
            roomSchemaVersion = 15,
            database = BackupArchiveEntry(
                BackupContract.DatabaseEntry,
                databaseFile.length(),
                BackupArchive.sha256(databaseFile)
            ),
            evidence = listOf(evidenceEntry),
            summary = inspection.summary
        )
        return ByteArrayOutputStream().also { output ->
            BackupArchive.write(
                output,
                manifest,
                databaseFile,
                mapOf("evidence-v15" to evidenceFile)
            )
        }.toByteArray()
    }

    private fun SQLiteDatabase.longValue(sql: String): Long = rawQuery(sql, null).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

    private fun SQLiteDatabase.stringValue(sql: String): String = rawQuery(sql, null).use { cursor ->
        cursor.moveToFirst()
        cursor.getString(0)
    }
}
