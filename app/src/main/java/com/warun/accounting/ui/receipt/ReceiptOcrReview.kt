package com.warun.accounting.ui.receipt

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.ocr.parser.ReceiptCandidateConfidence
import com.warun.accounting.ocr.parser.ReceiptParseResult
import com.warun.accounting.ui.viewmodel.ExpenseInput
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

    fun canApplyWithExisting(existing: ExpenseInput?): Boolean {
        if (existing == null) return canApply
        val supplierReady = if (existing.supplierName.isNotBlank()) {
            true
        } else {
            supplierError == null && supplierConfirmed
        }
        val dateReady = if (existing.expenseDate.isNotBlank()) {
            ReceiptOcrReviewValidator.normalizeDate(existing.expenseDate) != null
        } else {
            purchaseDateError == null
        }
        val amountReady = if (existing.amount.isNotBlank()) {
            ReceiptOcrReviewValidator.normalizeAmount(existing.amount) != null
        } else {
            totalAmountError == null
        }
        return supplierReady && dateReady && amountReady
    }

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

enum class ReceiptOcrMergeAction {
    FillFromOcr,
    KeepCurrent,
    Match
}

data class ReceiptOcrFieldMerge(
    val label: String,
    val currentValue: String,
    val ocrValue: String,
    val action: ReceiptOcrMergeAction
)

data class ReceiptOcrMergePlan(
    val mergedExpense: ExpenseInput,
    val fields: List<ReceiptOcrFieldMerge>
) {
    fun displayLines(): List<String> = fields.map { field ->
        val detail = when (field.action) {
            ReceiptOcrMergeAction.FillFromOcr ->
                "OCR値を反映：${field.ocrValue}"
            ReceiptOcrMergeAction.Match ->
                "現在値を維持（OCR結果と一致）：${field.currentValue}"
            ReceiptOcrMergeAction.KeepCurrent -> when {
                field.currentValue.isBlank() -> "空欄を維持（OCR候補なし）"
                field.ocrValue.isBlank() -> "現在値を維持：${field.currentValue}（OCR候補なし）"
                else -> "現在値を維持：${field.currentValue}（OCR候補：${field.ocrValue}）"
            }
        }
        "${field.label}：$detail"
    }
}

fun planReceiptOcrMerge(
    current: ExpenseInput,
    result: ReceiptOcrApplyResult
): ReceiptOcrMergePlan {
    fun field(
        label: String,
        currentValue: String,
        ocrValue: String,
        equivalent: (String, String) -> Boolean = { first, second -> first.trim() == second.trim() }
    ): ReceiptOcrFieldMerge {
        val action = when {
            currentValue.isBlank() && ocrValue.isNotBlank() -> ReceiptOcrMergeAction.FillFromOcr
            currentValue.isNotBlank() && ocrValue.isNotBlank() && equivalent(currentValue, ocrValue) ->
                ReceiptOcrMergeAction.Match
            else -> ReceiptOcrMergeAction.KeepCurrent
        }
        return ReceiptOcrFieldMerge(label, currentValue, ocrValue, action)
    }

    val supplier = field("支払先", current.supplierName, result.supplierName)
    val date = field("支出日", current.expenseDate, result.expenseDate) { first, second ->
        ReceiptOcrReviewValidator.normalizeDate(first) == ReceiptOcrReviewValidator.normalizeDate(second)
    }
    val amount = field("金額", current.amount, result.amount) { first, second ->
        ReceiptOcrReviewValidator.normalizeAmount(first) == ReceiptOcrReviewValidator.normalizeAmount(second)
    }
    val fields = listOf(supplier, date, amount)
    fun ReceiptOcrFieldMerge.mergedValue(): String =
        if (action == ReceiptOcrMergeAction.FillFromOcr) ocrValue else currentValue
    return ReceiptOcrMergePlan(
        mergedExpense = current.copy(
            supplierName = supplier.mergedValue(),
            expenseDate = date.mergedValue(),
            amount = amount.mergedValue()
        ),
        fields = fields
    )
}

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
