package com.warun.accounting.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceRecoveryNoticeTest {
    @Test
    fun failureIsNotifiedOnceWhileUnresolvedStateRemainsVisible() {
        val store = InMemoryAcknowledgementStore()
        val controller = EvidenceRecoveryNoticeController(store)

        val first = controller.evaluate(listOf("recovery:capture-1", "quarantine:capture-2"))
        assertEquals(2, first.issueCount)
        assertTrue(first.shouldNotify)

        val dismissed = controller.acknowledge(first)
        assertFalse(dismissed.shouldNotify)
        assertEquals(2, dismissed.issueCount)

        val nextLaunch = controller.evaluate(listOf("quarantine:capture-2", "recovery:capture-1"))
        assertEquals(2, nextLaunch.issueCount)
        assertFalse(nextLaunch.shouldNotify)

        val newFailure = controller.evaluate(
            listOf("recovery:capture-1", "quarantine:capture-2", "recovery:capture-3")
        )
        assertEquals(3, newFailure.issueCount)
        assertTrue(newFailure.shouldNotify)
    }

    @Test
    fun recoveryAndQuarantineForSameCaptureAreOneUserFacingIssue() {
        val result = EvidenceRecoveryResult(
            completedCaptureIds = emptyList(),
            failures = listOf(
                EvidenceRecoveryFailure("capture-1", IllegalStateException("failure"))
            ),
            quarantinedCaptureIds = setOf("capture-1", "capture-2"),
            unidentifiedQuarantinedCount = 1
        )

        assertEquals(
            setOf("capture:capture-1", "capture:capture-2", "capture:unknown:0"),
            result.noticeIssueKeys().toSet()
        )
    }

    private class InMemoryAcknowledgementStore : EvidenceRecoveryAcknowledgementStore {
        private var signature: String? = null
        override fun acknowledgedSignature(): String? = signature
        override fun acknowledge(signature: String) {
            this.signature = signature
        }
    }
}
