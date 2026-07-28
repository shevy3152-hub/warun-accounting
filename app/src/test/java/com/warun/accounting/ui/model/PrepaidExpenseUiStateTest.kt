package com.warun.accounting.ui.model

import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrepaidExpenseUiStateTest {
    @Test
    fun resolvesAccountOnlyThroughExpensePurchaseLink() {
        val account = PrepaidAccountRecord(
            id = PrepaidAccountId.Majica,
            type = PrepaidAccountType.Majica,
            name = "majica",
            isActive = true,
            createdAt = 1,
            updatedAt = 1
        )
        val purchase = PrepaidTransactionRecord(
            id = "purchase-id",
            accountId = account.id,
            transactionDate = "2026-07-28",
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = -500,
            expenseId = "expense-id",
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = "operation-id",
            createdAt = 1
        )
        val state = DashboardUiState(
            prepaidAccounts = listOf(account),
            prepaidTransactions = listOf(purchase),
            expensePrepaidLinks = listOf(
                ExpensePrepaidLinkRecord("expense-id", purchase.id, 1, 1)
            )
        )

        assertEquals(account, state.prepaidAccountForExpense("expense-id"))
        assertNull(state.prepaidAccountForExpense("other-expense"))
    }
}
