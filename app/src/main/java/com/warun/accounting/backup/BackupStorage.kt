package com.warun.accounting.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.warun.accounting.BuildConfig
import com.warun.accounting.data.local.Migration15To16Schema
import com.warun.accounting.data.local.Migration16To17Schema
import com.warun.accounting.data.local.WarunDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class BackupPaths(
    val databaseFile: File,
    val liveEvidenceDirectory: File,
    val workRoot: File
) {
    val restoreRoot: File = File(workRoot, "restore")
    val restoreJournalFile: File = File(restoreRoot, "restore-journal.properties")

    constructor(
        context: Context,
        databaseName: String = BuildConfig.DATABASE_NAME
    ) : this(
        databaseFile = context.getDatabasePath(databaseName),
        liveEvidenceDirectory = File(context.filesDir, "accounting-evidence/stored"),
        workRoot = File(context.filesDir, "accounting-backup")
    )

    fun requireSafeRestoreChild(directory: File): File {
        val root = restoreRoot.canonicalFile.toPath()
        val child = directory.canonicalFile.toPath()
        if (child == root || !child.startsWith(root)) {
            backupFail(BackupFailure.RestoreFailure, "Restore path escaped private staging")
        }
        return directory
    }
}

data class DatabaseEvidenceMetadata(
    val evidenceId: String,
    val storedUri: String,
    val byteSize: Long,
    val sha256: String,
    val mediaType: String
)

data class DatabaseInspection(
    val schemaVersion: Int,
    val evidence: List<DatabaseEvidenceMetadata>,
    val summary: BackupSummary
)

