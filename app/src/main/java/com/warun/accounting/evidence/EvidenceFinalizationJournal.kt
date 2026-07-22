package com.warun.accounting.evidence

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class EvidenceFinalizationState {
    Prepared,
    AccountingSaved
}

data class EvidenceFinalizationEntry(
    val captureId: String,
    val expenseDraftId: String,
    val expenseRecordId: String,
    val pendingFileName: String,
    val expenseFingerprint: String,
    val state: EvidenceFinalizationState,
    val createdAt: Long,
    val updatedAt: Long
)

class EvidenceFinalizationJournal(
    private val journalDirectory: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val Version = "1"
        private const val JournalPrefix = "finalization_"
        private const val JournalSuffix = ".journal"
        private const val TempSuffix = ".tmp"
        private const val QuarantineSuffix = ".bad"
        private val ValidCaptureId = Regex("[A-Za-z0-9-]+")
        private val ValidExpenseId = Regex("[A-Za-z0-9._-]{1,256}")
        private val ValidFingerprint = Regex("[0-9a-f]{64}")
        private val RequiredKeys = setOf(
            "version",
            "captureId",
            "expenseDraftId",
            "expenseRecordId",
            "pendingFileName",
            "expenseFingerprint",
            "state",
            "createdAt",
            "updatedAt",
            "checksum"
        )
    }

    @Synchronized
    fun prepare(
        captureId: String,
        expenseDraftId: String,
        expenseRecordId: String,
        expenseFingerprint: String
    ): EvidenceFinalizationEntry {
        validateIdentifiers(captureId, expenseDraftId, expenseRecordId, expenseFingerprint)
        rejectQuarantinedCapture(captureId)
        val pendingFileName = pendingFileName(captureId)
        val existing = find(captureId)
        if (existing != null) {
            if (
                existing.expenseDraftId != expenseDraftId ||
                existing.expenseRecordId != expenseRecordId ||
                existing.pendingFileName != pendingFileName
            ) {
                throw EvidenceJournalConflictException("captureIdの所有者が既存ジャーナルと一致しません")
            }
            if (existing.state == EvidenceFinalizationState.AccountingSaved) {
                return existing
            }
            return existing.copy(
                expenseFingerprint = expenseFingerprint,
                updatedAt = now()
            ).also(::writeAtomically)
        }
        val timestamp = now()
        return EvidenceFinalizationEntry(
            captureId = captureId,
            expenseDraftId = expenseDraftId,
            expenseRecordId = expenseRecordId,
            pendingFileName = pendingFileName,
            expenseFingerprint = expenseFingerprint,
            state = EvidenceFinalizationState.Prepared,
            createdAt = timestamp,
            updatedAt = timestamp
        ).also(::writeAtomically)
    }

    @Synchronized
    fun markAccountingSaved(
        captureId: String,
        expenseRecordId: String,
        expenseFingerprint: String
    ): EvidenceFinalizationEntry {
        val existing = find(captureId)
            ?: throw EvidenceJournalException("正式化ジャーナルが見つかりません")
        if (
            existing.expenseRecordId != expenseRecordId ||
            existing.expenseFingerprint != expenseFingerprint
        ) {
            throw EvidenceJournalConflictException("保存済み支出と正式化ジャーナルが一致しません")
        }
        if (existing.state == EvidenceFinalizationState.AccountingSaved) return existing
        return existing.copy(
            state = EvidenceFinalizationState.AccountingSaved,
            updatedAt = now()
        ).also(::writeAtomically)
    }

    @Synchronized
    fun complete(captureId: String, expenseRecordId: String) {
        val existing = find(captureId) ?: return
        if (existing.expenseRecordId != expenseRecordId) {
            throw EvidenceJournalConflictException("完了対象の支出が正式化ジャーナルと一致しません")
        }
        val file = journalFile(captureId)
        if (file.exists() && !file.delete()) {
            throw EvidenceJournalException("正式化済みジャーナルを削除できませんでした")
        }
    }

    @Synchronized
    fun find(captureId: String): EvidenceFinalizationEntry? {
        validateCaptureId(captureId)
        val file = journalFile(captureId)
        if (!file.exists()) return null
        return try {
            parse(file, captureId)
        } catch (error: Exception) {
            quarantine(file, captureId, error)
            throw EvidenceJournalCorruptException("正式化ジャーナルが破損しているため隔離しました", error)
        }
    }

    @Synchronized
    fun loadAll(): List<EvidenceFinalizationEntry> {
        if (!journalDirectory.exists()) return emptyList()
        if (!journalDirectory.isDirectory) {
            throw EvidenceJournalException("正式化ジャーナルの保存先がディレクトリではありません")
        }
        return journalDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(JournalPrefix) && it.name.endsWith(JournalSuffix) }
            .mapNotNull { file ->
                val captureId = file.name.removePrefix(JournalPrefix).removeSuffix(JournalSuffix)
                try {
                    validateCaptureId(captureId)
                    parse(file, captureId)
                } catch (error: Exception) {
                    quarantine(file, captureId.takeIf { it.matches(ValidCaptureId) }, error)
                    null
                }
            }
            .sortedBy { it.createdAt }
            .toList()
    }

    fun journalFileFor(captureId: String): File {
        validateCaptureId(captureId)
        return journalFile(captureId)
    }

    fun quarantinedFiles(): List<File> = quarantineDirectory().listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(QuarantineSuffix) }

    private fun writeAtomically(entry: EvidenceFinalizationEntry) {
        ensureJournalDirectory()
        val target = journalFile(entry.captureId)
        val temp = File(journalDirectory, ".${target.name}.${UUID.randomUUID()}$TempSuffix")
        val content = serialize(entry)
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temp.delete()
            throw EvidenceJournalException("正式化ジャーナルを保存できませんでした", error)
        }
    }

    private fun parse(file: File, expectedCaptureId: String): EvidenceFinalizationEntry {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            throw EvidenceJournalException("正式化ジャーナルを読み込めません")
        }
        val values = linkedMapOf<String, String>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) throw EvidenceJournalException("正式化ジャーナルの形式が不正です")
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (values.put(key, value) != null) {
                throw EvidenceJournalException("正式化ジャーナルに重複項目があります")
            }
        }
        if (values.keys != RequiredKeys || values.getValue("version") != Version) {
            throw EvidenceJournalException("正式化ジャーナルのversionまたは項目が不正です")
        }
        val entry = EvidenceFinalizationEntry(
            captureId = values.getValue("captureId"),
            expenseDraftId = values.getValue("expenseDraftId"),
            expenseRecordId = values.getValue("expenseRecordId"),
            pendingFileName = values.getValue("pendingFileName"),
            expenseFingerprint = values.getValue("expenseFingerprint"),
            state = EvidenceFinalizationState.valueOf(values.getValue("state")),
            createdAt = values.getValue("createdAt").toLong(),
            updatedAt = values.getValue("updatedAt").toLong()
        )
        validateIdentifiers(
            entry.captureId,
            entry.expenseDraftId,
            entry.expenseRecordId,
            entry.expenseFingerprint
        )
        if (entry.captureId != expectedCaptureId) {
            throw EvidenceJournalException("正式化ジャーナルのファイル名とcaptureIdが一致しません")
        }
        if (entry.pendingFileName != pendingFileName(entry.captureId)) {
            throw EvidenceJournalException("pending画像参照がcaptureIdと一致しません")
        }
        if (entry.createdAt < 0L || entry.updatedAt < entry.createdAt) {
            throw EvidenceJournalException("正式化ジャーナルの時刻が不正です")
        }
        val expectedChecksum = checksum(canonical(entry))
        if (values.getValue("checksum") != expectedChecksum) {
            throw EvidenceJournalException("正式化ジャーナルのchecksumが一致しません")
        }
        return entry
    }

    private fun serialize(entry: EvidenceFinalizationEntry): String {
        val canonical = canonical(entry)
        return "$canonical\nchecksum=${checksum(canonical)}\n"
    }

    private fun canonical(entry: EvidenceFinalizationEntry): String = listOf(
        "version=$Version",
        "captureId=${entry.captureId}",
        "expenseDraftId=${entry.expenseDraftId}",
        "expenseRecordId=${entry.expenseRecordId}",
        "pendingFileName=${entry.pendingFileName}",
        "expenseFingerprint=${entry.expenseFingerprint}",
        "state=${entry.state.name}",
        "createdAt=${entry.createdAt}",
        "updatedAt=${entry.updatedAt}"
    ).joinToString("\n")

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun quarantine(file: File, captureId: String?, cause: Throwable) {
        val directory = quarantineDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw EvidenceJournalException("破損した正式化ジャーナルを隔離できませんでした", cause)
        }
        if (!directory.isDirectory) {
            throw EvidenceJournalException("正式化ジャーナルの隔離先がディレクトリではありません", cause)
        }
        val safeId = captureId ?: "unknown"
        val destination = File(directory, "$JournalPrefix$safeId.${UUID.randomUUID()}$QuarantineSuffix")
        try {
            Files.move(file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(file.toPath(), destination.toPath())
            } catch (error: Exception) {
                throw EvidenceJournalException("破損した正式化ジャーナルを隔離できませんでした", error)
            }
        } catch (error: Exception) {
            throw EvidenceJournalException("破損した正式化ジャーナルを隔離できませんでした", error)
        }
    }

    private fun rejectQuarantinedCapture(captureId: String) {
        val prefix = "$JournalPrefix$captureId."
        if (quarantinedFiles().any { it.name.startsWith(prefix) }) {
            throw EvidenceJournalCorruptException("隔離済みジャーナルがあるため自動正式化できません")
        }
    }

    private fun ensureJournalDirectory() {
        if (journalDirectory.exists()) {
            if (!journalDirectory.isDirectory) {
                throw EvidenceJournalException("正式化ジャーナルの保存先がディレクトリではありません")
            }
            return
        }
        if (!journalDirectory.mkdirs()) {
            throw EvidenceJournalException("正式化ジャーナルの保存先を作成できませんでした")
        }
    }

    private fun journalFile(captureId: String): File =
        File(journalDirectory, "$JournalPrefix$captureId$JournalSuffix")

    private fun quarantineDirectory(): File = File(journalDirectory, "quarantine")

    private fun pendingFileName(captureId: String): String = "receipt_$captureId.jpg"

    private fun validateIdentifiers(
        captureId: String,
        expenseDraftId: String,
        expenseRecordId: String,
        expenseFingerprint: String
    ) {
        validateCaptureId(captureId)
        if (!expenseDraftId.matches(ValidExpenseId) || !expenseRecordId.matches(ValidExpenseId)) {
            throw EvidenceJournalException("不正な支出IDです")
        }
        if (!expenseFingerprint.matches(ValidFingerprint)) {
            throw EvidenceJournalException("不正な支出fingerprintです")
        }
    }

    private fun validateCaptureId(captureId: String) {
        if (!captureId.matches(ValidCaptureId)) {
            throw EvidenceJournalException("不正なcaptureIdです")
        }
    }
}

open class EvidenceJournalException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class EvidenceJournalConflictException(message: String) : EvidenceJournalException(message)

class EvidenceJournalCorruptException(
    message: String,
    cause: Throwable? = null
) : EvidenceJournalException(message, cause)
