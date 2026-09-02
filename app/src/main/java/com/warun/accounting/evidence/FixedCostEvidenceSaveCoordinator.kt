package com.warun.accounting.evidence

import android.content.ContentResolver
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.FixedCostEvidenceLinkRecord
import com.warun.accounting.data.local.FixedCostReceiptApplicationRecord
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.fixedcost.FixedCostSaveResult
import com.warun.accounting.data.fixedcost.fixedCostPaymentMethodOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FixedCostEvidenceSaveRequest(
    val receiptId: String,
    val dailyReportId: String,
    val fixedCostType: String,
    val paymentMethod: String,
    val appliedAmount: Long,
    val sources: List<FixedCostEvidenceSource>,
    /** Non-null only for the DailyReport direct-registration adapter. */
    val directRegistrationKey: String? = null
)

enum class FixedCostFailurePoint {
    JournalPrepared,
    PendingSavedPartially,
    FirstEvidenceStored,
    AllEvidenceStored,
    DatabaseApplied
}

fun interface FixedCostFailureInjector {
    fun after(point: FixedCostFailurePoint)
}

object NoOpFixedCostFailureInjector : FixedCostFailureInjector {
    override fun after(point: FixedCostFailurePoint) = Unit
}

@Singleton
class FixedCostEvidenceSaveCoordinator @Inject constructor(
    private val dao: WarunDao,
    private val store: FixedCostEvidenceFileStore,
    private val journal: FixedCostFinalizationJournal,
    private val failureInjector: FixedCostFailureInjector
) {
    private val mutex = Mutex()

    suspend fun save(
        resolver: ContentResolver,
        request: FixedCostEvidenceSaveRequest
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(request.sources.isNotEmpty()) { "At least one Evidence is required" }
            require(request.sources.map { it.sortOrder }.distinct().size == request.sources.size) {
                "Evidence sortOrder must be unique"
            }
            val paymentMethod = paymentMethodFor(request.fixedCostType)
            val report = dao.getDailyReport(request.dailyReportId)
                ?: throw FixedCostSaveException("Target DailyReport does not exist")
            val receipt = if (request.directRegistrationKey == null) {
                dao.getReceipt(request.receiptId)
                    ?: throw FixedCostSaveException("Target Receipt does not exist")
            } else {
                ReceiptRecord(
                    id = request.receiptId,
                    purchaseDate = report.reportDate,
                    capturedDate = report.reportDate,
                    registeredAt = System.currentTimeMillis(),
                    storeName = fixedCostLabel(request.fixedCostType),
                    totalAmount = request.appliedAmount,
                    taxAmount = 0L,
                    registrationNumber = null,
                    expenseCategory = null,
                    isConfirmed = true,
                    memo = "日報から固定費Evidenceを登録",
                    updatedAt = System.currentTimeMillis()
                )
            }
            if (request.directRegistrationKey == null && receipt.isConfirmed) {
                throw FixedCostSaveException("Receipt is already confirmed")
            }
            if (request.appliedAmount != receipt.totalAmount) {
                throw FixedCostAmountConflictException("Receipt amount does not match request")
            }
            if (request.paymentMethod != paymentMethod) {
                throw FixedCostSaveException("Payment method is determined by fixed-cost type")
            }
            val existingAmount = report.fixedCostAmount(request.fixedCostType)
            if (existingAmount != 0L && existingAmount != receipt.totalAmount) {
                throw FixedCostAmountConflictException("Existing fixed-cost amount conflicts with Receipt")
            }
            if (dao.getFixedCostReceiptApplicationByReceipt(request.receiptId) != null ||
                dao.getFixedCostReceiptApplicationByReportAndType(
                    request.dailyReportId,
                    request.fixedCostType
                ) != null
            ) throw FixedCostSaveException("Receipt or fixed-cost type was already applied")
            val operationId = request.directRegistrationKey
                ?: UUID.randomUUID().toString()
            val applicationId = UUID.nameUUIDFromBytes(
                "fixed-cost-application:$operationId".toByteArray()
            ).toString()
            val evidenceIds = request.sources.map { source ->
                UUID.nameUUIDFromBytes(
                    "fixed-cost-evidence:$operationId:${source.uri}: ${source.sortOrder}".toByteArray()
                ).toString()
            }
            val initialEvidence = request.sources.zip(evidenceIds).map { (source, id) ->
                FixedCostJournalEvidence(
                    evidenceId = id,
                    mediaType = source.mediaType.substringBefore(';').lowercase(),
                    pendingPath = store.pendingFileFor(id, source.mediaType).absolutePath,
                    finalPath = store.storedFileFor(id, source.mediaType).absolutePath,
                    sha256 = "",
                    byteSize = 0L,
                    sortOrder = source.sortOrder,
                    state = FixedCostFinalizationState.Prepared
                )
            }
            journal.prepare(
                applicationId = applicationId,
                receiptId = request.receiptId,
                dailyReportId = request.dailyReportId,
                fixedCostType = request.fixedCostType,
                paymentMethod = paymentMethod,
                appliedAmount = receipt.totalAmount,
                evidence = initialEvidence
            )
            failureInjector.after(FixedCostFailurePoint.JournalPrepared)
            val pending = request.sources.zip(evidenceIds).mapIndexed { index, (source, id) ->
                val result = store.savePending(resolver, source, id)
                if (index == 0 && request.sources.size > 1) {
                    failureInjector.after(FixedCostFailurePoint.PendingSavedPartially)
                }
                result
            }
            journal.markPendingSaved(applicationId)
            val stored = pending.mapIndexed { index, _ ->
                val result = store.promotePending(evidenceIds[index], request.sources[index].mediaType)
                journal.markEvidenceStored(applicationId, result.evidenceId, result.finalPath, result.sha256, result.byteSize)
                if (index == 0) failureInjector.after(FixedCostFailurePoint.FirstEvidenceStored)
                result
            }
            val finalJournal = journal.find(applicationId)
                ?: throw FixedCostSaveException("Fixed-cost journal disappeared")
            check(finalJournal.state == FixedCostFinalizationState.AllFilesStored)
            failureInjector.after(FixedCostFailurePoint.AllEvidenceStored)
            val application = FixedCostReceiptApplicationRecord(
                applicationId = applicationId,
                receiptId = request.receiptId,
                dailyReportId = request.dailyReportId,
                fixedCostType = request.fixedCostType,
                paymentMethod = paymentMethod,
                appliedAt = finalJournal.createdAt,
                updatedAt = finalJournal.updatedAt
            )
            val evidenceRecords = stored.map { result ->
                EvidenceRecord(
                    id = result.evidenceId,
                    captureId = result.evidenceId,
                    storedUri = File(result.finalPath).toURI().toString(),
                    byteSize = result.byteSize,
                    sha256 = result.sha256,
                    state = EvidenceRecordState.Stored,
                    createdAt = result.storedAt,
                    storedAt = result.storedAt,
                    updatedAt = result.storedAt,
                    mediaType = result.mediaType
                )
            }
            val links = finalJournal.evidence.map { item ->
                FixedCostEvidenceLinkRecord(applicationId, item.evidenceId, item.sortOrder, System.currentTimeMillis())
            }
            if (request.directRegistrationKey == null) {
                dao.applyFixedCostEvidence(report, receipt, application, evidenceRecords, links)
            } else {
                dao.applyDirectFixedCostEvidence(report, receipt, application, evidenceRecords, links)
            }
            failureInjector.after(FixedCostFailurePoint.DatabaseApplied)
            journal.markDatabaseApplied(applicationId)
            journal.complete(applicationId)
        }
    }

    suspend fun saveResult(
        resolver: ContentResolver,
        request: FixedCostEvidenceSaveRequest
    ): FixedCostSaveResult {
        if (request.sources.isEmpty()) return FixedCostSaveResult.MissingEvidence
        if (request.fixedCostType !in fixedCostTypes) return FixedCostSaveResult.InvalidFixedCostType
        if (request.appliedAmount <= 0L) return FixedCostSaveResult.AmountConflict
        val receipt = request.directRegistrationKey?.let {
            dao.getDailyReport(request.dailyReportId)?.let { report ->
                ReceiptRecord(
                    id = request.receiptId, purchaseDate = report.reportDate, capturedDate = report.reportDate,
                    registeredAt = 0L, storeName = fixedCostLabel(request.fixedCostType),
                    totalAmount = request.appliedAmount, taxAmount = 0L, registrationNumber = null,
                    expenseCategory = null, isConfirmed = true, memo = "日報から固定費Evidenceを登録", updatedAt = 0L
                )
            }
        } ?: dao.getReceipt(request.receiptId)
            ?: return FixedCostSaveResult.ReceiptNotFound
        if (request.directRegistrationKey == null && receipt.isConfirmed) return FixedCostSaveResult.ReceiptAlreadyConfirmed
        if (request.appliedAmount != receipt.totalAmount) return FixedCostSaveResult.AmountConflict
        if (request.paymentMethod != paymentMethodFor(request.fixedCostType)) {
            return FixedCostSaveResult.SaveFailure(
                FixedCostSaveException("Payment method is determined by fixed-cost type")
            )
        }
        if (dao.getDailyReport(request.dailyReportId) == null) return FixedCostSaveResult.MissingDailyReport
        if (request.directRegistrationKey == null && dao.getFixedCostReceiptApplicationByReceipt(request.receiptId) != null ||
            dao.getFixedCostReceiptApplicationByReportAndType(request.dailyReportId, request.fixedCostType) != null
        ) return FixedCostSaveResult.AlreadyApplied
        val currentAmount = dao.getDailyReport(request.dailyReportId)!!.fixedCostAmount(request.fixedCostType)
        if (request.directRegistrationKey != null && currentAmount <= 0L) return FixedCostSaveResult.AmountConflict
        if (currentAmount != 0L && currentAmount != receipt.totalAmount) return FixedCostSaveResult.AmountConflict
        return try {
        save(resolver, request)
        FixedCostSaveResult.Success
        } catch (error: FixedCostAmountConflictException) {
            FixedCostSaveResult.AmountConflict
        } catch (error: FixedCostEvidenceFileException) {
            FixedCostSaveResult.SaveFailure(error)
        } catch (error: Exception) {
            FixedCostSaveResult.RecoveryRequired(error)
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        mutex.withLock {
            journal.loadAll().entries.forEach { entry ->
                if (entry.state == FixedCostFinalizationState.DatabaseApplied) {
                    journal.complete(entry.applicationId)
                    return@forEach
                }
                var current = entry
                current.evidence.filter { it.state != FixedCostFinalizationState.AllFilesStored }
                    .forEach { item ->
                        try {
                            val stored = store.promotePending(item.evidenceId, item.mediaType)
                            current = journal.markEvidenceStored(
                                current.applicationId,
                                stored.evidenceId,
                                stored.finalPath,
                                stored.sha256,
                                stored.byteSize
                            )
                        } catch (_: FixedCostEvidenceFileException) {
                            return@forEach
                        }
                    }
                if (current.state != FixedCostFinalizationState.AllFilesStored) return@forEach
                val report = dao.getDailyReport(current.dailyReportId) ?: return@forEach
                val receipt = dao.getReceipt(current.receiptId) ?: return@forEach
                val evidence = current.evidence.map { item ->
                    val file = File(item.finalPath)
                    EvidenceRecord(
                        id = item.evidenceId, captureId = item.evidenceId,
                        storedUri = file.toURI().toString(), byteSize = item.byteSize,
                        sha256 = item.sha256, state = EvidenceRecordState.Stored,
                        createdAt = file.lastModified(), storedAt = file.lastModified(),
                        updatedAt = file.lastModified(), mediaType = item.mediaType
                    )
                }
                val application = FixedCostReceiptApplicationRecord(
                    current.applicationId, current.receiptId, current.dailyReportId,
                    current.fixedCostType, paymentMethodFor(current.fixedCostType), current.createdAt, current.updatedAt
                )
                val links = current.evidence.map { FixedCostEvidenceLinkRecord(current.applicationId, it.evidenceId, it.sortOrder, current.updatedAt) }
                dao.applyFixedCostEvidence(
                    report,
                    receipt,
                    application,
                    evidence,
                    links
                )
                journal.markDatabaseApplied(current.applicationId)
                journal.complete(current.applicationId)
            }
        }
    }
}

