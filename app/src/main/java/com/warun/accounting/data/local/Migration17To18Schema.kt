package com.warun.accounting.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Creates the Evidence-only current-assignment and append-only audit tables. */
internal object Migration17To18Schema {
    const val CreateAssignments = """
        CREATE TABLE fixed_cost_evidence_assignments (
            evidenceId TEXT NOT NULL,
            dailyReportId TEXT NOT NULL,
            fixedCostType TEXT NOT NULL,
            sortOrder INTEGER NOT NULL,
            assignedAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY(evidenceId),
            FOREIGN KEY(evidenceId) REFERENCES evidence_records(id)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(dailyReportId) REFERENCES daily_reports(id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
    """

    const val CreateAssignmentsTargetIndex = """
        CREATE INDEX index_fixed_cost_evidence_assignments_dailyReportId_fixedCostType
        ON fixed_cost_evidence_assignments(dailyReportId, fixedCostType)
    """

    const val CreateAssignmentsSortIndex = """
        CREATE UNIQUE INDEX index_fixed_cost_evidence_assignments_dailyReportId_fixedCostType_sortOrder
        ON fixed_cost_evidence_assignments(dailyReportId, fixedCostType, sortOrder)
    """

    const val CreateAudits = """
        CREATE TABLE fixed_cost_evidence_assignment_audits (
            operationId TEXT NOT NULL,
            evidenceId TEXT NOT NULL,
            operationType TEXT NOT NULL,
            beforeDailyReportId TEXT,
            beforeFixedCostType TEXT,
            afterDailyReportId TEXT,
            afterFixedCostType TEXT,
            executedAt INTEGER NOT NULL,
            PRIMARY KEY(operationId)
        )
    """

    const val CreateAuditsEvidenceIndex = """
        CREATE INDEX index_fixed_cost_evidence_assignment_audits_evidenceId_executedAt
        ON fixed_cost_evidence_assignment_audits(evidenceId, executedAt)
    """

    const val BackfillAssignments = """
        INSERT INTO fixed_cost_evidence_assignments(
            evidenceId, dailyReportId, fixedCostType, sortOrder, assignedAt, updatedAt
        )
        SELECT link.evidenceId, application.dailyReportId, application.fixedCostType,
               link.sortOrder, link.linkedAt, link.linkedAt
        FROM fixed_cost_evidence_links AS link
        INNER JOIN fixed_cost_receipt_applications AS application
            ON application.applicationId = link.applicationId
        """

    val Statements = listOf(
        CreateAssignments,
        CreateAssignmentsTargetIndex,
        CreateAssignmentsSortIndex,
        CreateAudits,
        CreateAuditsEvidenceIndex,
        BackfillAssignments
    )

    fun verifyBackfill(db: SupportSQLiteDatabase) {
        val sourceCount = db.scalarLong("SELECT COUNT(*) FROM fixed_cost_evidence_links")
        val targetCount = db.scalarLong("SELECT COUNT(*) FROM fixed_cost_evidence_assignments")
        check(sourceCount == targetCount) {
            "Fixed-cost Evidence assignment backfill count mismatch"
        }
        val mismatchCount = db.scalarLong(
            """
            SELECT COUNT(*)
            FROM fixed_cost_evidence_links AS link
            INNER JOIN fixed_cost_receipt_applications AS application
                ON application.applicationId = link.applicationId
            LEFT JOIN fixed_cost_evidence_assignments AS assignment
                ON assignment.evidenceId = link.evidenceId
               AND assignment.dailyReportId = application.dailyReportId
               AND assignment.fixedCostType = application.fixedCostType
               AND assignment.sortOrder = link.sortOrder
               AND assignment.assignedAt = link.linkedAt
               AND assignment.updatedAt = link.linkedAt
            WHERE assignment.evidenceId IS NULL
            """.trimIndent()
        )
        check(mismatchCount == 0L) {
            "Fixed-cost Evidence assignment backfill content mismatch"
        }
    }

    private fun SupportSQLiteDatabase.scalarLong(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Migration verification query returned no row" }
            cursor.getLong(0)
        }
}
