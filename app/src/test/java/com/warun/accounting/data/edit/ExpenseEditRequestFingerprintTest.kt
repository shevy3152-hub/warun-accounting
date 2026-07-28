package com.warun.accounting.data.edit

import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodPrepaid
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseEditRequestFingerprintTest {
    @Test
    fun identicalLogicalRequestsHaveStableFingerprint() {
        val first = input(
            evidenceTokens = listOf(
                token("evidence-b", "capture-b", "b".repeat(64)),
                token("evidence-a", "capture-a", "a".repeat(64))
            )
        )
        val reordered = first.copy(
            paymentMethod = " prepaid ",
            prepaidAccountId = " prepaid-majica ",
            date = "2026-07-28",
            evidenceTokens = first.evidenceTokens.reversed()
        )

        assertEquals(
            ExpenseEditRequestFingerprint.create(first),
            ExpenseEditRequestFingerprint.create(reordered)
        )
    }

    @Test
    fun everyBusinessFieldAndEvidenceTokenAffectsFingerprint() {
        val original = input()
        val fingerprint = ExpenseEditRequestFingerprint.create(original)
        listOf(
            original.copy(expenseId = "expense-2"),
            original.copy(paymentMethod = PaymentMethodCash, prepaidAccountId = null),
            original.copy(prepaidAccountId = "prepaid-au-pay"),
            original.copy(amount = 501L),
            original.copy(date = "2026-07-29"),
            original.copy(category = "food_purchase"),
            original.copy(supplier = "別店舗"),
            original.copy(memo = "別メモ"),
            original.copy(receiptId = "receipt-2"),
            original.copy(sourceType = "receipt"),
            original.copy(
                evidenceTokens = listOf(
                    token("evidence-1", "capture-1", "d".repeat(64))
                )
            )
        ).forEach { changed ->
            assertNotEquals(fingerprint, ExpenseEditRequestFingerprint.create(changed))
        }
    }

    @Test
    fun nullAndEmptyRemainDistinctAndDelimiterCannotCollide() {
        val nullSupplier = input(supplier = null)
        val emptySupplier = input(supplier = "")
        assertNotEquals(
            ExpenseEditRequestFingerprint.create(nullSupplier),
            ExpenseEditRequestFingerprint.create(emptySupplier)
        )
        assertNotEquals(
            ExpenseEditRequestFingerprint.create(
                input(category = "a|b", supplier = "c")
            ),
            ExpenseEditRequestFingerprint.create(
                input(category = "a", supplier = "b|c")
            )
        )
    }

    @Test
    fun unicodeAndLocaleDoNotChangeFingerprint() {
        val previous = Locale.getDefault()
        try {
            val decomposed = input(supplier = "Cafe\u0301")
            Locale.setDefault(Locale.JAPAN)
            val japaneseLocale = ExpenseEditRequestFingerprint.create(decomposed)
            Locale.setDefault(Locale("tr", "TR"))
            val turkishLocale = ExpenseEditRequestFingerprint.create(
                decomposed.copy(supplier = "Café")
            )
            assertEquals(japaneseLocale, turkishLocale)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun nonPrepaidAccountIdIsCanonicalizedToNull() {
        val withoutAccount = input(
            paymentMethod = PaymentMethodCash,
            prepaidAccountId = null
        )
        val staleAccount = withoutAccount.copy(prepaidAccountId = "prepaid-majica")
        assertEquals(
            ExpenseEditRequestFingerprint.create(withoutAccount),
            ExpenseEditRequestFingerprint.create(staleAccount)
        )
    }

    @Test
    fun invalidIdentifiersAmountsDatesAndEvidenceHashesAreRejected() {
        listOf(
            input(expenseId = ""),
            input(amount = 0L),
            input(date = "2026-02-30"),
            input(prepaidAccountId = null),
            input(
                evidenceTokens = listOf(token("evidence-1", "capture-1", "not-a-sha"))
            )
        ).forEach { invalid ->
            val error = runCatching {
                ExpenseEditRequestFingerprint.create(invalid)
            }.exceptionOrNull()
            assertTrue(error is ExpenseEditOperationException)
            assertTrue(
                (error as ExpenseEditOperationException).failure in
                    setOf(
                        ExpenseEditOperationFailure.InvalidExpenseId,
                        ExpenseEditOperationFailure.InvalidRequest
                    )
            )
        }
    }

    private fun input(
        expenseId: String = "expense-1",
        paymentMethod: String = PaymentMethodPrepaid,
        prepaidAccountId: String? = "prepaid-majica",
        amount: Long = 500L,
        date: String = "2026-07-28",
        category: String = "other_expense",
        supplier: String? = "テスト商店",
        memo: String? = "編集メモ",
        receiptId: String? = null,
        sourceType: String = "manual",
        evidenceTokens: List<ExpenseEditEvidenceToken> = listOf(
            token("evidence-1", "capture-1", "a".repeat(64))
        )
    ) = ExpenseEditRequestFingerprintInput(
        expenseId = expenseId,
        paymentMethod = paymentMethod,
        prepaidAccountId = prepaidAccountId,
        amount = amount,
        date = date,
        category = category,
        supplier = supplier,
        memo = memo,
        receiptId = receiptId,
        sourceType = sourceType,
        evidenceTokens = evidenceTokens
    )

    private fun token(
        evidenceId: String,
        captureId: String,
        sha256: String
    ) = ExpenseEditEvidenceToken(evidenceId, captureId, sha256)
}