private fun DailyReport.withFixedCostAmount(type: String, amount: Long): DailyReport {
    val existing = when (type) {
        FixedCostType.Electricity -> electricityExpense
        FixedCostType.Water -> waterExpense
        FixedCostType.Communication -> communicationExpense
        FixedCostType.Gas -> gasExpense
        else -> throw FixedCostSaveException("Unsupported fixed-cost type")
    }
    if (existing != 0L) throw FixedCostSaveException("Existing fixed-cost amount will not be overwritten")
    return when (type) {
        FixedCostType.Electricity -> copy(electricityExpense = amount)
        FixedCostType.Water -> copy(waterExpense = amount)
        FixedCostType.Communication -> copy(communicationExpense = amount)
        FixedCostType.Gas -> copy(gasExpense = amount)
        else -> error("unreachable")
    }
}

private fun DailyReport.withFixedCostAmountForRecovery(type: String, amount: Long): DailyReport {
    val existing = fixedCostAmount(type)
    return if (existing == 0L) withFixedCostAmount(type, amount)
    else if (existing == amount) this
    else throw FixedCostSaveException("Existing fixed-cost amount conflicts with recovery")
}

private fun DailyReport.fixedCostAmount(type: String): Long = when (type) {
    FixedCostType.Electricity -> electricityExpense
    FixedCostType.Water -> waterExpense
    FixedCostType.Communication -> communicationExpense
    FixedCostType.Gas -> gasExpense
    else -> throw FixedCostSaveException("Unsupported fixed-cost type")
}

object FixedCostType {
    const val Electricity = "electricity"
    const val Water = "water"
    const val Communication = "communication"
    const val Gas = "gas"
}

private val fixedCostTypes = setOf(
    FixedCostType.Electricity,
    FixedCostType.Water,
    FixedCostType.Communication,
    FixedCostType.Gas
)

private fun paymentMethodFor(fixedCostType: String): String = when (fixedCostType) {
    FixedCostType.Electricity, FixedCostType.Water, FixedCostType.Communication, FixedCostType.Gas ->
        requireNotNull(fixedCostPaymentMethodOrNull(fixedCostType))
    else -> throw FixedCostSaveException("Unsupported fixed-cost type")
}

private fun fixedCostLabel(fixedCostType: String): String = when (fixedCostType) {
    FixedCostType.Electricity -> "電気代"
    FixedCostType.Water -> "水道代"
    FixedCostType.Communication -> "通信費"
    FixedCostType.Gas -> "ガス代"
    else -> "固定費"
}

open class FixedCostSaveException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class FixedCostAmountConflictException(message: String) : FixedCostSaveException(message)
