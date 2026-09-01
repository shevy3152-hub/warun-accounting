package com.warun.accounting.ui.fixedcost

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.warun.accounting.data.fixedcost.FixedCostEvidenceRegistrationState
import com.warun.accounting.data.fixedcost.FixedCostEvidenceStatus
import com.warun.accounting.data.fixedcost.registrationState
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.EvidenceRecord

@Composable
fun FixedCostEvidenceStatusRows(
    report: DailyReport,
    statuses: List<FixedCostEvidenceStatus>,
    onOpenEvidence: (fixedCostType: String, evidence: List<EvidenceRecord>) -> Unit
) {
    val items = listOf(
        "electricity" to ("電気代" to report.electricityExpense),
        "water" to ("水道代" to report.waterExpense),
        "communication" to ("通信費" to report.communicationExpense),
        "gas" to ("ガス代" to report.gasExpense)
    )
    items.forEach { (type, labelAndAmount) ->
        val (label, amount) = labelAndAmount
        val status = statuses.firstOrNull {
            it.dailyReportId == report.id && it.fixedCostType == type
        } ?: FixedCostEvidenceStatus(report.id, type, null, emptyList())
        when (status.registrationState(amount)) {
            FixedCostEvidenceRegistrationState.NONE -> Unit
            FixedCostEvidenceRegistrationState.MISSING -> Text(
                "$label：証憑未登録",
                fontSize = 16.sp,
                modifier = Modifier.testTag("fixed-cost-evidence-missing-$type")
            )
            FixedCostEvidenceRegistrationState.NEEDS_REVIEW -> Text(
                "$label：証憑要確認",
                fontSize = 16.sp,
                modifier = Modifier.testTag("fixed-cost-evidence-review-$type")
            )
            FixedCostEvidenceRegistrationState.REGISTERED -> TextButton(
                onClick = { onOpenEvidence(type, status.evidence) },
                modifier = Modifier.testTag("fixed-cost-evidence-registered-$type")
            ) {
                Text("$label：証憑${status.evidence.size}件")
            }
        }
    }
}
