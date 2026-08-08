package com.warun.accounting.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupArchive {
    fun write(
        output: OutputStream,
        manifest: BackupManifest,
        databaseFile: File,
        evidenceFiles: Map<String, File>
    ) {
        manifest.validateContract()
        val expectedIds = manifest.evidence.map { it.evidenceId }.toSet()
        if (evidenceFiles.keys != expectedIds) {
            backupFail(BackupFailure.EvidenceMismatch, "Evidence set does not match manifest")
        }
        try {
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry(BackupContract.ManifestEntry))
                BackupManifestXml.write(manifest, zip)
                zip.closeEntry()
                zip.writeFileEntry(manifest.database, databaseFile)
                manifest.evidence.forEach { item ->
                    zip.writeFileEntry(
                        item.archiveEntry,
                        requireNotNull(evidenceFiles[item.evidenceId])
                    )
                }
                zip.finish()
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.OutputFailure, "Backup archive could not be written", error)
        }
    }

    fun extractAndValidate(
        input: InputStream,
        destination: File
    ): ValidatedBackupArchive {
        if (destination.exists() || !destination.mkdirs()) {
            backupFail(BackupFailure.RestoreFailure, "Restore staging directory is unavailable")
        }
        val extracted = LinkedHashMap<String, ExtractedEntry>()
        var totalBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = validateArchivePath(entry)
                    if (extracted.containsKey(path)) {
                        backupFail(BackupFailure.DuplicateArchiveEntry, "Duplicate archive entry")
                    }
                    if (extracted.size >= BackupContract.MaxEntries) {
                        backupFail(BackupFailure.ArchiveTooLarge, "Too many archive entries")
                    }
                    val maximum = maximumEntrySize(path)
                    if (entry.size > maximum) {
                        backupFail(BackupFailure.ArchiveTooLarge, "Archive entry is too large")
                    }
                    val outputFile = safeDestination(destination, path)
                    outputFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            backupFail(BackupFailure.RestoreFailure, "Cannot create staging path")
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var entryBytes = 0L
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalBytes += count
                            if (entryBytes > maximum || totalBytes > BackupContract.MaxArchiveBytes) {
                                backupFail(BackupFailure.ArchiveTooLarge, "Archive exceeds limits")
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                    extracted[path] = ExtractedEntry(
                        file = outputFile,
                        size = entryBytes,
                        sha256 = digest.digest().toHex()
                    )
                    zip.closeEntry()
                }
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: ZipException) {
            backupFail(BackupFailure.CorruptArchive, "Backup ZIP is corrupt", error)
        } catch (error: Exception) {
            backupFail(BackupFailure.CorruptArchive, "Backup archive could not be read", error)
        }

        val manifestEntry = extracted[BackupContract.ManifestEntry]
            ?: backupFail(BackupFailure.MissingArchiveEntry, "Manifest is missing")
        val manifest = FileInputStream(manifestEntry.file).use(BackupManifestXml::read)
        val expectedPaths = buildSet {
            add(BackupContract.ManifestEntry)
            add(manifest.database.path)
            manifest.evidence.forEach { add(it.archiveEntry.path) }
        }
        val missing = expectedPaths - extracted.keys
        if (missing.isNotEmpty()) {
            backupFail(BackupFailure.MissingArchiveEntry, "Required archive entry is missing")
        }
        val unexpected = extracted.keys - expectedPaths
        if (unexpected.isNotEmpty()) {
            backupFail(BackupFailure.UnexpectedArchiveEntry, "Unexpected archive entry")
        }
        validateExtracted(manifest.database, requireNotNull(extracted[manifest.database.path]))
        manifest.evidence.forEach { item ->
            validateExtracted(
                item.archiveEntry,
                requireNotNull(extracted[item.archiveEntry.path])
            )
        }
        return ValidatedBackupArchive(
            manifest = manifest,
            rootDirectory = destination,
            databaseFile = requireNotNull(extracted[manifest.database.path]).file,
            evidenceFiles = manifest.evidence.associate { item ->
                item.evidenceId to requireNotNull(extracted[item.archiveEntry.path]).file
            }
        )
    }

    fun sha256(file: File): String = FileInputStream(file).use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    fun manifestBytes(manifest: BackupManifest): ByteArray =
        ByteArrayOutputStream().use { output ->
            BackupManifestXml.write(manifest, output)
            output.toByteArray()
        }

    private fun ZipOutputStream.writeFileEntry(entry: BackupArchiveEntry, file: File) {
        if (!file.isFile || file.length() != entry.size || sha256(file) != entry.sha256) {
            backupFail(BackupFailure.ChecksumMismatch, "Source entry changed during backup")
        }
        putNextEntry(ZipEntry(entry.path))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    private fun validateArchivePath(entry: ZipEntry): String {
        val path = entry.name
        if (entry.isDirectory || path.isBlank() || path.startsWith('/') ||
            path.startsWith('\\') || '\\' in path || ':' in path ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            backupFail(BackupFailure.UnsafeArchivePath, "Unsafe archive path")
        }
        val supported = path == BackupContract.ManifestEntry ||
            path == BackupContract.DatabaseEntry ||
            Regex("evidence/[A-Za-z0-9-]+\\.jpg").matches(path)
        if (!supported) {
            backupFail(BackupFailure.UnexpectedArchiveEntry, "Unsupported archive path")
        }
        return path
    }

    private fun maximumEntrySize(path: String): Long = when {
        path == BackupContract.ManifestEntry -> BackupContract.MaxManifestBytes
        path == BackupContract.DatabaseEntry -> BackupContract.MaxDatabaseBytes
        path.startsWith("evidence/") -> BackupContract.MaxEvidenceBytes
        else -> backupFail(BackupFailure.UnexpectedArchiveEntry, "Unsupported archive path")
    }

    private fun safeDestination(root: File, path: String): File {
        val destination = File(root, path)
        val rootPath = root.canonicalFile.toPath()
        val destinationPath = destination.canonicalFile.toPath()
        if (!destinationPath.startsWith(rootPath)) {
            backupFail(BackupFailure.UnsafeArchivePath, "Archive path escaped staging")
        }
        return destination
    }

    private fun validateExtracted(
        expected: BackupArchiveEntry,
        actual: ExtractedEntry
    ) {
        if (expected.size != actual.size) {
            backupFail(BackupFailure.SizeMismatch, "Archive entry size mismatch")
        }
        if (expected.sha256 != actual.sha256) {
            backupFail(BackupFailure.ChecksumMismatch, "Archive checksum mismatch")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private data class ExtractedEntry(
        val file: File,
        val size: Long,
        val sha256: String
    )
}
