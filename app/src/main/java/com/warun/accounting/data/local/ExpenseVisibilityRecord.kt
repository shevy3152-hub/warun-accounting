package com.warun.accounting.data.local

import androidx.room.Embedded

data class ExpenseVisibilityRecord(
    @Embedded val expense: ExpenseRecord,
    val isCancelled: Boolean
)
