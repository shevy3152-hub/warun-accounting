package com.warun.accounting.ocr.parser

data class ReceiptLine(
    val index: Int,
    val original: String,
    val normalized: String
)

enum class ReceiptCandidateConfidence {
    High,
    Medium,
    Low
}

data class ReceiptCandidateEvidence(
    val lines: List<ReceiptLine>,
    val reason: String
)

data class ReceiptStoreCandidate(
    val normalizedName: String,
    val originalText: String,
    val branchName: String?,
    val priority: Int,
    val confidence: ReceiptCandidateConfidence,
    val evidence: ReceiptCandidateEvidence
) {
    val displayName: String
        get() = if (branchName.isNullOrBlank()) normalizedName else "$normalizedName（$branchName）"
}

data class ReceiptDateTimeCandidate(
    val normalizedDate: String,
    val normalizedTime: String?,
    val originalText: String,
    val priority: Int,
    val confidence: ReceiptCandidateConfidence,
    val evidence: ReceiptCandidateEvidence
) {
    val normalizedValue: String
        get() = normalizedTime?.let { "$normalizedDate $it" } ?: normalizedDate
}

data class ReceiptAmountCandidate(
    val amount: Long,
    val originalText: String,
    val priority: Int,
    val confidence: ReceiptCandidateConfidence,
    val evidence: ReceiptCandidateEvidence
)

data class ReceiptParseResult(
    val rawText: String,
    val lines: List<ReceiptLine>,
    val storeCandidates: List<ReceiptStoreCandidate>,
    val dateTimeCandidates: List<ReceiptDateTimeCandidate>,
    val totalAmountCandidates: List<ReceiptAmountCandidate>
) {
    val bestStore: ReceiptStoreCandidate? get() = storeCandidates.firstOrNull()
    val bestDateTime: ReceiptDateTimeCandidate? get() = dateTimeCandidates.firstOrNull()
    val bestTotalAmount: ReceiptAmountCandidate? get() = totalAmountCandidates.firstOrNull()
}
