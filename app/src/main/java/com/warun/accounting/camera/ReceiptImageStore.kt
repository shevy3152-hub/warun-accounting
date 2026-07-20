package com.warun.accounting.camera

import java.io.File
import java.util.UUID

class ReceiptImageStore(
    private val pendingDirectory: File,
    private val now: () -> Long = System::currentTimeMillis
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
            if (file.exists()) check(file.delete()) { "Unable to replace pending receipt image" }
        }
    }

    fun delete(captureId: String): Boolean {
        val file = fileFor(captureId)
        return !file.exists() || file.delete()
    }

    fun cleanupExpired(retentionMillis: Long = PendingRetentionMillis): Int {
        val cutoff = now() - retentionMillis
        return pendingDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(FilePrefix) && it.name.endsWith(FileSuffix) }
            .filter { it.lastModified() < cutoff }
            .count { it.delete() }
    }
}
