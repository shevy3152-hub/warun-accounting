package com.warun.accounting.data.prepaid

import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType

fun netCashChargeAmount(
    transactions: Iterable<PrepaidTransactionRecord>,
    includesDate: (String) -> Boolean = { true }
): Long {
    val all = transactions.toList()
    val byId = all.associateBy { it.id }
    return all
        .asSequence()
        .filter { includesDate(it.transactionDate) }
        .filter { transaction ->
            when (transaction.transactionType) {
                PrepaidTransactionType.Charge ->
                    transaction.chargeSource == PrepaidChargeSource.Cash
                PrepaidTransactionType.Reversal -> {
                    val target = transaction.reversalOfTransactionId?.let(byId::get)
                    target?.transactionType == PrepaidTransactionType.Charge &&
                        target.chargeSource == PrepaidChargeSource.Cash
                }
                else -> false
            }
        }
        .fold(0L) { total, transaction ->
            Math.addExact(total, transaction.balanceDelta)
        }
}
