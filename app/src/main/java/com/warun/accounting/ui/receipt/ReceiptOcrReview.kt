package com.warun.accounting.ui.receipt

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.ocr.parser.ReceiptCandidateConfidence
import com.warun.accounting.ocr.parser.ReceiptParseResult
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

data class ReceiptOcrReviewState(
    val supplierName: String,
    val purchaseDate: String,
    val totalAmount: String,
    val supplierConfirmed: Boolean,
    val purchaseDateConfirmed: Boolean,
    val totalAmountConfirmed: Boolean
) {
    val supplierError: String?
        get() = when {
            supplierName.isBlank() && !supplierConfirmed ->
                "支払先を入力するか、空欄で反映することを確認してください"
            !supplierConfirmed -> "低confidence候補を確認してください"
            else -> null
        }

    val normalizedPurchaseDate: String?
        get() = ReceiptOcrReviewValidator.normalizeDate(purchaseDate)

    val purchaseDateError: String?
        get() = when {
            purchaseDate.isBlank() -> "購入日を入力してください"
            normalizedPurchaseDate == null -> "購入日を正しい日付で入力してください"
            !purchaseDateConfirmed -> "低confidence候補を確認してください"
            else -> null
        }

    val normalizedTotalAmount: Long?
        get() = ReceiptOcrReviewValidator.normalizeAmount(totalAmount)

    val totalAmountError: String?
        get() = when {
            totalAmount.isBlank() -> "合計金額を入力してください"
            normalizedTotalAmount == null -> "合計金額は1円以上の整数で入力してください"
            !totalAmountConfirmed -> "低confidence候補を確認してください"
            else -> null
        }

    val canApply: Boolean
        get() = supplierError == null &&
            purchaseDateError == null &&
            totalAmountError == null &&
            supplierConfirmed

    companion object {
        fun from(parseResult: ReceiptParseResult): ReceiptOcrReviewState {
            val store = parseResult.bestStore
            val date = parseResult.bestDateTime
            val amount = parseResult.bestTotalAmount
            return ReceiptOcrReviewState(
                supplierName = store?.displayName.orEmpty(),
                purchaseDate = date?.normalizedDate.orEmpty(),
                totalAmount = amount?.amount?.toString().orEmpty(),
                supplierConfirmed = store != null && store.confidence != ReceiptCandidateConfidence.Low,
                purchaseDateConfirmed = date != null && date.confidence != ReceiptCandidateConfidence.Low,
                totalAmountConfirmed = amount != null && amount.confidence != ReceiptCandidateConfidence.Low
            )
        }
    }
}

data class ReceiptOcrApplyResult(
    val capture: ReceiptCaptureResult,
    val supplierName: String,
    val expenseDate: String,
    val amount: String
)

object ReceiptOcrReviewValidator {
    fun normalizeAmount(value: String): Long? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[,\\s円¥￥]"), "")
        if (normalized.isBlank() || normalized.any { !it.isDigit() }) return null
        return normalized.toLongOrNull()?.takeIf { it > 0L }
    }

    fun normalizeDate(value: String): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        val match = DatePatterns.firstNotNullOfOrNull { it.matchEntire(normalized) } ?: return null
        return try {
            val rawYear = match.groupValues[1].toInt()
            val year = if (rawYear < 100) 2_000 + rawYear else rawYear
            LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[3].toInt()).toString()
        } catch (_: DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private val DatePatterns = listOf(
        Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})"),
        Regex("(\\d{4})年\\s*(\\d{1,2})月\\s*(\\d{1,2})日"),
        Regex("(\\d{2})[/-](\\d{1,2})[/-](\\d{1,2})")
    )
}