class BackupDatabaseInspector {
    fun inspect(databaseFile: File): DatabaseInspection {
        if (!databaseFile.isFile || databaseFile.length() <= 0L) {
            backupFail(BackupFailure.CorruptDatabase, "SQLite snapshot is missing")
        }
        val database = try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (error: Exception) {
            backupFail(BackupFailure.CorruptDatabase, "SQLite snapshot cannot be opened", error)
        }
        return try {
            if (!database.isDatabaseIntegrityOk) {
                backupFail(BackupFailure.CorruptDatabase, "SQLite integrity check failed")
            }
            database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    backupFail(BackupFailure.CorruptDatabase, "SQLite foreign keys are invalid")
                }
            }
            val schema = database.longValue("PRAGMA user_version").toInt()
            val expectedIdentityHash = BackupContract.SupportedRoomIdentityHashes[schema]
            if (expectedIdentityHash == null) {
                backupFail(BackupFailure.UnsupportedSchema, "Backup schema is not supported")
            }
            val identityHash = database.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
                null
            ).use { cursor ->
                cursor.takeIf(Cursor::moveToFirst)?.getString(0)
            }
            if (identityHash != expectedIdentityHash) {
                backupFail(BackupFailure.UnsupportedSchema, "Room schema identity is not supported")
            }
            val evidenceColumns = if (schema >= 17) {
                "id, storedUri, byteSize, sha256, state, storedAt, mediaType"
            } else {
                "id, storedUri, byteSize, sha256, state, storedAt"
            }
            val evidence = database.rawQuery(
                "SELECT $evidenceColumns FROM evidence_records ORDER BY id",
                null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val state = cursor.getString(4)
                        if (state != "stored" || cursor.isNull(5)) {
                            backupFail(
                                BackupFailure.EvidenceMismatch,
                                "Database contains Evidence that is not formally stored"
                            )
                        }
                        val item = DatabaseEvidenceMetadata(
                            evidenceId = cursor.getString(0),
                            storedUri = cursor.getString(1),
                            byteSize = cursor.getLong(2),
                            sha256 = cursor.getString(3),
                            mediaType = if (schema >= 17) cursor.getString(6) else "image/jpeg"
                        )
                        if (!BackupContract.ValidEvidenceId.matches(item.evidenceId) ||
                            item.byteSize <= 0L ||
                            !BackupContract.ValidSha256.matches(item.sha256)
                        ) {
                            backupFail(BackupFailure.EvidenceMismatch, "Invalid Evidence metadata")
                        }
                        add(item)
                    }
                }
            }
            DatabaseInspection(
                schemaVersion = schema,
                evidence = evidence,
                summary = BackupSummary(
                    dailyReportCount = database.count("daily_reports"),
                    receiptCount = database.count("receipts"),
                    expenseCount = database.count("expense_records"),
                    evidenceCount = database.count("evidence_records"),
                    cancellationCount = database.count("expense_cancellations"),
                    prepaidAccountCount = database.count("prepaid_accounts"),
                    prepaidTransactionCount = database.count("prepaid_transactions")
                )
            )
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.CorruptDatabase, "SQLite snapshot validation failed", error)
        } finally {
            database.close()
        }
    }

    fun validateBundle(bundle: BackupBundle) {
        val manifest = bundle.manifest
        manifest.validateContract()
        if (bundle.databaseFile.length() != manifest.database.size ||
            BackupArchive.sha256(bundle.databaseFile) != manifest.database.sha256
        ) {
            backupFail(BackupFailure.ChecksumMismatch, "Database snapshot changed")
        }
        val inspection = inspect(bundle.databaseFile)
        if (inspection.schemaVersion != manifest.roomSchemaVersion ||
            inspection.summary != manifest.summary
        ) {
            backupFail(BackupFailure.CorruptDatabase, "Database summary does not match manifest")
        }
        val dbEvidence = inspection.evidence.associateBy(DatabaseEvidenceMetadata::evidenceId)
        val manifestEvidence = manifest.evidence.associateBy(BackupEvidenceEntry::evidenceId)
        if (dbEvidence.keys != manifestEvidence.keys || bundle.evidenceFiles.keys != dbEvidence.keys) {
            backupFail(BackupFailure.EvidenceMismatch, "Evidence sets do not match")
        }
        dbEvidence.forEach { (id, metadata) ->
            val manifestItem = requireNotNull(manifestEvidence[id])
            val file = requireNotNull(bundle.evidenceFiles[id])
            if (metadata.storedUri != manifestItem.storedUri ||
                metadata.byteSize != manifestItem.archiveEntry.size ||
                metadata.sha256 != manifestItem.archiveEntry.sha256 ||
                !file.isFile || file.length() != metadata.byteSize ||
                BackupArchive.sha256(file) != metadata.sha256
            ) {
                backupFail(BackupFailure.EvidenceMismatch, "Evidence content does not match")
            }
        }
    }

    fun validateLive(paths: BackupPaths, manifest: BackupManifest) {
        val bundle = BackupBundle(
            rootDirectory = paths.workRoot,
            manifest = manifest,
            databaseFile = paths.databaseFile,
            evidenceFiles = manifest.evidence.associate { item ->
                item.evidenceId to File(
                    paths.liveEvidenceDirectory,
                    "evidence_${item.evidenceId}.${item.archiveEntry.path.substringAfterLast('.')}"
                )
            }
        )
        validateBundle(bundle)
        manifest.evidence.forEach { item ->
            val expectedUri = File(
                paths.liveEvidenceDirectory,
                "evidence_${item.evidenceId}.${item.archiveEntry.path.substringAfterLast('.')}"
            ).toURI().toString()
            if (item.storedUri != expectedUri) {
                backupFail(BackupFailure.EvidenceMismatch, "Evidence URI is not restorable here")
            }
        }
    }

    private fun SQLiteDatabase.count(table: String): Long =
        longValue("SELECT COUNT(*) FROM `$table`")

    private fun SQLiteDatabase.longValue(sql: String): Long =
        rawQuery(sql, null).use { cursor ->
            if (!cursor.moveToFirst()) {
                backupFail(BackupFailure.CorruptDatabase, "SQLite query returned no value")
            }
            cursor.getLong(0)
        }
}

fun interface DatabaseSnapshotSource {
    fun createConsolidatedSnapshot(destination: File)
}

class DatabaseSnapshotter(
    private val database: WarunDatabase,
    private val liveDatabaseFile: File
) : DatabaseSnapshotSource {
    override fun createConsolidatedSnapshot(destination: File) {
        prepareSnapshotDestination(destination)
        val supportDatabase = database.openHelper.writableDatabase
        supportDatabase.beginTransaction()
        try {
            copyDatabaseAndWal(liveDatabaseFile, destination)
            supportDatabase.setTransactionSuccessful()
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.SnapshotFailure, "Database snapshot copy failed", error)
        } finally {
            supportDatabase.endTransaction()
        }
        consolidateCopiedDatabase(destination)
    }
}

