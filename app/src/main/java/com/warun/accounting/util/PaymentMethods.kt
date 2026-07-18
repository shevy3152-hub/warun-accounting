package com.warun.accounting.util

const val PaymentMethodCash = "現金"
const val PaymentMethodCredit = "クレジット"
const val PaymentMethodElectronicMoney = "電子マネー"
const val PaymentMethodCreditPurchase = "掛け"

val paymentMethodOptions = listOf(
    PaymentMethodCash,
    PaymentMethodCredit,
    PaymentMethodElectronicMoney,
    PaymentMethodCreditPurchase
)

fun normalizePaymentMethod(value: String?): String {
    val normalized = value.orEmpty().trim()
    val key = normalized
        .lowercase()
        .replace(" ", "")
        .replace("　", "")
        .replace("-", "")
        .replace("_", "")

    return when (key) {
        "現金" -> PaymentMethodCash
        "クレジット", "クレジットカード", "カード", "visa", "mastercard", "master", "jcb" -> PaymentMethodCredit
        "電子マネー", "paypay", "楽天ペイ", "d払い", "aupay", "qr決済", "suica", "pasmo", "id", "quicpay" -> PaymentMethodElectronicMoney
        "掛け", "掛け払い", "ツケ", "後払い" -> PaymentMethodCreditPurchase
        else -> PaymentMethodCash
    }
}

fun isSupportedPaymentMethod(value: String): Boolean = value in paymentMethodOptions