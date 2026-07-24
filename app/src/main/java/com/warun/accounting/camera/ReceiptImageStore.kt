package com.warun.accounting.camera

import java.io.File
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
    }

    fun newCaptureId(): String = UUID.randomUUID().toString()

    fun fileFor(captureId: String): File {
        require(captureId.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid capture id" }
        return File(pendingDirectory, "$FilePrefix$captureId$FileSuffix")
    }

    fun prepareFile(captureId: String): File {
        check(pendingDirectory.exists() || pendingDirectory.mkdirs()) {
            "Unable to create pending receipt directory"
        }
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
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }
                captureId?.let { it to file }
            }
            .filter { (_, file) -> file.lastModified() < cutoff }
            .filter { (captureId, _) -> policy.canDelete(captureId) }
            .count { (_, file) -> file.delete() }
    }
}
