package com.warun.accounting.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.warun.accounting.BuildConfig
import com.warun.accounting.data.local.WarunDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RestorePreview(
    val token: String,
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val roomSchemaVersion: Int,
    val summary: BackupSummary
)

data class BackupCreationResult(
    val createdAtEpochMillis: Long,
    val summary: BackupSummary
)

enum class RestoreExecutionOutcome {
    Applied,
    RolledBackAfterFailure
}

data class RestoreExecutionResult(
    val outcome: RestoreExecutionOutcome,
    val restartRequired: Boolean = true
)

@Singleton
class BackupRestoreManager internal constructor(
    private val context: Context,
    private val database: WarunDatabase,
    private val paths: BackupPaths,
    private val inspector: BackupDatabaseInspector,
    private val restoreInterceptor: RestoreInstallInterceptor,
    private val stagedUpgradeInterceptor: StagedDatabaseUpgradeInterceptor,
    private val outputStreamFactory: (Uri, String) -> OutputStream? = { uri, mode ->
        context.contentResolver.openOutputStream(uri, mode)
    },
    private val inputStreamFactory: (Uri) -> InputStream? = { uri ->
        context.contentResolver.openInputStream(uri)
    }
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: WarunDatabase
    ) : this(
        context = context,
        database = database,
        paths = BackupPaths(context),
        inspector = BackupDatabaseInspector(),
        restoreInterceptor = RestoreInstallInterceptor.None,
        stagedUpgradeInterceptor = StagedDatabaseUpgradeInterceptor.None
    )

    private val snapshotter = DatabaseSnapshotter(database, paths.databaseFile)
    private val bundleBuilder = BackupBundleBuilder(paths, snapshotter, inspector)
    private val bundleLoader = BackupBundleLoader(paths, inspector)
    private val journalStore = RestoreJournalStore(paths)
    private val mutex = Mutex()

    suspend fun createBackup(uri: Uri): BackupCreationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prepared = try {
                prepareValidatedBackup()
            } catch (error: BackupException) {
                logFailure(error)
                throw error
            }
            try {
                val output = try {
                    outputStreamFactory(uri, "wt")
                        ?: backupFail(
                            BackupFailure.OutputFailure,
                            "SAF destination cannot be opened",
                            stage = BackupStage.OutputOpen
                        )
                } catch (error: BackupException) {
                    throw error
                } catch (error: Exception) {
                    backupFailure(BackupFailure.OutputFailure, BackupStage.OutputOpen, error)
                }
                writePreparedBackup(output, prepared.archiveFile)
                verifyWrittenDocument(uri)
                BackupCreationResult(prepared.createdAtEpochMillis, prepared.summary)
            } catch (error: BackupException) {
                logFailure(error)
                throw error
            } finally {
                prepared.archiveFile.delete()
                prepared.verificationDirectory.deleteRecursively()
            }
        }
    }

    internal fun createBackup(output: OutputStream): BackupCreationResult {
        val prepared = prepareValidatedBackup()
        try {
            writePreparedBackup(output, prepared.archiveFile)
            return BackupCreationResult(
                createdAtEpochMillis = prepared.createdAtEpochMillis,
                summary = prepared.summary
            )
        } finally {
            prepared.archiveFile.delete()
            prepared.verificationDirectory.deleteRecursively()
        }
    }

    private data class PreparedBackup(
        val archiveFile: File,
        val verificationDirectory: File,
        val createdAtEpochMillis: Long,
        val summary: BackupSummary
    )

    private fun prepareValidatedBackup(): PreparedBackup {
        ensureNoRestoreInProgress()
        val bundleDirectory = newRestoreDirectory("candidate-backup")
        val archiveFile = File(paths.restoreRoot, ".backup-${UUID.randomUUID()}.tmp")
        val verificationDirectory = newRestoreDirectory("candidate-verify")
        var prepared = false
        try {
            val bundle = bundleBuilder.build(bundleDirectory)
            FileOutputStream(archiveFile).use { archiveOutput ->
                BackupArchive.write(
                    archiveOutput,
                    bundle.manifest,
                    bundle.databaseFile,
                    bundle.evidenceFiles
                )
            }
            FileOutputStream(archiveFile, true).use { it.fd.sync() }
            FileInputStream(archiveFile).use { input ->
                val extracted = BackupArchive.extractAndValidate(input, verificationDirectory)
                inspector.validateBundle(
                    BackupBundle(
                        verificationDirectory,
                        extracted.manifest,
                        extracted.databaseFile,
                        extracted.evidenceFiles
                    )
                )
            }
            val result = PreparedBackup(
                archiveFile = archiveFile,
                verificationDirectory = verificationDirectory,
                createdAtEpochMillis = bundle.manifest.createdAtEpochMillis,
                summary = bundle.manifest.summary
            )
            prepared = true
            return result
        } catch (error: BackupException) {
            if (error.stage == null) {
                throw BackupException(
                    failure = error.failure,
                    message = error.message ?: "Backup generation failed",
                    cause = error,
                    stage = BackupStage.Build
                )
            }
            throw error
        } catch (error: Exception) {
            backupFailure(BackupFailure.OutputFailure, BackupStage.Build, error)
        } finally {
            bundleDirectory.deleteRecursively()
            if (!prepared) {
                archiveFile.delete()
                verificationDirectory.deleteRecursively()
            }
        }
    }

    private fun writePreparedBackup(output: OutputStream, archiveFile: File) {
        var failure: BackupException? = null
        try {
            FileInputStream(archiveFile).use { input ->
                try {
                    input.copyTo(output)
                } catch (error: Exception) {
                    failure = backupException(BackupFailure.OutputFailure, BackupStage.OutputWrite, error)
                }
            }
            if (failure == null) {
                try {
                    output.flush()
                } catch (error: Exception) {
                    failure = backupException(BackupFailure.OutputFailure, BackupStage.OutputFlush, error)
                }
            }
        } finally {
            try {
                output.close()
            } catch (error: Exception) {
                if (failure == null) {
                    failure = backupException(BackupFailure.OutputFailure, BackupStage.OutputClose, error)
                }
            }
        }
        failure?.let { throw it }
    }

    suspend fun stageRestore(uri: Uri): RestorePreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            val input = inputStreamFactory(uri)
                ?: backupFail(BackupFailure.CorruptArchive, "SAF source cannot be opened")
            input.use(::stageRestore)
        }
    }

    internal fun stageRestore(input: InputStream): RestorePreview {
        ensureNoRestoreInProgress()
        cleanupCandidates()
        val candidate = newRestoreDirectory("candidate")
        try {
            val extracted = BackupArchive.extractAndValidate(input, candidate)
            val extractedBundle = BackupBundle(
                rootDirectory = candidate,
                manifest = extracted.manifest,
                databaseFile = extracted.databaseFile,
                evidenceFiles = extracted.evidenceFiles
            )
            inspector.validateBundle(extractedBundle)
            val rebased = StagedEvidenceUriRebaser(paths, inspector).rebase(extractedBundle)
            val bundle = StagedBackupDatabaseUpgrader(
                inspector,
                stagedUpgradeInterceptor
            ).upgradeToCurrent(rebased)
            val token = candidate.name.removePrefix("candidate-")
            return RestorePreview(
                token = token,
                createdAtEpochMillis = bundle.manifest.createdAtEpochMillis,
                appVersion = bundle.manifest.appVersion,
                roomSchemaVersion = bundle.manifest.roomSchemaVersion,
                summary = bundle.manifest.summary
            )
        } catch (error: Exception) {
            candidate.deleteRecursively()
            throw error
        }
    }

    suspend fun restore(token: String): RestoreExecutionResult = withContext(Dispatchers.IO) {
        mutex.withLock { restoreLocked(token) }
    }

    private fun restoreLocked(token: String): RestoreExecutionResult {
        ensureNoRestoreInProgress()
        if (!Regex("[A-Za-z0-9-]+").matches(token)) {
            backupFail(BackupFailure.RestoreFailure, "Invalid restore token")
        }
        val candidateDirectory = paths.requireSafeRestoreChild(
            File(paths.restoreRoot, "candidate-$token")
        )
        val candidate = bundleLoader.load(candidateDirectory)
        val rollbackDirectory = newRestoreDirectory("rollback")
        try {
            database.close()
        } catch (error: Exception) {
            backupFail(
                BackupFailure.RollbackFailure,
                "Room could not close before restore; app restart is required",
                error
            )
        }
        val rollback = try {
            BackupBundleBuilder(
                paths,
                ClosedDatabaseSnapshotter(paths.databaseFile),
                inspector
            ).build(rollbackDirectory)
        } catch (error: Exception) {
            backupFail(
                BackupFailure.RollbackFailure,
                "Rollback snapshot could not be created after Room closed",
                error
            )
        }
        val journal = RestoreJournal(
            phase = RestoreJournalPhase.Prepared,
            candidateDirectoryName = candidateDirectory.name,
            rollbackDirectoryName = rollbackDirectory.name
        )
        try {
            journalStore.write(journal)
        } catch (error: Exception) {
            backupFail(
                BackupFailure.RollbackFailure,
                "Restore journal could not be prepared after Room closed",
                error
            )
        }
        return try {
            BackupLiveInstaller(paths, inspector, restoreInterceptor).install(candidate)
            journalStore.write(journal.copy(phase = RestoreJournalPhase.Applied))
            RestoreExecutionResult(RestoreExecutionOutcome.Applied)
        } catch (restoreError: Exception) {
            try {
                BackupLiveInstaller(paths, inspector).install(rollback)
                inspector.validateLive(paths, rollback.manifest)
                journalStore.clear()
                cleanupCandidates()
                rollbackDirectory.deleteRecursively()
                RestoreExecutionResult(RestoreExecutionOutcome.RolledBackAfterFailure)
            } catch (rollbackError: Exception) {
                backupFail(
                    BackupFailure.RollbackFailure,
                    "Restore failed and rollback must resume on next app start",
                    rollbackError
                )
            }
        }
    }

    fun suggestedFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val time = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "warun-accounting-backup-$time${BackupContract.FileExtension}"
    }

    private fun ensureNoRestoreInProgress() {
        if (journalStore.readOrNull() != null) {
            backupFail(BackupFailure.Busy, "Restore recovery is pending")
        }
    }

    private fun newRestoreDirectory(prefix: String): File {
        if (!paths.restoreRoot.exists() && !paths.restoreRoot.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Private restore staging is unavailable")
        }
        return paths.requireSafeRestoreChild(
            File(paths.restoreRoot, "$prefix-${UUID.randomUUID()}")
        )
    }

    private fun cleanupCandidates() {
        if (!paths.restoreRoot.exists()) return
        paths.restoreRoot.listFiles().orEmpty()
            .filter { it.name.startsWith("candidate-") || it.name.startsWith("candidate-backup-") }
            .forEach(File::deleteRecursively)
    }

    private fun verifyWrittenDocument(uri: Uri) {
        val verificationDirectory = newRestoreDirectory("candidate-verify")
        try {
            val input = inputStreamFactory(uri)
                ?: backupFail(BackupFailure.OutputFailure, "Written SAF document cannot be reopened")
            val extracted = input.use {
                BackupArchive.extractAndValidate(it, verificationDirectory)
            }
            inspector.validateBundle(
                BackupBundle(
                    verificationDirectory,
                    extracted.manifest,
                    extracted.databaseFile,
                    extracted.evidenceFiles
                )
            )
        } catch (error: BackupException) {
            backupFail(
                BackupFailure.OutputFailure,
                "Written SAF document failed verification",
                error,
                BackupStage.OutputVerify
            )
        } catch (error: Exception) {
            backupFailure(BackupFailure.OutputFailure, BackupStage.OutputVerify, error)
        } finally {
            verificationDirectory.deleteRecursively()
        }
    }

    private fun backupFailure(
        failure: BackupFailure,
        stage: BackupStage,
        cause: Throwable
    ): Nothing = throw backupException(failure, stage, cause)

    private fun backupException(
        failure: BackupFailure,
        stage: BackupStage,
        cause: Throwable
    ): BackupException = BackupException(
        failure = failure,
        message = "Backup failed during ${stage.name}",
        cause = cause,
        stage = stage
    )

    private fun logFailure(error: BackupException) {
        Log.e(
            "WarunBackup",
            "backup failed stage=${error.stage?.name ?: "unknown"} " +
                "failure=${error.failure} exception=${error.cause?.javaClass?.name ?: error.javaClass.name}",
            error.cause ?: error
        )
    }
}
