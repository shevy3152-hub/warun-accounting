package com.warun.accounting.ui.balance

import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.ui.model.normalizeSupplierCandidateName
import com.warun.accounting.util.PaymentMethodCreditPurchase
import com.warun.accounting.util.normalizePaymentMethod
import java.math.BigInteger
import java.time.YearMonth

internal data class CreditPurchaseTaxSummary(
    val supplierName: String,
    val taxRatePercent: Int,
    val netAmount: Long,
    val taxAmount: Long,
    val grossAmount: Long
)

internal fun creditPurchaseTaxSummaries(
    expenses: List<ExpenseRecord>,
    targetMonth: YearMonth,
    excludedExpenseIds: Set<String> = emptySet()
): List<CreditPurchaseTaxSummary> = listOf(
    "トキノ屋" to 8,
    "サカツ" to 10
).map { (supplier, rate) ->
    val net = expenses.asSequence()
        .filterNot { it.id in excludedExpenseIds }
        .filter { it.expenseDate.startsWith(targetMonth.toString()) }
        .filter { normalizeSupplierCandidateName(it.supplierName.orEmpty()) == supplier }
        .filter { normalizePaymentMethod(it.paymentMethod) == PaymentMethodCreditPurchase }
        .map { it.amount }
        .fold(0L, Math::addExact)
    val tax = BigInteger.valueOf(net)
        .multiply(BigInteger.valueOf(rate.toLong()))
        .divide(BigInteger.valueOf(100L))
        .longValueExact()
    CreditPurchaseTaxSummary(supplier, rate, net, tax, Math.addExact(net, tax))
}
