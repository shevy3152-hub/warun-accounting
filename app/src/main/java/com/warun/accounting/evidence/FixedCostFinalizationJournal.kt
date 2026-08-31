package com.warun.accounting.evidence

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

enum class FixedCostFinalizationState {
    Prepared,
    PendingSaved,
    AllFilesStored,
    DatabaseApplied
}

data class FixedCostJournalEvidence(
    val evidenceId: String,
    val mediaType: String,
    val pendingPath: String,
    val finalPath: String,
    val sha256: String,
    val byteSize: Long,
    val sortOrder: Int,
    val state: FixedCostFinalizationState
)

data class FixedCostFinalizationEntry(
    val applicationId: String,
    val receiptId: String,
    val dailyReportId: String,
    val fixedCostType: String,
    val paymentMethod: String,
    val appliedAmount: Long,
    val evidence: List<FixedCostJournalEvidence>,
    val state: FixedCostFinalizationState,
    val createdAt: Long,
    val updatedAt: Long
)

data class FixedCostJournalLoadResult(
    val entries: List<FixedCostFinalizationEntry>,
    val quarantinedFiles: List<File>
)

class FixedCostFinalizationJournal(
    private val journalDirectory: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val Version = "1"
        private const val Prefix = "fixed_cost_finalization_"
        private const val Suffix = ".journal"
        private const val TempSuffix = ".tmp"
        private const val BadSuffix = ".bad"
        private val ValidId = Regex("[A-Za-z0-9-]{1,256}")
    }

    @Synchronized
    fun prepare(
        applicationId: String,
        receiptId: String,
        dailyReportId: String,
        fixedCostType: String,
        paymentMethod: String,
        appliedAmount: Long,
        evidence: List<FixedCostJournalEvidence>
    ): FixedCostFinalizationEntry {
        listOf(applicationId, receiptId, dailyReportId).forEach(::validateId)
        require(appliedAmount >= 0L) { "applied amount must be non-negative" }
        require(evidence.isNotEmpty()) { "fixed-cost application requires Evidence" }
        require(evidence.map { it.sortOrder }.distinct().size == evidence.size) {
            "Evidence sortOrder must be unique"
        }
        val existing = find(applicationId)
        if (existing != null) {
            if (
                existing.receiptId != receiptId ||
                existing.dailyReportId != dailyReportId ||
                existing.fixedCostType != fixedCostType ||
                existing.paymentMethod != paymentMethod ||
                existing.appliedAmount != appliedAmount ||
                existing.evidence.map { it.evidenceId } != evidence.map { it.evidenceId }
            ) throw FixedCostJournalConflictException("applicationId conflicts with existing journal")
            return existing
        }
        val timestamp = now()
        return FixedCostFinalizationEntry(
            applicationId, receiptId, dailyReportId, fixedCostType, paymentMethod,
            appliedAmount, evidence, FixedCostFinalizationState.Prepared, timestamp, timestamp
        ).also(::writeAtomically)
    }

    @Synchronized
    fun markPendingSaved(applicationId: String): FixedCostFinalizationEntry = update(applicationId) {
        copy(state = FixedCostFinalizationState.PendingSaved, updatedAt = now())
    }

    @Synchronized
    fun markEvidenceStored(
        applicationId: String,
        evidenceId: String,
        finalPath: String,
        sha256: String,
        byteSize: Long
    ): FixedCostFinalizationEntry = update(applicationId) {
        val updated = evidence.map { item ->
            if (item.evidenceId == evidenceId) item.copy(
                finalPath = finalPath,
                sha256 = sha256,
                byteSize = byteSize,
                state = FixedCostFinalizationState.AllFilesStored
            ) else item
        }
        check(updated.any { it.evidenceId == evidenceId }) { "Evidence is not in journal" }
        copy(evidence = updated, updatedAt = now())
    }.let { entry ->
        if (entry.evidence.all { it.state == FixedCostFinalizationState.AllFilesStored }) {
            update(applicationId) { copy(state = FixedCostFinalizationState.AllFilesStored, updatedAt = now()) }
        } else entry
    }

    @Synchronized
    fun markDatabaseApplied(applicationId: String): FixedCostFinalizationEntry = update(applicationId) {
        check(state == FixedCostFinalizationState.AllFilesStored) { "Evidence files are not fully stored" }
        copy(state = FixedCostFinalizationState.DatabaseApplied, updatedAt = now())
    }

    @Synchronized
    fun find(applicationId: String): FixedCostFinalizationEntry? {
        validateId(applicationId)
        val file = journalFileFor(applicationId)
        if (!file.isFile) return null
        return try { parse(file, applicationId) } catch (error: Exception) {
            quarantine(file, error)
            throw FixedCostJournalCorruptException("Fixed-cost journal was quarantined", error)
        }
    }

    @Synchronized
    fun loadAll(): FixedCostJournalLoadResult {
        if (!journalDirectory.exists()) return FixedCostJournalLoadResult(emptyList(), emptyList())
        if (!journalDirectory.isDirectory) throw FixedCostJournalException("Journal directory is invalid")
        val quarantined = mutableListOf<File>()
        val entries = journalDirectory.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.startsWith(Prefix) && it.name.endsWith(Suffix) }
            .mapNotNull { file ->
                val id = file.name.removePrefix(Prefix).removeSuffix(Suffix)
                try { parse(file, id) } catch (error: Exception) {
                    quarantined += quarantine(file, error)
                    null
                }
            }.sortedBy { it.createdAt }.toList()
        return FixedCostJournalLoadResult(entries, quarantined)
    }

    @Synchronized
    fun complete(applicationId: String) {
        val file = journalFileFor(applicationId)
        if (file.isFile && !file.delete()) throw FixedCostJournalException("Journal could not be removed")
    }

    fun journalFileFor(applicationId: String): File {
        validateId(applicationId)
        return File(journalDirectory, "$Prefix$applicationId$Suffix")
    }

    fun quarantineDirectory(): File = File(journalDirectory, "quarantine")

    private fun update(applicationId: String, transform: FixedCostFinalizationEntry.() -> FixedCostFinalizationEntry): FixedCostFinalizationEntry {
        val current = find(applicationId) ?: throw FixedCostJournalException("Journal not found")
        return transform(current).also(::writeAtomically)
    }

    private fun writeAtomically(entry: FixedCostFinalizationEntry) {
        if (!journalDirectory.exists() && !journalDirectory.mkdirs()) throw FixedCostJournalException("Journal directory could not be created")
        val target = journalFileFor(entry.applicationId)
        val temp = File(journalDirectory, ".${target.name}.${UUID.randomUUID()}$TempSuffix")
        val properties = Properties()
        properties["version"] = Version
        properties["applicationId"] = entry.applicationId
        properties["receiptId"] = entry.receiptId
        properties["dailyReportId"] = entry.dailyReportId
        properties["fixedCostType"] = entry.fixedCostType
        properties["paymentMethod"] = entry.paymentMethod
        properties["appliedAmount"] = entry.appliedAmount.toString()
        properties["state"] = entry.state.name
        properties["createdAt"] = entry.createdAt.toString()
        properties["updatedAt"] = entry.updatedAt.toString()
        properties["evidenceCount"] = entry.evidence.size.toString()
        entry.evidence.forEachIndexed { index, item ->
            properties["evidence.$index.id"] = item.evidenceId
            properties["evidence.$index.mediaType"] = item.mediaType
            properties["evidence.$index.pendingPath"] = item.pendingPath
            properties["evidence.$index.finalPath"] = item.finalPath
            properties["evidence.$index.sha256"] = item.sha256
            properties["evidence.$index.byteSize"] = item.byteSize.toString()
            properties["evidence.$index.sortOrder"] = item.sortOrder.toString()
            properties["evidence.$index.state"] = item.state.name
        }
        properties["checksum"] = checksum(properties)
        try {
            FileOutputStream(temp).use { output -> properties.store(output, null); output.fd.sync() }
            moveReplace(temp, target)
        } catch (error: Exception) {
            temp.delete()
            throw FixedCostJournalException("Journal atomic write failed", error)
        }
    }

    private fun parse(file: File, expectedId: String): FixedCostFinalizationEntry {
        val properties = Properties()
        file.inputStream().use(properties::load)
        check(properties["version"] == Version)
        check(properties["applicationId"] == expectedId)
        check(properties["checksum"] == checksum(properties))
        val count = properties.requiredInt("evidenceCount")
        check(count in 1..100)
        val evidence = (0 until count).map { index ->
            FixedCostJournalEvidence(
                properties.required("evidence.$index.id"),
                properties.required("evidence.$index.mediaType"),
                properties.required("evidence.$index.pendingPath"),
                properties.required("evidence.$index.finalPath"),
                properties.requiredAllowEmpty("evidence.$index.sha256"),
                properties.requiredLong("evidence.$index.byteSize"),
                properties.requiredInt("evidence.$index.sortOrder"),
                FixedCostFinalizationState.valueOf(properties.required("evidence.$index.state"))
            ).also { item ->
                check(ValidId.matches(item.evidenceId))
                check(item.mediaType in setOf("image/jpeg", "image/png", "application/pdf"))
                check(item.pendingPath.isNotBlank() && item.finalPath.isNotBlank())
                check(item.sortOrder >= 0)
                check(item.byteSize >= 0L)
                check(item.sha256.isEmpty() || item.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
        return FixedCostFinalizationEntry(
            properties.required("applicationId"), properties.required("receiptId"),
            properties.required("dailyReportId"), properties.required("fixedCostType"),
            properties.required("paymentMethod"), properties.requiredLong("appliedAmount"),
            evidence, FixedCostFinalizationState.valueOf(properties.required("state")),
            properties.requiredLong("createdAt"), properties.requiredLong("updatedAt")
        )
    }

    private fun checksum(properties: Properties): String = buildString {
        properties.stringPropertyNames().filter { it != "checksum" }.sorted().forEach { key -> append(key).append('=').append(properties.getProperty(key)).append('\n') }
    }.toByteArray(Charsets.UTF_8).let { MessageDigest.getInstance("SHA-256").digest(it) }
        .joinToString("") { "%02x".format(it) }

    private fun quarantine(file: File, cause: Throwable): File {
        if (!quarantineDirectory().exists() && !quarantineDirectory().mkdirs()) throw FixedCostJournalException("Journal quarantine unavailable", cause)
        val target = File(quarantineDirectory(), "${file.name}.${UUID.randomUUID()}$BadSuffix")
        moveReplace(file, target)
        return target
    }

    private fun moveReplace(source: File, target: File) {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun validateId(value: String) { require(ValidId.matches(value)) { "Invalid journal ID" } }
    private fun Properties.required(key: String): String = getProperty(key)?.takeIf { it.isNotEmpty() } ?: error("Missing journal field: $key")
    private fun Properties.requiredAllowEmpty(key: String): String = getProperty(key) ?: error("Missing journal field: $key")
    private fun Properties.requiredLong(key: String): Long = required(key).toLong()
    private fun Properties.requiredInt(key: String): Int = required(key).toInt()
}

open class FixedCostJournalException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class FixedCostJournalConflictException(message: String) : FixedCostJournalException(message)
class FixedCostJournalCorruptException(message: String, cause: Throwable? = null) : FixedCostJournalException(message, cause)
