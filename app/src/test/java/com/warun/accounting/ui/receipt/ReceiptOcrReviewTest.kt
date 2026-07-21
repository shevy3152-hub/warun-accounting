package com.warun.accounting.ui.receipt

import com.warun.accounting.ocr.parser.ReceiptAmountCandidate
import com.warun.accounting.ocr.parser.ReceiptCandidateConfidence
import com.warun.accounting.ocr.parser.ReceiptCandidateEvidence
import com.warun.accounting.ocr.parser.ReceiptDateTimeCandidate
import com.warun.accounting.ocr.parser.ReceiptLine
import com.warun.accounting.ocr.parser.ReceiptParseResult
import com.warun.accounting.ocr.parser.ReceiptStoreCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrReviewTest {
    @Test
    fun amountSymbolsCommasAndFullWidthDigitsAreNormalized() {
        assertEquals(1_540L, ReceiptOcrReviewValidator.normalizeAmount("￥１，５４０円"))
        assertEquals(2_000L, ReceiptOcrReviewValidator.normalizeAmount(" 2,000 円 "))
    }

    @Test
    fun invalidOrNonPositiveAmountsAreRejected() {
        assertNull(ReceiptOcrReviewValidator.normalizeAmount(""))
        assertNull(ReceiptOcrReviewValidator.normalizeAmount("合計千円"))
        assertNull(ReceiptOcrReviewValidator.normalizeAmount("0円"))
        assertNull(ReceiptOcrReviewValidator.normalizeAmount("-100円"))
    }

    @Test
    fun supportedDatesNormalizeToExistingIsoFormat() {
        assertEquals("2026-07-21", ReceiptOcrReviewValidator.normalizeDate("2026/7/21"))
        assertEquals("2026-07-21", ReceiptOcrReviewValidator.normalizeDate("２０２６年７月２１日"))
        assertEquals("2026-07-21", ReceiptOcrReviewValidator.normalizeDate("26-7-21"))
        assertNull(ReceiptOcrReviewValidator.normalizeDate("2026-02-30"))
    }

    @Test
    fun parserCandidatesBecomeEditableInitialValues() {
        val review = ReceiptOcrReviewState.from(parseResult(ReceiptCandidateConfidence.High))

        assertEquals("バロー（岐南店）", review.supplierName)
        assertEquals("2026-07-21", review.purchaseDate)
        assertEquals("1540", review.totalAmount)
        assertTrue(review.canApply)
    }

    @Test
    fun lowConfidenceCandidatesRequireExplicitConfirmation() {
        val initial = ReceiptOcrReviewState.from(parseResult(ReceiptCandidateConfidence.Low))

        assertFalse(initial.canApply)
        val confirmed = initial.copy(
            supplierConfirmed = true,
            purchaseDateConfirmed = true,
            totalAmountConfirmed = true
        )
        assertTrue(confirmed.canApply)
    }

    @Test
    fun missingSupplierCanOnlyApplyAfterExplicitBlankConfirmation() {
        val state = ReceiptOcrReviewState(
            supplierName = "",
            purchaseDate = "2026-07-21",
            totalAmount = "1540",
            supplierConfirmed = false,
            purchaseDateConfirmed = true,
            totalAmountConfirmed = true
        )

        assertFalse(state.canApply)
        assertTrue(state.copy(supplierConfirmed = true).canApply)
    }

    private fun parseResult(confidence: ReceiptCandidateConfidence): ReceiptParseResult {
        val line = ReceiptLine(0, "バロー 岐南店", "バロー 岐南店")
        val evidence = ReceiptCandidateEvidence(listOf(line), "テスト根拠")
        return ReceiptParseResult(
            rawText = line.original,
            lines = listOf(line),
            storeCandidates = listOf(
                ReceiptStoreCandidate("バロー", line.original, "岐南店", 100, confidence, evidence)
            ),
            dateTimeCandidates = listOf(
                ReceiptDateTimeCandidate("2026-07-21", "12:30", "2026/07/21 12:30", 100, confidence, evidence)
            ),
            totalAmountCandidates = listOf(
                ReceiptAmountCandidate(1_540L, "1,540円", 100, confidence, evidence)
            )
        )
    }
}
