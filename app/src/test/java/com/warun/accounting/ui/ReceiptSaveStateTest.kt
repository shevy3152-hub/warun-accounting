package com.warun.accounting.ui

import com.warun.accounting.ui.viewmodel.ReceiptInput
import org.junit.Assert.assertSame
import org.junit.Test

class ReceiptSaveStateTest {
    @Test
    fun failureKeepsCurrentInput() {
        val current = ReceiptInput(storeName = "入力中")
        val reset = ReceiptInput()

        val actual = receiptInputAfterSave(Result.failure(IllegalStateException()), current, reset)

        assertSame(current, actual)
    }

    @Test
    fun successReturnsResetInput() {
        val current = ReceiptInput(storeName = "保存済み")
        val reset = ReceiptInput()

        val actual = receiptInputAfterSave(Result.success(Unit), current, reset)

        assertSame(reset, actual)
    }
}