package com.warun.accounting.camera

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.UUID

data class PendingImageDeletionPolicy(
    val protectedCaptureIds: Set<String> = emptySet(),
    val allowDeletion: Boolean = true
) {
    fun canDelete(captureId: String): Boolean =
        allowDeletion && captureId !in protectedCaptureIds
}

fun interface PendingImageDeletionPolicyProvider {
    fun currentPolicy(): PendingImageDeletionPolicy
}

data class PendingImportFiles(
    val sourceFile: File,
    val normalizedFile: File
)

class ReceiptImageStore(
    private val pendingDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val deletionPolicyProvider: PendingImageDeletionPolicyProvider =
        PendingImageDeletionPolicyProvider { PendingImageDeletionPolicy() }
) {
    companion object {
        const val PendingRetentionMillis: Long = 7L * 24L * 60L * 60L * 1000L
        private const val FilePrefix = "receipt_"
        private const val FileSuffix = ".jpg"
        private const val ImportPrefix = "import_"
        private const val ImportSourceSuffix = ".source.tmp"
        private const val ImportNormalizedSuffix = ".tmp"
        private val ValidCaptureId = Regex("[A-Za-z0-9-]+")
    }

    fun newCaptureId(): String = UUID.randomUUID().toString()

    fun fileFor(captureId: String): File {
        require(captureId.matches(ValidCaptureId)) { "Invalid capture id" }
        return File(pendingDirectory, "$FilePrefix$captureId$FileSuffix")
    }

    fun prepareImportFiles(captureId: String): PendingImportFiles {
        ensurePendingDirectory()
        val destination = fileFor(captureId)
        val files = PendingImportFiles(
            sourceFile = File(pendingDirectory, "$ImportPrefix$captureId$ImportSourceSuffix"),
            normalizedFile = File(pendingDirectory, "$ImportPrefix$captureId$ImportNormalizedSuffix")
        )
        check(!destination.exists() && !files.sourceFile.exists() && !files.normalizedFile.exists()) {
            "Pending receipt import id is already in use"
        }
        return files
    }

    fun publishImportedJpeg(captureId: String, normalizedFile: File): File {
        ensurePendingDirectory()
        val destination = fileFor(captureId)
        check(normalizedFile.parentFile?.canonicalFile == pendingDirectory.canonicalFile) {
            "Imported receipt must be published from the pending directory"
        }
        check(
            normalizedFile.name == "$ImportPrefix$captureId$ImportNormalizedSuffix" &&
                normalizedFile.isFile
        ) {
            "Normalized receipt import is missing"
        }
        check(!destination.exists()) { "Pending receipt image already exists" }
        try {
            Files.move(normalizedFile.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(normalizedFile.toPath(), destination.toPath())
        }
        return destination
    }

    fun cleanupOrphanedImportFiles(): Int {
        if (!pendingDirectory.exists()) return 0
        val importFilePattern = Regex(
            "^${Regex.escape(ImportPrefix)}[A-Za-z0-9-]+(?:${Regex.escape(ImportSourceSuffix)}|${Regex.escape(ImportNormalizedSuffix)})$"
        )
        return pendingDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.matches(importFilePattern) }
            .count(File::delete)
    }

    fun prepareFile(captureId: String): File {
        ensurePendingDirectory()
        return fileFor(captureId).also { file ->
            if (file.exists()) {
                check(deletionPolicyProvider.currentPolicy().canDelete(captureId)) {
                    "Pending receipt image is protected by evidence recovery"
                }
                check(file.delete()) { "Unable to replace pending receipt image" }
            }
        }
    }

    fun delete(captureId: String): Boolean {
        val file = fileFor(captureId)
        if (!canDelete(captureId)) return false
        return !file.exists() || file.delete()
    }

    fun canDelete(captureId: String): Boolean {
        val file = fileFor(captureId)
        return !file.exists() || deletionPolicyProvider.currentPolicy().canDelete(captureId)
    }

    fun cleanupExpired(retentionMillis: Long = PendingRetentionMillis): Int {
        val cutoff = now() - retentionMillis
        val policy = deletionPolicyProvider.currentPolicy()
        return pendingDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .mapNotNull { file ->
                val captureId = file.name
                    .takeIf { file.isFile && it.startsWith(FilePrefix) && it.endsWith(FileSuffix) }
                    ?.removePrefix(FilePrefix)
                    ?.removeSuffix(FileSuffix)
                    ?.takeIf { it.matches(ValidCaptureId) }
                captureId?.let { it to file }
            }
            .filter { (_, file) -> file.lastModified() < cutoff }
            .filter { (captureId, _) -> policy.canDelete(captureId) }
            .count { (_, file) -> file.delete() }
    }

    private fun ensurePendingDirectory() {
        check(pendingDirectory.exists() || pendingDirectory.mkdirs()) {
            "Unable to create pending receipt directory"
        }
        check(pendingDirectory.isDirectory) {
            "Pending receipt location is not a directory"
        }
    }
}
