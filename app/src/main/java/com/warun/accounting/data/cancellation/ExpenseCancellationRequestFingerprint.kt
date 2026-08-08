package com.warun.accounting.data.cancellation

import com.warun.accounting.data.local.ExpenseCancellationRecord
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class ExpenseCancellationRequestFingerprintInput(
    val expenseId: String,
    val expectedExpenseUpdatedAt: Long,
    val originalPurchaseTransactionId: String,
    val prepaidAccountId: String,
    val amount: Long,
    val purchaseDate: String,
    val cancellationDate: String,
    val reason: String?
)

data class NonPrepaidExpenseCancellationRequestFingerprintInput(
    val expenseId: String,
    val expectedExpenseUpdatedAt: Long,
    val paymentMethod: String,
    val amount: Long,
    val expenseDate: String,
    val cancellationDate: String,
    val reason: String?
)

enum class ExpenseCancellationValidationFailure {
    InvalidOperationKey,
    InvalidIdentifier,
    InvalidFingerprint,
    InvalidTimestamp,
    InvalidAmount,
    InvalidDate,
    SamePurchaseAndReversal,
    ReasonTooLong,
    UnnormalizedReason
}

class ExpenseCancellationValidationException(
    val failure: ExpenseCancellationValidationFailure
) : IllegalArgumentException(failure.name)

object ExpenseCancellationRules {
    const val MaxReasonLength = 200

    private val OperationKeyPattern = Regex(
        "^expense-cancel:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-" +
            "[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    )
    private val FingerprintPattern = Regex("^[0-9a-f]{64}$")

    fun validateOperationKey(operationKey: String) {
        if (!OperationKeyPattern.matches(operationKey)) {
            fail(ExpenseCancellationValidationFailure.InvalidOperationKey)
        }
    }

    fun normalizeReason(reason: String?): String? =
        reason
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFC).trim() }
            ?.takeIf { it.isNotEmpty() }

    fun canonicalReason(reason: String?): String? =
        normalizeReason(reason).also {
            if ((it?.length ?: 0) > MaxReasonLength) {
                fail(ExpenseCancellationValidationFailure.ReasonTooLong)
            }
        }

    fun validateRecord(record: ExpenseCancellationRecord) {
        validateOperationKey(record.operationKey)
        canonicalIdentifier(record.expenseId)
        val purchaseId = record.originalPurchaseTransactionId
        val reversalId = record.reversalTransactionId
        if ((purchaseId == null) != (reversalId == null)) {
            fail(ExpenseCancellationValidationFailure.InvalidIdentifier)
        }
        purchaseId?.let(::canonicalIdentifier)
        reversalId?.let(::canonicalIdentifier)
        if (!FingerprintPattern.matches(record.requestFingerprint)) {
            fail(ExpenseCancellationValidationFailure.InvalidFingerprint)
        }
        if (purchaseId != null && purchaseId == reversalId) {
            fail(ExpenseCancellationValidationFailure.SamePurchaseAndReversal)
        }
        canonicalDate(record.cancellationDate)
        if (record.cancelledAt < 0L) {
            fail(ExpenseCancellationValidationFailure.InvalidTimestamp)
        }
        if (record.reason != canonicalReason(record.reason)) {
            fail(ExpenseCancellationValidationFailure.UnnormalizedReason)
        }
    }

    internal fun canonicalIdentifier(value: String): String {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        if (normalized.isEmpty()) {
            fail(ExpenseCancellationValidationFailure.InvalidIdentifier)
        }
        return normalized
    }

    internal fun canonicalDate(value: String): String = try {
        LocalDate.parse(value.trim()).toString()
    } catch (_: DateTimeParseException) {
        fail(ExpenseCancellationValidationFailure.InvalidDate)
    }

    internal fun fail(failure: ExpenseCancellationValidationFailure): Nothing {
        throw ExpenseCancellationValidationException(failure)
    }
}

object ExpenseCancellationRequestFingerprint {
    private const val PrepaidCanonicalSchemaVersion = 1
    private const val NonPrepaidCanonicalSchemaVersion = 2
    private val HexDigits = "0123456789abcdef".toCharArray()

