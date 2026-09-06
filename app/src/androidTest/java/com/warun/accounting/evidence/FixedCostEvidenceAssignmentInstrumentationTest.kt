package com.warun.accounting.evidence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.fixedcost.FixedCostEvidenceAssociationResult
import com.warun.accounting.data.fixedcost.FixedCostEvidenceTarget
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.FixedCostEvidenceLinkRecord
import com.warun.accounting.data.local.FixedCostReceiptApplicationRecord
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedCostEvidenceAssignmentInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: WarunDatabase
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        databaseName = "fixed-cost-assignment-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, WarunDatabase::class.java, databaseName).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun reassignChangesOnlyCurrentTargetAndAppendsAudit() = runBlocking {
        val dao = database.warunDao()
        val oldReport = report("report-old", "2026-08-23", 41_617)
        val newReport = report("report-new", "2026-09-01", 12_000)
        val receipt = ReceiptRecord("receipt", oldReport.reportDate, oldReport.reportDate, 1, "fixed", 41_617, 0, null, null, true, null, 2)
        val evidence = storedEvidence("evidence-reassign")
        dao.insertDailyReport(oldReport)
        dao.insertDailyReport(newReport)
        dao.insertReceipt(receipt)
        dao.insertEvidenceRecord(evidence)
        val initial = dao.assignFixedCostEvidence(
            evidence.id,
            FixedCostEvidenceTarget(oldReport.id, "electricity"),
            "op-assign",
            3
        )
        assertEquals(FixedCostEvidenceAssociationResult.Success, initial)
        val beforeReport = dao.getDailyReport(oldReport.id)
        val beforeNewReport = dao.getDailyReport(newReport.id)
        val beforeReceipt = dao.getReceipt(receipt.id)
        val beforeEvidence = dao.getEvidenceRecord(evidence.id)

        assertEquals(
            FixedCostEvidenceAssociationResult.Success,
            dao.reassignFixedCostEvidence(
                evidence.id,
                FixedCostEvidenceTarget(oldReport.id, "electricity"),
                FixedCostEvidenceTarget(newReport.id, "water"),
                "op-reassign",
                4
            )
        )

        assertEquals(beforeReport, dao.getDailyReport(oldReport.id))
        assertEquals(beforeNewReport, dao.getDailyReport(newReport.id))
        assertEquals(beforeReceipt, dao.getReceipt(receipt.id))
        assertEquals(beforeEvidence, dao.getEvidenceRecord(evidence.id))
        assertEquals(
            FixedCostEvidenceTarget(newReport.id, "water"),
            dao.getFixedCostEvidenceAssignment(evidence.id)!!.let {
                FixedCostEvidenceTarget(it.dailyReportId, it.fixedCostType)
            }
        )
        val audits = dao.getFixedCostEvidenceAssignmentAudits(evidence.id)
        assertEquals(listOf("ASSIGN", "REASSIGN"), audits.map { it.operationType })
        assertEquals(oldReport.id, audits[1].beforeDailyReportId)
        assertEquals(newReport.id, audits[1].afterDailyReportId)
    }

    @Test
    fun unlinkPreservesEvidenceAndMakesItUnclassified() = runBlocking {
        val dao = database.warunDao()
        val report = report("report-unlink", "2026-08-23", 41_617)
        val evidence = storedEvidence("evidence-unlink")
        val receipt = ReceiptRecord("receipt-unlink", report.reportDate, report.reportDate, 1, "fixed", 41_617, 0, null, null, true, null, 1)
        val application = FixedCostReceiptApplicationRecord("application-unlink", receipt.id, report.id, "electricity", "現金", 1, 1)
        val legacyLink = FixedCostEvidenceLinkRecord(application.applicationId, evidence.id, 0, 1)
        dao.insertDailyReport(report)
        dao.insertReceipt(receipt)
        dao.insertFixedCostReceiptApplication(application)
        dao.insertEvidenceRecord(evidence)
        dao.insertFixedCostEvidenceLink(legacyLink)
        val beforeApplication = dao.getFixedCostReceiptApplication(application.applicationId)
        val beforeLegacyLink = dao.getFixedCostEvidenceLinks(application.applicationId)
        assertEquals(
            FixedCostEvidenceAssociationResult.Success,
            dao.assignFixedCostEvidence(evidence.id, FixedCostEvidenceTarget(report.id, "electricity"), "op-assign", 1)
        )
        assertEquals(
            FixedCostEvidenceAssociationResult.Success,
            dao.unlinkFixedCostEvidence(evidence.id, FixedCostEvidenceTarget(report.id, "electricity"), "op-unlink", 2)
        )
        assertNull(dao.getFixedCostEvidenceAssignment(evidence.id))
        assertEquals(evidence, dao.getEvidenceRecord(evidence.id))
        assertEquals(beforeApplication, dao.getFixedCostReceiptApplication(application.applicationId))
        assertEquals(beforeLegacyLink, dao.getFixedCostEvidenceLinks(application.applicationId))
        assertEquals(listOf(evidence.id), dao.observeUnclassifiedFixedCostEvidence().first().map { it.id })
        val audit = dao.getFixedCostEvidenceAssignmentAudits(evidence.id).last()
        assertEquals("UNLINK", audit.operationType)
        assertEquals(report.id, audit.beforeDailyReportId)
        assertNull(audit.afterDailyReportId)
    }

    @Test
    fun unclassifiedEvidenceCanBeAssignedAndMultipleEvidenceShareOneTarget() = runBlocking {
        val dao = database.warunDao()
        val report = report("report-multiple", "2026-08-23", 41_617)
        val evidence = listOf(storedEvidence("evidence-a"), storedEvidence("evidence-b"))
        dao.insertDailyReport(report)
        evidence.forEach { dao.insertEvidenceRecord(it) }
        assertEquals(2, dao.observeUnclassifiedFixedCostEvidence().first().size)
        evidence.forEachIndexed { index, item ->
            assertEquals(
                FixedCostEvidenceAssociationResult.Success,
                dao.assignFixedCostEvidence(item.id, FixedCostEvidenceTarget(report.id, "electricity"), "op-assign-$index", index.toLong())
            )
        }
        assertEquals(
            listOf(0, 1),
            dao.getFixedCostEvidenceAssignmentsForTarget(report.id, "electricity").map { it.sortOrder }
        )
        assertTrue(dao.observeUnclassifiedFixedCostEvidence().first().isEmpty())
    }

    @Test
    fun invalidTargetsExpenseLinksAndStaleOperationsAreRejected() = runBlocking {
        val dao = database.warunDao()
        val report = report("report-reject", "2026-08-23", 41_617)
        val evidence = storedEvidence("evidence-reject")
        val expense = ExpenseRecord("expense-reject", report.reportDate, "other_expense", "store", 100, "現金", null, null, "manual", 1, 1)
        dao.insertDailyReport(report)
        dao.insertEvidenceRecord(evidence)
        dao.insertExpenseRecord(expense)
        dao.insertExpenseEvidenceLink(ExpenseEvidenceLinkRecord(expense.id, evidence.id, 2))
        assertEquals(
            FixedCostEvidenceAssociationResult.LinkedToExpense,
            dao.assignFixedCostEvidence(evidence.id, FixedCostEvidenceTarget(report.id, "electricity"), "op-expense", 3)
        )

        val freeEvidence = storedEvidence("evidence-reject-free")
        dao.insertEvidenceRecord(freeEvidence)
        assertEquals(
            FixedCostEvidenceAssociationResult.InvalidFixedCostType,
            dao.assignFixedCostEvidence(freeEvidence.id, FixedCostEvidenceTarget(report.id, "rent"), "op-bad-type", 4)
        )
        assertEquals(
            FixedCostEvidenceAssociationResult.DailyReportNotFound,
            dao.assignFixedCostEvidence(freeEvidence.id, FixedCostEvidenceTarget("missing", "electricity"), "op-missing-report", 5)
        )
        assertEquals(
            FixedCostEvidenceAssociationResult.Success,
            dao.assignFixedCostEvidence(freeEvidence.id, FixedCostEvidenceTarget(report.id, "electricity"), "op-free", 6)
        )
        assertEquals(
            FixedCostEvidenceAssociationResult.AlreadyAssigned,
            dao.assignFixedCostEvidence(freeEvidence.id, FixedCostEvidenceTarget(report.id, "electricity"), "op-double", 7)
        )
        assertEquals(
            FixedCostEvidenceAssociationResult.CurrentTargetMismatch,
            dao.reassignFixedCostEvidence(
                freeEvidence.id,
                FixedCostEvidenceTarget("stale", "electricity"),
                FixedCostEvidenceTarget(report.id, "water"),
                "op-stale",
                8
            )
        )
        assertNotNull(dao.getFixedCostEvidenceAssignment(freeEvidence.id))
        assertFalse(dao.observeUnclassifiedFixedCostEvidence().first().any { it.id == freeEvidence.id })
    }

    @Test
    fun failedAuditInsertRollsBackAssignmentUpdate() = runBlocking {
        val dao = database.warunDao()
        val oldReport = report("report-rollback-old", "2026-08-23", 1)
        val newReport = report("report-rollback-new", "2026-09-01", 2)
        val evidence = storedEvidence("evidence-rollback")
        dao.insertDailyReport(oldReport)
        dao.insertDailyReport(newReport)
        dao.insertEvidenceRecord(evidence)
        dao.assignFixedCostEvidence(evidence.id, FixedCostEvidenceTarget(oldReport.id, "electricity"), "op-assign", 1)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_fixed_cost_assignment_audit
            BEFORE INSERT ON fixed_cost_evidence_assignment_audits
            BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END
            """.trimIndent()
        )

        assertTrue(
            runCatching {
                dao.reassignFixedCostEvidence(
                    evidence.id,
                    FixedCostEvidenceTarget(oldReport.id, "electricity"),
                    FixedCostEvidenceTarget(newReport.id, "water"),
                    "op-rollback",
                    2
                )
            }.isFailure
        )
        val current = dao.getFixedCostEvidenceAssignment(evidence.id)!!
        assertEquals(oldReport.id, current.dailyReportId)
        assertEquals("electricity", current.fixedCostType)
        assertEquals(1, dao.getFixedCostEvidenceAssignmentAudits(evidence.id).size)
    }

    @Test
    fun concurrentReassignUsesExpectedStateAndOnlyOneTargetWins() = runBlocking {
        val dao = database.warunDao()
        val oldReport = report("report-concurrent-old", "2026-08-23", 1)
        val targetA = report("report-concurrent-a", "2026-09-01", 2)
        val targetB = report("report-concurrent-b", "2026-09-02", 3)
        val evidence = storedEvidence("evidence-concurrent")
        listOf(oldReport, targetA, targetB).forEach { dao.insertDailyReport(it) }
        dao.insertEvidenceRecord(evidence)
        dao.assignFixedCostEvidence(evidence.id, FixedCostEvidenceTarget(oldReport.id, "electricity"), "op-assign", 1)

        val results = listOf(
            async(Dispatchers.IO) {
                dao.reassignFixedCostEvidence(
                    evidence.id,
                    FixedCostEvidenceTarget(oldReport.id, "electricity"),
                    FixedCostEvidenceTarget(targetA.id, "water"),
                    "op-a",
                    2
                )
            },
            async(Dispatchers.IO) {
                dao.reassignFixedCostEvidence(
                    evidence.id,
                    FixedCostEvidenceTarget(oldReport.id, "electricity"),
                    FixedCostEvidenceTarget(targetB.id, "gas"),
                    "op-b",
                    3
                )
            }
        ).awaitAll()
        assertEquals(1, results.count { it == FixedCostEvidenceAssociationResult.Success })
        assertEquals(1, results.count { it == FixedCostEvidenceAssociationResult.CurrentTargetMismatch })
        assertTrue(dao.getFixedCostEvidenceAssignment(evidence.id)!!.dailyReportId in setOf(targetA.id, targetB.id))
    }

    private fun report(id: String, date: String, electricity: Long) = DailyReport(
        id = id,
        reportDate = date,
        status = DailyReportStatus.Draft,
        authorName = null,
        cashSales = 0,
        cardSales = 0,
        qrSales = 0,
        accountsReceivableSales = 0,
        otherSales = 0,
        foodPurchases = 0,
        alcoholPurchases = 0,
        consumablesExpense = 0,
        utilitiesExpense = 0,
        electricityExpense = electricity,
        gasExpense = 0,
        waterExpense = 0,
        communicationExpense = 0,
        rentExpense = 0,
        accountantFeeExpense = 0,
        miscellaneousExpense = 0,
        otherExpense = 0,
        openingCash = 0,
        actualClosingCash = 0,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1,
        updatedAt = 1,
        hasActualClosingCash = false
    )

    private fun storedEvidence(id: String) = EvidenceRecord(
        id = id,
        captureId = "capture-$id",
        storedUri = "file:/$id.jpg",
        byteSize = 10,
        sha256 = "a".repeat(64),
        state = EvidenceRecordState.Stored,
        createdAt = 1,
        storedAt = 2,
        updatedAt = 2,
        mediaType = "image/jpeg"
    )
}