class ClosedDatabaseSnapshotter(
    private val liveDatabaseFile: File
) : DatabaseSnapshotSource {
    override fun createConsolidatedSnapshot(destination: File) {
        prepareSnapshotDestination(destination)
        try {
            copyDatabaseAndWal(liveDatabaseFile, destination)
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.SnapshotFailure, "Closed database snapshot failed", error)
        }
        consolidateCopiedDatabase(destination)
    }
}

private fun prepareSnapshotDestination(destination: File) {
    destination.parentFile?.let { parent ->
        if (!parent.exists() && !parent.mkdirs()) {
            backupFail(BackupFailure.SnapshotFailure, "Cannot create snapshot directory")
        }
    }
    listOf(destination, File(destination.absolutePath + "-wal"), File(destination.absolutePath + "-shm"))
        .forEach { file ->
            if (file.exists() && !file.delete()) {
                backupFail(BackupFailure.SnapshotFailure, "Old snapshot file could not be removed")
            }
        }
}

private fun copyDatabaseAndWal(source: File, destination: File) {
    copyAndSync(source, destination)
    val liveWal = File(source.absolutePath + "-wal")
    if (liveWal.isFile && liveWal.length() > 0L) {
        copyAndSync(liveWal, File(destination.absolutePath + "-wal"))
    }
}

private fun consolidateCopiedDatabase(snapshot: File) {
    val copy = try {
        SQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    } catch (error: Exception) {
        backupFail(BackupFailure.SnapshotFailure, "Copied database cannot be opened", error)
    }
    try {
        copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            if (cursor.moveToFirst()) {
                val busy = cursor.getInt(0)
                val logFrames = cursor.getInt(1)
                val checkpointedFrames = cursor.getInt(2)
                if (busy != 0 || checkpointedFrames != logFrames) {
                    backupFail(BackupFailure.SnapshotFailure, "WAL checkpoint was incomplete")
                }
            }
        }
        copy.rawQuery("PRAGMA journal_mode=DELETE", null).use(Cursor::moveToFirst)
    } catch (error: BackupException) {
        throw error
    } catch (error: Exception) {
        backupFail(BackupFailure.SnapshotFailure, "Snapshot WAL consolidation failed", error)
    } finally {
        copy.close()
    }
    listOf(File(snapshot.absolutePath + "-wal"), File(snapshot.absolutePath + "-shm"))
        .forEach { file ->
            if (file.exists() && !file.delete()) {
                backupFail(BackupFailure.SnapshotFailure, "Snapshot sidecar could not be removed")
            }
        }
    FileOutputStream(snapshot, true).use { it.fd.sync() }
}

data class BackupBundle(
    val rootDirectory: File,
    val manifest: BackupManifest,
    val databaseFile: File,
    val evidenceFiles: Map<String, File>
)

