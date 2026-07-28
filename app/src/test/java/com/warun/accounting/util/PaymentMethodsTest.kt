package com.warun.accounting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentMethodsTest {
    @Test
    fun prepaidIsSupportedButNeverCash() {
        assertEquals(PaymentMethodPrepaid, normalizePaymentMethod("プリペイド"))
        assertEquals(PaymentMethodPrepaid, normalizePaymentMethod("prepaid"))
        assertTrue(isSupportedPaymentMethod(PaymentMethodPrepaid))
        assertFalse(isCashPaymentMethod(PaymentMethodPrepaid))
    }

    @Test
    fun unknownAndBlankValuesDoNotFallBackToCash() {
        assertEquals("未知の決済", normalizePaymentMethod(" 未知の決済 "))
        assertEquals("", normalizePaymentMethod(null))
        assertFalse(isSupportedPaymentMethod(normalizePaymentMethod("未知の決済")))
        assertFalse(isCashPaymentMethod("未知の決済"))
        assertFalse(isCashPaymentMethod(null))
    }

    @Test
    fun existingPaymentMethodsRemainSupported() {
        listOf(
            PaymentMethodCash,
            PaymentMethodCredit,
            PaymentMethodElectronicMoney,
            PaymentMethodCreditPurchase
        ).forEach { value ->
            assertEquals(value, normalizePaymentMethod(value))
            assertTrue(isSupportedPaymentMethod(value))
        }
        assertTrue(isCashPaymentMethod(PaymentMethodCash))
    }
}
