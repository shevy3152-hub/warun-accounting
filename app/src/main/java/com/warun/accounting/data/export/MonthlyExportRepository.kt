package com.warun.accounting.data.export

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.data.local.WarunDao
import java.time.YearMonth
import javax.inject.Inject

data class MonthlyExportSourceSnapshot(
    val dailyReports: List<DailyReport>,
    val expenseVisibility: List<ExpenseVisibilityRecord>,
    val storedEvidence: List<ExpenseEvidenceRecord>
)

interface MonthlyExportRepository {
    suspend fun loadSourceSnapshot(targetMonth: YearMonth): MonthlyExportSourceSnapshot
}

class OfflineMonthlyExportRepository @Inject constructor(
    private val dao: WarunDao
) : MonthlyExportRepository {
    override suspend fun loadSourceSnapshot(targetMonth: YearMonth): MonthlyExportSourceSnapshot =
        dao.getMonthlyExportSourceSnapshot(
            targetMonth = targetMonth.toString(),
            from = targetMonth.atDay(1).toString(),
            to = targetMonth.atEndOfMonth().toString()
        )
}