    fun create(input: ExpenseCancellationRequestFingerprintInput): String {
        val canonical = canonicalize(input)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PrepaidCanonicalSchemaVersion)
                output.writeString(canonical.expenseId)
                output.writeLong(canonical.expectedExpenseUpdatedAt)
                output.writeString(canonical.originalPurchaseTransactionId)
                output.writeString(canonical.prepaidAccountId)
                output.writeLong(canonical.amount)
                output.writeString(canonical.purchaseDate)
                output.writeString(canonical.cancellationDate)
                output.writeNullableString(canonical.reason)
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .toLowerHex()
    }

    fun createNonPrepaid(
        input: NonPrepaidExpenseCancellationRequestFingerprintInput
    ): String {
        val canonical = canonicalizeNonPrepaid(input)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(NonPrepaidCanonicalSchemaVersion)
                output.writeString(canonical.expenseId)
                output.writeLong(canonical.expectedExpenseUpdatedAt)
                output.writeString(canonical.paymentMethod)
                output.writeLong(canonical.amount)
                output.writeString(canonical.expenseDate)
                output.writeString(canonical.cancellationDate)
                output.writeNullableString(canonical.reason)
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .toLowerHex()
    }

    internal fun canonicalize(
        input: ExpenseCancellationRequestFingerprintInput
    ): CanonicalExpenseCancellationRequest = CanonicalExpenseCancellationRequest(
        expenseId = ExpenseCancellationRules.canonicalIdentifier(input.expenseId),
        expectedExpenseUpdatedAt = input.expectedExpenseUpdatedAt.also {
            if (it < 0L) {
                ExpenseCancellationRules.fail(
                    ExpenseCancellationValidationFailure.InvalidTimestamp
                )
            }
        },
        originalPurchaseTransactionId = ExpenseCancellationRules.canonicalIdentifier(
            input.originalPurchaseTransactionId
        ),
        prepaidAccountId = ExpenseCancellationRules.canonicalIdentifier(
            input.prepaidAccountId
        ),
        amount = input.amount.also {
            if (it <= 0L) {
                ExpenseCancellationRules.fail(
                    ExpenseCancellationValidationFailure.InvalidAmount
                )
            }
        },
        purchaseDate = ExpenseCancellationRules.canonicalDate(input.purchaseDate),
        cancellationDate = ExpenseCancellationRules.canonicalDate(input.cancellationDate),
        reason = ExpenseCancellationRules.canonicalReason(input.reason)
    )

    internal fun canonicalizeNonPrepaid(
        input: NonPrepaidExpenseCancellationRequestFingerprintInput
    ): CanonicalNonPrepaidExpenseCancellationRequest =
        CanonicalNonPrepaidExpenseCancellationRequest(
            expenseId = ExpenseCancellationRules.canonicalIdentifier(input.expenseId),
            expectedExpenseUpdatedAt = input.expectedExpenseUpdatedAt.also {
                if (it < 0L) {
                    ExpenseCancellationRules.fail(
                        ExpenseCancellationValidationFailure.InvalidTimestamp
                    )
                }
            },
            paymentMethod = ExpenseCancellationRules.canonicalIdentifier(input.paymentMethod),
            amount = input.amount.also {
                if (it <= 0L) {
                    ExpenseCancellationRules.fail(
                        ExpenseCancellationValidationFailure.InvalidAmount
                    )
                }
            },
            expenseDate = ExpenseCancellationRules.canonicalDate(input.expenseDate),
            cancellationDate = ExpenseCancellationRules.canonicalDate(input.cancellationDate),
            reason = ExpenseCancellationRules.canonicalReason(input.reason)
        )

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun ByteArray.toLowerHex(): String {
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = HexDigits[value ushr 4]
            result[index * 2 + 1] = HexDigits[value and 0x0f]
        }
        return String(result)
    }
}

internal data class CanonicalExpenseCancellationRequest(
    val expenseId: String,
    val expectedExpenseUpdatedAt: Long,
    val originalPurchaseTransactionId: String,
    val prepaidAccountId: String,
    val amount: Long,
    val purchaseDate: String,
    val cancellationDate: String,
    val reason: String?
)

internal data class CanonicalNonPrepaidExpenseCancellationRequest(
    val expenseId: String,
    val expectedExpenseUpdatedAt: Long,
    val paymentMethod: String,
    val amount: Long,
    val expenseDate: String,
    val cancellationDate: String,
    val reason: String?
)
