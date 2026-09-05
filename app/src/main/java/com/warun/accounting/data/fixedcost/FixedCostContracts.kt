package com.warun.accounting.data.fixedcost

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.FixedCostEvidenceLinkRecord
import com.warun.accounting.data.local.FixedCostReceiptApplicationRecord
import com.warun.accounting.data.local.ReceiptRecord

enum class ExistingAmountState {
    EMPTY,
    SAME,
    CONFLICT
}

fun fixedCostPaymentMethodOrNull(fixedCostType: String): String? = when (fixedCostType) {
    "electricity", "water", "communication" -> "現金"
    "gas" -> "銀行振込"
    else -> null
}

fun existingAmountState(currentAmount: Long, receiptAmount: Long): ExistingAmountState = when {
    currentAmount == 0L -> ExistingAmountState.EMPTY
    currentAmount == receiptAmount -> ExistingAmountState.SAME
    else -> ExistingAmountState.CONFLICT
}

data class FixedCostDetailSnapshot(
    val receipt: ReceiptRecord?,
    val dailyReport: DailyReport?,
    val fixedCostType: String,
    val currentAmount: Long?,
    val existingAmountState: ExistingAmountState?,
    val application: FixedCostReceiptApplicationRecord?,
    val evidenceLinks: List<FixedCostEvidenceLinkRecord>,
    val evidence: List<EvidenceRecord>,
    val alreadyApplied: Boolean,
    val dailyReportMissing: Boolean
)

data class FixedCostEvidenceStatus(
    val dailyReportId: String,
    val fixedCostType: String,
    val applicationId: String?,
    val evidence: List<EvidenceRecord>
)

fun fixedCostEvidenceCount(
    statuses: List<FixedCostEvidenceStatus>,
    dailyReportId: String,
    fixedCostType: String
): Int = statuses.firstOrNull {
    it.dailyReportId == dailyReportId && it.fixedCostType == fixedCostType
}?.evidence?.size ?: 0

fun fixedCostEvidenceButtonLabel(storedEvidenceCount: Int): String =
    if (storedEvidenceCount > 0) "✓ 保存済み（再編集）" else "証憑追加"

enum class FixedCostEvidenceRegistrationState {
    NONE,
    MISSING,
    REGISTERED,
    NEEDS_REVIEW
}

fun FixedCostEvidenceStatus.registrationState(amount: Long): FixedCostEvidenceRegistrationState = when {
    applicationId != null && evidence.isNotEmpty() -> FixedCostEvidenceRegistrationState.REGISTERED
    applicationId != null -> FixedCostEvidenceRegistrationState.NEEDS_REVIEW
    amount > 0L -> FixedCostEvidenceRegistrationState.MISSING
    else -> FixedCostEvidenceRegistrationState.NONE
}

sealed interface FixedCostSaveResult {
    data object Success : FixedCostSaveResult
    data object MissingDailyReport : FixedCostSaveResult
    data object MissingEvidence : FixedCostSaveResult
    data object AlreadyApplied : FixedCostSaveResult
    data object AmountConflict : FixedCostSaveResult
    data object ReceiptNotFound : FixedCostSaveResult
    data object ReceiptAlreadyConfirmed : FixedCostSaveResult
    data object InvalidFixedCostType : FixedCostSaveResult
    data class SaveFailure(val cause: Throwable) : FixedCostSaveResult
    data class RecoveryRequired(val cause: Throwable) : FixedCostSaveResult
}
