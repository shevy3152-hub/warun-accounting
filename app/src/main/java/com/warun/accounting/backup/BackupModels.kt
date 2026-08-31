package com.warun.accounting.backup

object BackupContract {
    const val FormatVersion = 2
    const val LegacyFormatVersion = 1
    const val CurrentRoomSchemaVersion = 17
    const val CurrentRoomIdentityHash = "43a2c87a4820fd4c21d76c9f2582f297"
    const val PreviousRoomSchemaVersion = 16
    const val PreviousRoomIdentityHash = "e2b19e095274ae3cace88936de475a41"
    const val LegacyRoomSchemaVersion = 15
    const val LegacyRoomIdentityHash = "5bb4c7c0a1ea7a4dd3f4351778db558b"
    val SupportedRoomIdentityHashes = mapOf(
        LegacyRoomSchemaVersion to LegacyRoomIdentityHash,
        PreviousRoomSchemaVersion to PreviousRoomIdentityHash,
        CurrentRoomSchemaVersion to CurrentRoomIdentityHash
    )
    const val ManifestEntry = "manifest.xml"
    const val DatabaseEntry = "database/warun-accounting.db"
    const val MimeType = "application/octet-stream"
    const val FileExtension = ".warunbackup"
    const val MaxManifestBytes = 1L * 1024L * 1024L
    const val MaxDatabaseBytes = 512L * 1024L * 1024L
    const val MaxEvidenceBytes = 50L * 1024L * 1024L
    const val MaxArchiveBytes = 2L * 1024L * 1024L * 1024L
    const val MaxEntries = 10_000
    val ValidEvidenceId = Regex("[A-Za-z0-9-]+")
    val ValidSha256 = Regex("[0-9a-f]{64}")
    val SupportedEvidenceMediaTypes = setOf("image/jpeg", "image/png", "application/pdf")

    fun evidenceEntry(evidenceId: String, mediaType: String = "image/jpeg"): String {
        require(ValidEvidenceId.matches(evidenceId))
        val extension = when (mediaType.substringBefore(';').lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "application/pdf" -> "pdf"
            else -> error("Unsupported Evidence MIME")
        }
        return "evidence/$evidenceId.$extension"
    }
}

data class BackupArchiveEntry(
    val path: String,
    val size: Long,
    val sha256: String
)

data class BackupEvidenceEntry(
    val evidenceId: String,
    val storedUri: String,
    val archiveEntry: BackupArchiveEntry,
    val mediaType: String = "image/jpeg"
)

data class BackupSummary(
    val dailyReportCount: Long,
    val receiptCount: Long,
    val expenseCount: Long,
    val evidenceCount: Long,
    val cancellationCount: Long,
    val prepaidAccountCount: Long,
    val prepaidTransactionCount: Long
)

data class BackupManifest(
    val formatVersion: Int,
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val roomSchemaVersion: Int,
    val database: BackupArchiveEntry,
    val evidence: List<BackupEvidenceEntry>,
    val summary: BackupSummary
)

data class ValidatedBackupArchive(
    val manifest: BackupManifest,
    val rootDirectory: java.io.File,
    val databaseFile: java.io.File,
    val evidenceFiles: Map<String, java.io.File>
)

enum class BackupFailure {
    InvalidManifest,
    UnsupportedFormat,
    UnsupportedSchema,
    UnsafeArchivePath,
    DuplicateArchiveEntry,
    ArchiveTooLarge,
    MissingArchiveEntry,
    UnexpectedArchiveEntry,
    SizeMismatch,
    ChecksumMismatch,
    CorruptArchive,
    CorruptDatabase,
    EvidenceMissing,
    EvidenceMismatch,
    SnapshotFailure,
    OutputFailure,
    RestoreFailure,
    RollbackFailure,
    Busy
}

class BackupException(
    val failure: BackupFailure,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal fun backupFail(
    failure: BackupFailure,
    message: String,
    cause: Throwable? = null
): Nothing = throw BackupException(failure, message, cause)

internal fun BackupArchiveEntry.validate(
    expectedPath: String,
    maximumSize: Long
) {
    if (path != expectedPath || size <= 0L || size > maximumSize) {
        backupFail(BackupFailure.InvalidManifest, "Invalid archive entry metadata")
    }
    if (!BackupContract.ValidSha256.matches(sha256)) {
        backupFail(BackupFailure.InvalidManifest, "Invalid SHA-256 metadata")
    }
}

internal fun BackupManifest.validateContract() {
    if (formatVersion !in setOf(BackupContract.LegacyFormatVersion, BackupContract.FormatVersion)) {
        backupFail(BackupFailure.UnsupportedFormat, "Unsupported backup format")
    }
    if (roomSchemaVersion !in BackupContract.SupportedRoomIdentityHashes) {
        backupFail(BackupFailure.UnsupportedSchema, "Unsupported Room schema")
    }
    if (createdAtEpochMillis < 0L || appVersion.isBlank() || appVersion.length > 100) {
        backupFail(BackupFailure.InvalidManifest, "Invalid backup identity")
    }
    database.validate(BackupContract.DatabaseEntry, BackupContract.MaxDatabaseBytes)
    if (evidence.size > BackupContract.MaxEntries - 2) {
        backupFail(BackupFailure.ArchiveTooLarge, "Too many Evidence entries")
    }
    val ids = HashSet<String>()
    val paths = HashSet<String>()
    evidence.forEach { item ->
        if (!BackupContract.ValidEvidenceId.matches(item.evidenceId) ||
            !ids.add(item.evidenceId) ||
            item.storedUri.isBlank() ||
            item.storedUri.length > 4_096 ||
            item.mediaType !in BackupContract.SupportedEvidenceMediaTypes
        ) {
            backupFail(BackupFailure.InvalidManifest, "Invalid Evidence metadata")
        }
        val expectedPath = BackupContract.evidenceEntry(item.evidenceId, item.mediaType)
        item.archiveEntry.validate(expectedPath, BackupContract.MaxEvidenceBytes)
        if (!paths.add(item.archiveEntry.path)) {
            backupFail(BackupFailure.InvalidManifest, "Duplicate Evidence path")
        }
    }
    val counts = listOf(
        summary.dailyReportCount,
        summary.receiptCount,
        summary.expenseCount,
        summary.evidenceCount,
        summary.cancellationCount,
        summary.prepaidAccountCount,
        summary.prepaidTransactionCount
    )
    if (counts.any { it < 0L } || summary.evidenceCount != evidence.size.toLong()) {
        backupFail(BackupFailure.InvalidManifest, "Invalid backup summary")
    }
}
