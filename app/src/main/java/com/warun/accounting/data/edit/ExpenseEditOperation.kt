package com.warun.accounting.data.edit

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.warun.accounting.data.local.ExpenseEditOperationDao
import com.warun.accounting.data.local.ExpenseEditOperationRecord
import com.warun.accounting.data.local.ExpenseEditOperationStatus
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.util.isSupportedPaymentMethod
import com.warun.accounting.util.normalizePaymentMethod
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

data class ExpenseEditEvidenceToken(
    val evidenceId: String,
    val captureId: String,
    val contentSha256: String
)

data class ExpenseEditRequestFingerprintInput(
    val expenseId: String,
    val paymentMethod: String,
    val prepaidAccountId: String?,
    val amount: Long,
    val date: String,
    val category: String,
    val supplier: String?,
    val memo: String?,
    val receiptId: String?,
    val sourceType: String,
    val evidenceTokens: List<ExpenseEditEvidenceToken> = emptyList()
)

data class ExpenseEditOperationRequest(
    val operationKey: String,
    val fingerprintInput: ExpenseEditRequestFingerprintInput,
    val createdAt: Long
)

data class ExpenseEditOperationCompletion(
    val paymentMethod: String,
    val prepaidAccountId: String?,
    val amount: Long,
    val completedAt: Long
)

data class ExpenseEditOperationExecutionResult(
    val operation: ExpenseEditOperationRecord,
    val wasAlreadyCompleted: Boolean
)

enum class ExpenseEditOperationFailure {
    InvalidOperationKey,
    InvalidExpenseId,
    InvalidRequest,
    OperationConflict,
    OperationAlreadyCompleted,
    OperationStateCorrupted,
    DatabaseFailure
}

class ExpenseEditOperationException(
    val failure: ExpenseEditOperationFailure,
    cause: Throwable? = null
) : IllegalStateException(failure.name, cause)

object ExpenseEditRequestFingerprint {
    private const val CanonicalSchemaVersion = 1
    private val HexDigits = "0123456789abcdef".toCharArray()

    fun create(input: ExpenseEditRequestFingerprintInput): String {
        val canonical = canonicalize(input)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(CanonicalSchemaVersion)
                output.writeString(canonical.expenseId)
                output.writeString(canonical.paymentMethod)
                output.writeNullableString(canonical.prepaidAccountId)
                output.writeLong(canonical.amount)
                output.writeString(canonical.date)
                output.writeString(canonical.category)
                output.writeNullableString(canonical.supplier)
                output.writeNullableString(canonical.memo)
                output.writeNullableString(canonical.receiptId)
                output.writeString(canonical.sourceType)
                output.writeInt(canonical.evidenceTokens.size)
                canonical.evidenceTokens.forEach { token ->
                    output.writeString(token.evidenceId)
                    output.writeString(token.captureId)
                    output.writeString(token.contentSha256)
                }
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .toLowerHex()
    }

    internal fun canonicalize(
        input: ExpenseEditRequestFingerprintInput
    ): CanonicalExpenseEditRequest {
        val expenseId = input.expenseId.normalizedIdentifier()
        if (expenseId.isBlank()) {
            fail(ExpenseEditOperationFailure.InvalidExpenseId)
        }
        val paymentMethod = normalizePaymentMethod(input.paymentMethod)
        if (!isSupportedPaymentMethod(paymentMethod)) {
            fail(ExpenseEditOperationFailure.InvalidRequest)
        }
        val prepaidAccountId = if (paymentMethod == PaymentMethodPrepaid) {
            input.prepaidAccountId?.normalizedIdentifier()?.takeIf { it.isNotBlank() }
                ?: fail(ExpenseEditOperationFailure.InvalidRequest)
        } else {
            null
        }
        if (input.amount <= 0L) {
            fail(ExpenseEditOperationFailure.InvalidRequest)
        }
        val date = try {
            LocalDate.parse(input.date.trim()).toString()
        } catch (_: DateTimeParseException) {
            fail(ExpenseEditOperationFailure.InvalidRequest)
        }
        val category = input.category.normalizedText()
        val sourceType = input.sourceType.normalizedText()
        if (category.isBlank() || sourceType.isBlank()) {
            fail(ExpenseEditOperationFailure.InvalidRequest)
        }
        val evidenceTokens = input.evidenceTokens
            .map { token ->
                CanonicalEvidenceToken(
                    evidenceId = token.evidenceId.normalizedIdentifier(),
                    captureId = token.captureId.normalizedIdentifier(),
                    contentSha256 = token.contentSha256.normalizedIdentifier().lowercase()
                ).also {
                    if (
                        it.evidenceId.isBlank() ||
                        it.captureId.isBlank() ||
                        !it.contentSha256.matches(Regex("[0-9a-f]{64}"))
                    ) {
                        fail(ExpenseEditOperationFailure.InvalidRequest)
                    }
                }
            }
            .sortedWith(
                compareBy(
                    CanonicalEvidenceToken::evidenceId,
                    CanonicalEvidenceToken::captureId,
                    CanonicalEvidenceToken::contentSha256
                )
            )
        return CanonicalExpenseEditRequest(
            expenseId = expenseId,
            paymentMethod = paymentMethod,
            prepaidAccountId = prepaidAccountId,
            amount = input.amount,
            date = date,
            category = category,
            supplier = input.supplier?.normalizedText(),
            memo = input.memo?.normalizedText(),
            receiptId = input.receiptId?.normalizedIdentifier(),
            sourceType = sourceType,
            evidenceTokens = evidenceTokens
        )
    }

    private fun String.normalizedIdentifier(): String =
        Normalizer.normalize(trim(), Normalizer.Form.NFC)