class BackupBundleBuilder(
    private val paths: BackupPaths,
    private val snapshotter: DatabaseSnapshotSource,
    private val inspector: BackupDatabaseInspector,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun build(destination: File): BackupBundle {
        paths.requireSafeRestoreChild(destination)
        if (destination.exists()) destination.deleteRecursively()
        val databaseFile = File(destination, BackupContract.DatabaseEntry)
        val evidenceDirectory = File(destination, "evidence")
        try {
            snapshotter.createConsolidatedSnapshot(databaseFile)
            val inspection = inspector.inspect(databaseFile)
            if (inspection.schemaVersion != BackupContract.CurrentRoomSchemaVersion) {
                backupFail(
                    BackupFailure.UnsupportedSchema,
                    "New backups require the current Room schema"
                )
            }
            if (!evidenceDirectory.mkdirs() && !evidenceDirectory.isDirectory) {
                backupFail(BackupFailure.SnapshotFailure, "Cannot create Evidence staging")
            }
            val evidenceFiles = LinkedHashMap<String, File>()
            val evidenceManifest = inspection.evidence.map { metadata ->
                val source = File(
                    paths.liveEvidenceDirectory,
                    "evidence_${metadata.evidenceId}.${BackupContract.evidenceEntry(metadata.evidenceId, metadata.mediaType).substringAfterLast('.')}"
                )
                val expectedUri = source.toURI().toString()
                if (!source.isFile) {
                    backupFail(BackupFailure.EvidenceMissing, "Formal Evidence file is missing")
                }
                if (metadata.storedUri != expectedUri ||
                    source.length() != metadata.byteSize ||
                    BackupArchive.sha256(source) != metadata.sha256
                ) {
                    backupFail(BackupFailure.EvidenceMismatch, "Formal Evidence does not match DB")
                }
                val staged = File(evidenceDirectory, BackupContract.evidenceEntry(metadata.evidenceId, metadata.mediaType).substringAfterLast('/'))
                copyAndSync(source, staged)
                evidenceFiles[metadata.evidenceId] = staged
                BackupEvidenceEntry(
                    evidenceId = metadata.evidenceId,
                    storedUri = metadata.storedUri,
                    archiveEntry = BackupArchiveEntry(
                        path = BackupContract.evidenceEntry(metadata.evidenceId, metadata.mediaType),
                        size = metadata.byteSize,
                        sha256 = metadata.sha256
                    ),
                    mediaType = metadata.mediaType
                )
            }
            val manifest = BackupManifest(
                formatVersion = BackupContract.FormatVersion,
                createdAtEpochMillis = now(),
                appVersion = appVersion,
                roomSchemaVersion = inspection.schemaVersion,
                database = BackupArchiveEntry(
                    path = BackupContract.DatabaseEntry,
                    size = databaseFile.length(),
                    sha256 = BackupArchive.sha256(databaseFile)
                ),
                evidence = evidenceManifest,
                summary = inspection.summary
            )
            val manifestFile = File(destination, BackupContract.ManifestEntry)
            FileOutputStream(manifestFile).use { output ->
                BackupManifestXml.write(manifest, output)
                output.fd.sync()
            }
            return BackupBundle(destination, manifest, databaseFile, evidenceFiles).also {
                inspector.validateBundle(it)
            }
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }
}

class BackupBundleLoader(
    private val paths: BackupPaths,
    private val inspector: BackupDatabaseInspector
) {
    fun load(root: File): BackupBundle {
        paths.requireSafeRestoreChild(root)
        val manifestFile = File(root, BackupContract.ManifestEntry)
        if (!manifestFile.isFile || manifestFile.length() > BackupContract.MaxManifestBytes) {
            backupFail(BackupFailure.InvalidManifest, "Staged manifest is unavailable")
        }
        val manifest = FileInputStream(manifestFile).use(BackupManifestXml::read)
        val bundle = BackupBundle(
            rootDirectory = root,
            manifest = manifest,
            databaseFile = File(root, manifest.database.path),
            evidenceFiles = manifest.evidence.associate { item ->
                item.evidenceId to File(root, item.archiveEntry.path)
            }
        )
        inspector.validateBundle(bundle)
        return bundle
    }
}

class StagedEvidenceUriRebaser(
    private val paths: BackupPaths,
    private val inspector: BackupDatabaseInspector
) {
    fun rebase(bundle: BackupBundle): BackupBundle {
        inspector.validateBundle(bundle)
        val replacements = bundle.manifest.evidence.mapNotNull { item ->
            val currentUri = File(
                paths.liveEvidenceDirectory,
                "evidence_${item.evidenceId}.${item.archiveEntry.path.substringAfterLast('.')}"
            ).toURI().toString()
            item.takeIf { it.storedUri != currentUri }?.let { it to currentUri }
        }
        if (replacements.isEmpty()) return bundle

        val database = try {
            SQLiteDatabase.openDatabase(
                bundle.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (error: Exception) {
            backupFail(BackupFailure.CorruptDatabase, "Staged database cannot be rebased", error)
        }
        try {
            database.beginTransaction()
            replacements.forEach { (item, currentUri) ->
                val updated = database.compileStatement(
                    "UPDATE evidence_records SET storedUri = ? " +
                        "WHERE id = ? AND storedUri = ? AND byteSize = ? AND sha256 = ?"
                ).use { statement ->
                    statement.bindString(1, currentUri)
                    statement.bindString(2, item.evidenceId)
                    statement.bindString(3, item.storedUri)
                    statement.bindLong(4, item.archiveEntry.size)
                    statement.bindString(5, item.archiveEntry.sha256)
                    statement.executeUpdateDelete()
                }
                if (updated != 1) {
                    backupFail(BackupFailure.EvidenceMismatch, "Evidence URI rebase was ambiguous")
                }
            }
            database.setTransactionSuccessful()
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.EvidenceMismatch, "Evidence URI rebase failed", error)
        } finally {
            if (database.inTransaction()) database.endTransaction()
            database.close()
        }
        val replacementById = replacements.associate { (item, uri) -> item.evidenceId to uri }
        val updatedManifest = bundle.manifest.copy(
            database = bundle.manifest.database.copy(
                size = bundle.databaseFile.length(),
                sha256 = BackupArchive.sha256(bundle.databaseFile)
            ),
            evidence = bundle.manifest.evidence.map { item ->
                item.copy(storedUri = replacementById[item.evidenceId] ?: item.storedUri)
            }
        )
        FileOutputStream(File(bundle.rootDirectory, BackupContract.ManifestEntry)).use { output ->
            BackupManifestXml.write(updatedManifest, output)
            output.fd.sync()
        }
        return bundle.copy(manifest = updatedManifest).also(inspector::validateBundle)
    }
}

fun interface StagedDatabaseUpgradeInterceptor {
    fun afterSchemaStatements()

    companion object {
        val None = StagedDatabaseUpgradeInterceptor {}
    }
}

/**
 * Upgrades an already extracted and Evidence-rebased v15/v16 candidate before it can become live.
 * Only the isolated candidate database is opened. The manifest is rewritten and the upgraded bundle is
 * fully inspected before stageRestore publishes its token.
 */
class StagedBackupDatabaseUpgrader(
    private val inspector: BackupDatabaseInspector,
    private val interceptor: StagedDatabaseUpgradeInterceptor =
        StagedDatabaseUpgradeInterceptor.None
) {
    fun upgradeToCurrent(bundle: BackupBundle): BackupBundle {
        inspector.validateBundle(bundle)
        if (bundle.manifest.roomSchemaVersion == BackupContract.CurrentRoomSchemaVersion) {
            return bundle
        }
        if (bundle.manifest.roomSchemaVersion !in setOf(
                BackupContract.LegacyRoomSchemaVersion,
                BackupContract.PreviousRoomSchemaVersion
            )
        ) {
            backupFail(BackupFailure.UnsupportedSchema, "Staged database cannot be upgraded")
        }

        val database = try {
            SQLiteDatabase.openDatabase(
                bundle.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (error: Exception) {
            backupFail(BackupFailure.CorruptDatabase, "Staged database cannot be upgraded", error)
        }
        try {
            database.beginTransaction()
            if (bundle.manifest.roomSchemaVersion == BackupContract.LegacyRoomSchemaVersion) {
                Migration15To16Schema.Statements.forEach(database::execSQL)
            }
            Migration16To17Schema.Statements.forEach(database::execSQL)
            interceptor.afterSchemaStatements()
            val updatedIdentity = database.compileStatement(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42"
            ).use { statement ->
                statement.bindString(1, BackupContract.CurrentRoomIdentityHash)
                statement.executeUpdateDelete()
            }
            if (updatedIdentity != 1) {
                backupFail(BackupFailure.UnsupportedSchema, "Room identity metadata is missing")
            }
            database.version = BackupContract.CurrentRoomSchemaVersion
            database.setTransactionSuccessful()
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.RestoreFailure, "Staged database upgrade failed", error)
        } finally {
            if (database.inTransaction()) database.endTransaction()
            database.close()
        }

        val updatedManifest = bundle.manifest.copy(
            roomSchemaVersion = BackupContract.CurrentRoomSchemaVersion,
            database = bundle.manifest.database.copy(
                size = bundle.databaseFile.length(),
                sha256 = BackupArchive.sha256(bundle.databaseFile)
            )
        )
        FileOutputStream(File(bundle.rootDirectory, BackupContract.ManifestEntry)).use { output ->
            BackupManifestXml.write(updatedManifest, output)
            output.fd.sync()
        }
        return bundle.copy(manifest = updatedManifest).also(inspector::validateBundle)
    }
}

fun interface RestoreInstallInterceptor {
    fun afterEvidenceInstalled()

    companion object {
        val None = RestoreInstallInterceptor {}
    }
}

class BackupLiveInstaller(
    private val paths: BackupPaths,
    private val inspector: BackupDatabaseInspector,
    private val interceptor: RestoreInstallInterceptor = RestoreInstallInterceptor.None
) {
    fun install(bundle: BackupBundle) {
        inspector.validateBundle(bundle)
        val token = UUID.randomUUID().toString()
        val evidenceParent = paths.liveEvidenceDirectory.parentFile
            ?: backupFail(BackupFailure.RestoreFailure, "Evidence parent is unavailable")
        if (!evidenceParent.exists() && !evidenceParent.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Evidence parent cannot be created")
        }
        val preparedEvidence = File(evidenceParent, ".restore-$token")
        val oldEvidence = File(evidenceParent, ".restore-old-$token")
        if (!preparedEvidence.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Evidence restore staging failed")
        }
        bundle.manifest.evidence.forEach { item ->
            copyAndSync(
                requireNotNull(bundle.evidenceFiles[item.evidenceId]),
                File(
                    preparedEvidence,
                    "evidence_${item.evidenceId}.${item.archiveEntry.path.substringAfterLast('.')}"
                )
            )
        }
        if (paths.liveEvidenceDirectory.exists()) {
            move(paths.liveEvidenceDirectory, oldEvidence, replace = false)
        }
        move(preparedEvidence, paths.liveEvidenceDirectory, replace = false)
        interceptor.afterEvidenceInstalled()

        val databaseParent = paths.databaseFile.parentFile
            ?: backupFail(BackupFailure.RestoreFailure, "Database parent is unavailable")
        if (!databaseParent.exists() && !databaseParent.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Database parent cannot be created")
        }
        val preparedDatabase = File(databaseParent, ".${paths.databaseFile.name}.$token.tmp")
        copyAndSync(bundle.databaseFile, preparedDatabase)
        removeSidecarOrFail(File(paths.databaseFile.absolutePath + "-wal"))
        removeSidecarOrFail(File(paths.databaseFile.absolutePath + "-shm"))
        removeSidecarOrFail(File(paths.databaseFile.absolutePath + "-journal"))
        move(preparedDatabase, paths.databaseFile, replace = true)
        inspector.validateLive(paths, bundle.manifest)
        oldEvidence.deleteRecursively()
        cleanupTransientFiles(evidenceParent, databaseParent)
    }

    private fun cleanupTransientFiles(evidenceParent: File, databaseParent: File) {
        evidenceParent.listFiles().orEmpty()
            .filter { it.name.startsWith(".restore-") }
            .forEach(File::deleteRecursively)
        databaseParent.listFiles().orEmpty()
            .filter { it.name.startsWith(".${paths.databaseFile.name}.") && it.name.endsWith(".tmp") }
            .forEach(File::delete)
    }

    private fun removeSidecarOrFail(file: File) {
        if (file.exists() && !file.delete()) {
            backupFail(BackupFailure.RestoreFailure, "Old SQLite sidecar could not be removed")
        }
    }
}

enum class RestoreJournalPhase { Prepared, Applied }

data class RestoreJournal(
    val phase: RestoreJournalPhase,
    val candidateDirectoryName: String,
    val rollbackDirectoryName: String
)

class RestoreJournalStore(private val paths: BackupPaths) {
    fun write(journal: RestoreJournal) {
        if (!paths.restoreRoot.exists() && !paths.restoreRoot.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Restore journal directory is unavailable")
        }
        validateDirectoryName(journal.candidateDirectoryName)
        validateDirectoryName(journal.rollbackDirectoryName)
        val temp = File(paths.restoreRoot, ".restore-journal-${UUID.randomUUID()}.tmp")
        val properties = Properties().apply {
            setProperty("phase", journal.phase.name)
            setProperty("candidate", journal.candidateDirectoryName)
            setProperty("rollback", journal.rollbackDirectoryName)
        }
        FileOutputStream(temp).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        move(temp, paths.restoreJournalFile, replace = true)
    }

    fun readOrNull(): RestoreJournal? {
        if (!paths.restoreJournalFile.exists()) return null
        val properties = try {
            FileInputStream(paths.restoreJournalFile).use { input ->
                Properties().apply { load(input) }
            }
        } catch (error: Exception) {
            backupFail(BackupFailure.RollbackFailure, "Restore journal is unreadable", error)
        }
        val phase = properties.getProperty("phase")
            ?.let { runCatching { RestoreJournalPhase.valueOf(it) }.getOrNull() }
            ?: backupFail(BackupFailure.RollbackFailure, "Restore journal phase is invalid")
        val candidate = properties.getProperty("candidate")
            ?: backupFail(BackupFailure.RollbackFailure, "Restore candidate is missing")
        val rollback = properties.getProperty("rollback")
            ?: backupFail(BackupFailure.RollbackFailure, "Restore rollback is missing")
        validateDirectoryName(candidate)
        validateDirectoryName(rollback)
        return RestoreJournal(phase, candidate, rollback)
    }

    fun clear() {
        if (paths.restoreJournalFile.exists() && !paths.restoreJournalFile.delete()) {
            backupFail(BackupFailure.RollbackFailure, "Restore journal could not be cleared")
        }
    }

    private fun validateDirectoryName(name: String) {
        if (!Regex("(?:candidate|rollback)-[A-Za-z0-9-]+").matches(name)) {
            backupFail(BackupFailure.RollbackFailure, "Unsafe restore journal path")
        }
    }
}

class RestoreStartupRecovery(
    private val paths: BackupPaths,
    private val inspector: BackupDatabaseInspector = BackupDatabaseInspector()
) {
    fun recoverIfNeeded() {
        val journalStore = RestoreJournalStore(paths)
        val journal = journalStore.readOrNull()
        if (journal == null) {
            cleanupOrphans()
            return
        }
        val loader = BackupBundleLoader(paths, inspector)
        val installer = BackupLiveInstaller(paths, inspector)
        val candidateDirectory = paths.requireSafeRestoreChild(
            File(paths.restoreRoot, journal.candidateDirectoryName)
        )
        val rollbackDirectory = paths.requireSafeRestoreChild(
            File(paths.restoreRoot, journal.rollbackDirectoryName)
        )
        if (journal.phase == RestoreJournalPhase.Applied) {
            val candidate = runCatching { loader.load(candidateDirectory) }.getOrNull()
            if (candidate != null && runCatching {
                    inspector.validateLive(paths, candidate.manifest)
                }.isSuccess
            ) {
                journalStore.clear()
                cleanupOrphans()
                return
            }
        }
        val rollback = try {
            loader.load(rollbackDirectory)
        } catch (error: Exception) {
            backupFail(
                BackupFailure.RollbackFailure,
                "Rollback snapshot is unavailable; production data was not opened",
                error
            )
        }
        try {
            installer.install(rollback)
            inspector.validateLive(paths, rollback.manifest)
            journalStore.clear()
            cleanupOrphans()
        } catch (error: Exception) {
            backupFail(
                BackupFailure.RollbackFailure,
                "Rollback could not be completed; production data was not opened",
                error
            )
        }
    }

    private fun cleanupOrphans() {
        if (!paths.restoreRoot.exists()) return
        paths.restoreRoot.listFiles().orEmpty().forEach { file ->
            if (file != paths.restoreJournalFile) file.deleteRecursively()
        }
    }
}

internal fun copyAndSync(source: File, destination: File) {
    if (!source.isFile) {
        backupFail(BackupFailure.EvidenceMissing, "Source file is unavailable")
    }
    destination.parentFile?.let { parent ->
        if (!parent.exists() && !parent.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Destination directory is unavailable")
        }
    }
    FileInputStream(source).use { input ->
        FileOutputStream(destination).use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }
}

internal fun move(source: File, destination: File, replace: Boolean) {
    val options = buildList {
        add(StandardCopyOption.ATOMIC_MOVE)
        if (replace) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    try {
        Files.move(source.toPath(), destination.toPath(), *options)
    } catch (_: AtomicMoveNotSupportedException) {
        val fallback = if (replace) {
            arrayOf(StandardCopyOption.REPLACE_EXISTING)
        } else {
            emptyArray()
        }
        Files.move(source.toPath(), destination.toPath(), *fallback)
    }
}
