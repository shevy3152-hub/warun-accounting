package com.warun.accounting.util

const val PaymentMethodCash = "現金"
const val PaymentMethodCredit = "クレジット"
const val PaymentMethodElectronicMoney = "電子マネー"
const val PaymentMethodCreditPurchase = "掛け"
const val PaymentMethodPrepaid = "プリペイド"

val paymentMethodOptions = listOf(
    PaymentMethodCash,
    PaymentMethodCredit,
    PaymentMethodElectronicMoney,
    PaymentMethodCreditPurchase,
    PaymentMethodPrepaid
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
        "プリペイド", "prepaid" -> PaymentMethodPrepaid
        else -> normalized
    }
}

fun isSupportedPaymentMethod(value: String): Boolean = value in paymentMethodOptions

fun isCashPaymentMethod(value: String?): Boolean =
    normalizePaymentMethod(value) == PaymentMethodCash

fun paymentMethodDisplayName(value: String?): String {
    val normalized = normalizePaymentMethod(value)
    return when {
        normalized.isBlank() -> "支払方法未入力"
        isSupportedPaymentMethod(normalized) -> normalized
        else -> "不明な支払方法"
    }
}
