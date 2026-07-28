package com.warun.accounting.data.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpensePrepaidEditPlanTest {
    @Test
    fun nonPrepaidEditDoesNotTouchLedger() {
        assertEquals(
            ExpensePrepaidEditPlan(false, false, ExpensePrepaidLinkTransition.None),
            planExpensePrepaidEdit(null, null, null, 700L, false)
        )
    }

    @Test
    fun prepaidAmountOrAccountChangeReversesAndReplacesPurchase() {
        assertEquals(
            ExpensePrepaidEditPlan(true, true, ExpensePrepaidLinkTransition.Replace),
            planExpensePrepaidEdit("prepaid-majica", 500L, "prepaid-majica", 700L, true)
        )
        assertEquals(
            ExpensePrepaidEditPlan(true, true, ExpensePrepaidLinkTransition.Replace),
            planExpensePrepaidEdit("prepaid-majica", 500L, "prepaid-au-pay", 500L, true)
        )
    }

    @Test
    fun prepaidToNonPrepaidReversesAndRemovesLink() {
        assertEquals(
            ExpensePrepaidEditPlan(true, false, ExpensePrepaidLinkTransition.Remove),
            planExpensePrepaidEdit("prepaid-majica", 500L, null, 500L, false)
        )
    }

    @Test
    fun nonPrepaidToPrepaidCreatesPurchaseAndLink() {
        assertEquals(
            ExpensePrepaidEditPlan(false, true, ExpensePrepaidLinkTransition.Create),
            planExpensePrepaidEdit(null, null, "prepaid-majica", 500L, true)
        )
    }

    @Test
    fun samePrepaidAccountAndAmountKeepsLedgerForOtherFieldEdits() {
        assertEquals(
            ExpensePrepaidEditPlan(false, false, ExpensePrepaidLinkTransition.Keep),
            planExpensePrepaidEdit("prepaid-majica", 500L, "prepaid-majica", 500L, true)
        )
    }
}