    private fun String.normalizedText(): String =
        Normalizer.normalize(this, Normalizer.Form.NFC)

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

class ExpenseEditOperationExecutor @Inject constructor(
    private val database: WarunDatabase,
    private val operationDao: ExpenseEditOperationDao
) {
    suspend fun execute(
        request: ExpenseEditOperationRequest,
        precondition: suspend () -> Unit = {},
        edit: suspend () -> ExpenseEditOperationCompletion
    ): ExpenseEditOperationExecutionResult {
        val operationKey = request.operationKey.trim()
        if (operationKey.isBlank() || operationKey != request.operationKey) {
            fail(ExpenseEditOperationFailure.InvalidOperationKey)
        }
        val canonical = ExpenseEditRequestFingerprint.canonicalize(request.fingerprintInput)
        val fingerprint = ExpenseEditRequestFingerprint.create(request.fingerprintInput)
        if (request.createdAt < 0L) {
            fail(ExpenseEditOperationFailure.InvalidRequest)
        }
        return try {
            database.withTransaction {
                precondition()
                val existing = operationDao.getByOperationKey(operationKey)
                if (existing != null) {
                    return@withTransaction existingResult(
                        existing = existing,
                        expenseId = canonical.expenseId,
                        fingerprint = fingerprint,
                        canonical = canonical
                    )
                }
                operationDao.insert(
                    ExpenseEditOperationRecord(
                        operationKey = operationKey,
                        expenseId = canonical.expenseId,
                        requestFingerprint = fingerprint,
                        status = ExpenseEditOperationStatus.Started,
                        createdAt = request.createdAt,
                        updatedAt = request.createdAt,
                        completedAt = null,
                        resultPaymentMethod = null,
                        resultPrepaidAccountId = null,
                        resultAmount = null
                    )
                )
                val completion = edit()
                validateCompletion(
                    canonical = canonical,
                    requestCreatedAt = request.createdAt,
                    completion = completion
                )
                val updated = operationDao.markCompleted(
                    operationKey = operationKey,
                    requestFingerprint = fingerprint,
                    completedAt = completion.completedAt,
                    resultPaymentMethod = canonical.paymentMethod,
                    resultPrepaidAccountId = canonical.prepaidAccountId,
                    resultAmount = canonical.amount
                )
                if (updated != 1) {
                    fail(ExpenseEditOperationFailure.OperationStateCorrupted)
                }
                val completed = operationDao.getByOperationKey(operationKey)
                    ?: fail(ExpenseEditOperationFailure.OperationStateCorrupted)
                if (completed.status != ExpenseEditOperationStatus.Completed) {
                    fail(ExpenseEditOperationFailure.OperationStateCorrupted)
                }
                ExpenseEditOperationExecutionResult(
                    operation = completed,
                    wasAlreadyCompleted = false
                )
            }
        } catch (error: ExpenseEditOperationException) {
            throw error
        } catch (error: SQLiteException) {
            throw ExpenseEditOperationException(
                ExpenseEditOperationFailure.DatabaseFailure,
                error
            )
        }
    }

    private fun existingResult(
        existing: ExpenseEditOperationRecord,
        expenseId: String,
        fingerprint: String,
        canonical: CanonicalExpenseEditRequest
    ): ExpenseEditOperationExecutionResult {
        if (
            existing.expenseId != expenseId ||
            existing.requestFingerprint != fingerprint
        ) {
            fail(ExpenseEditOperationFailure.OperationConflict)
        }
        if (existing.status == ExpenseEditOperationStatus.Started) {
            fail(ExpenseEditOperationFailure.OperationStateCorrupted)
        }
        if (
            existing.status != ExpenseEditOperationStatus.Completed ||
            existing.completedAt == null ||
            existing.resultPaymentMethod == null ||
            existing.resultAmount == null ||
            existing.resultPaymentMethod != canonical.paymentMethod ||
            existing.resultPrepaidAccountId != canonical.prepaidAccountId ||
            existing.resultAmount != canonical.amount
        ) {
            fail(ExpenseEditOperationFailure.OperationStateCorrupted)
        }
        return ExpenseEditOperationExecutionResult(
            operation = existing,
            wasAlreadyCompleted = true
        )
    }

    private fun validateCompletion(
        canonical: CanonicalExpenseEditRequest,
        requestCreatedAt: Long,
        completion: ExpenseEditOperationCompletion
    ) {
        val paymentMethod = normalizePaymentMethod(completion.paymentMethod)
        val prepaidAccountId = if (paymentMethod == PaymentMethodPrepaid) {
            completion.prepaidAccountId
                ?.let { Normalizer.normalize(it.trim(), Normalizer.Form.NFC) }
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (
            completion.completedAt < requestCreatedAt ||
            paymentMethod != canonical.paymentMethod ||
            prepaidAccountId != canonical.prepaidAccountId ||
            completion.amount != canonical.amount
        ) {
            fail(ExpenseEditOperationFailure.InvalidRequest)
        }
    }
}

internal data class CanonicalExpenseEditRequest(
    val expenseId: String,
    val paymentMethod: String,
    val prepaidAccountId: String?,
    val amount: Long,
    val date: String,
    val category: String,
    val supplier: String?,
    val memo: String?,
    val receiptId: String?,
    val sourceType: String,
    val evidenceTokens: List<CanonicalEvidenceToken>
)

internal data class CanonicalEvidenceToken(
    val evidenceId: String,
    val captureId: String,
    val contentSha256: String
)

private fun fail(failure: ExpenseEditOperationFailure): Nothing {
    throw ExpenseEditOperationException(failure)
}
