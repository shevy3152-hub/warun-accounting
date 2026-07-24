package com.warun.accounting.evidence

import java.security.MessageDigest

data class EvidenceRecoveryNotice(
    val issueCount: Int = 0,
    val signature: String? = null,
    val shouldNotify: Boolean = false
)

interface EvidenceRecoveryAcknowledgementStore {
    fun acknowledgedSignature(): String?
    fun acknowledge(signature: String)
}

class EvidenceRecoveryNoticeController(
    private val acknowledgementStore: EvidenceRecoveryAcknowledgementStore
) {
    fun evaluate(issueKeys: Collection<String>): EvidenceRecoveryNotice {
        val normalized = issueKeys.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (normalized.isEmpty()) return EvidenceRecoveryNotice()
        val signature = MessageDigest.getInstance("SHA-256")
            .digest(normalized.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return EvidenceRecoveryNotice(
            issueCount = normalized.size,
            signature = signature,
            shouldNotify = acknowledgementStore.acknowledgedSignature() != signature
        )
    }

    fun acknowledge(notice: EvidenceRecoveryNotice): EvidenceRecoveryNotice {
        notice.signature?.let(acknowledgementStore::acknowledge)
        return notice.copy(shouldNotify = false)
    }
}

fun EvidenceRecoveryResult.noticeIssueKeys(): List<String> = buildList {
    failures.forEach { add("capture:${it.captureId}") }
    quarantinedCaptureIds.forEach { add("capture:$it") }
    repeat(unidentifiedQuarantinedCount) { index ->
        add("capture:unknown:$index")
    }
}.distinct()
