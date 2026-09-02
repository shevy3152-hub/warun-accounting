package com.warun.accounting.data

sealed interface ReceiptDeletionResult {
    data object Deleted : ReceiptDeletionResult
    data object NotFound : ReceiptDeletionResult
    data object AlreadyConfirmed : ReceiptDeletionResult
    data object Protected : ReceiptDeletionResult
    data object Failed : ReceiptDeletionResult
}
