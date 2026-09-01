package com.warun.accounting.data.fixedcost

import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedCostContractsTest {
    @Test
    fun amountStateDistinguishesEmptySameAndConflict() {
        assertEquals(ExistingAmountState.EMPTY, existingAmountState(0L, 41_617L))
        assertEquals(ExistingAmountState.SAME, existingAmountState(41_617L, 41_617L))
        assertEquals(ExistingAmountState.CONFLICT, existingAmountState(41_616L, 41_617L))
    }

    @Test
    fun paymentMethodIsDerivedFromFixedCostType() {
        assertEquals("現金", fixedCostPaymentMethodOrNull("electricity"))
        assertEquals("現金", fixedCostPaymentMethodOrNull("water"))
        assertEquals("現金", fixedCostPaymentMethodOrNull("communication"))
        assertEquals("銀行振込", fixedCostPaymentMethodOrNull("gas"))
        assertEquals(null, fixedCostPaymentMethodOrNull("food"))
    }

    @Test
    fun resultTypeHasExplicitBusinessOutcomes() {
        val results = listOf<FixedCostSaveResult>(
            FixedCostSaveResult.Success,
            FixedCostSaveResult.MissingDailyReport,
            FixedCostSaveResult.MissingEvidence,
            FixedCostSaveResult.AlreadyApplied,
            FixedCostSaveResult.AmountConflict,
            FixedCostSaveResult.ReceiptNotFound,
            FixedCostSaveResult.ReceiptAlreadyConfirmed,
            FixedCostSaveResult.InvalidFixedCostType
        )
        assertEquals(8, results.size)
    }

    @Test
    fun evidenceRegistrationStateDoesNotCountPendingEvidence() {
        val none = FixedCostEvidenceStatus("report", "gas", null, emptyList())
        assertEquals(FixedCostEvidenceRegistrationState.NONE, none.registrationState(0L))
        assertEquals(FixedCostEvidenceRegistrationState.MISSING, none.registrationState(1L))

        val applicationWithoutStoredEvidence = FixedCostEvidenceStatus("report", "gas", "app", emptyList())
        assertEquals(
            FixedCostEvidenceRegistrationState.NEEDS_REVIEW,
            applicationWithoutStoredEvidence.registrationState(1L)
        )

        val stored = EvidenceRecord(
            id = "evidence",
            captureId = "capture",
            storedUri = "file:///evidence.pdf",
            byteSize = 10L,
            sha256 = "a".repeat(64),
            state = EvidenceRecordState.Stored,
            createdAt = 1L,
            storedAt = 2L,
            updatedAt = 2L,
            mediaType = "application/pdf"
        )
        val registered = FixedCostEvidenceStatus("report", "gas", "app", listOf(stored))
        assertEquals(FixedCostEvidenceRegistrationState.REGISTERED, registered.registrationState(1L))
        assertEquals(1, registered.evidence.size)
    }
}
