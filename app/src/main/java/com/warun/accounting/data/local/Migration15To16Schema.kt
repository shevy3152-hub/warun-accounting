package com.warun.accounting.data.local

/**
 * Schema-only SQL shared by Room migration and offline restore-candidate upgrade.
 *
 * Keep these statements free of updates, backfills, and deletes. A staged restore upgrades only
 * the extracted candidate; production data is not opened by this contract.
 */
internal object Migration15To16Schema {
    const val CreateElectronicSubmissionRecords = """
        CREATE TABLE electronic_submission_records (
            id TEXT NOT NULL,
            targetMonth TEXT NOT NULL,
            generatedAt INTEGER NOT NULL,
            dailyReportFileName TEXT,
            expenseDetailFileName TEXT,
            receiptPdfFileName TEXT,
            status TEXT NOT NULL,
            submittedAt INTEGER,
            note TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
    """

    const val CreateTargetMonthIndex = """
        CREATE INDEX index_electronic_submission_records_targetMonth
        ON electronic_submission_records(targetMonth)
    """

    const val CreateGeneratedAtIndex = """
        CREATE INDEX index_electronic_submission_records_generatedAt
        ON electronic_submission_records(generatedAt)
    """

    val Statements = listOf(
        CreateElectronicSubmissionRecords,
        CreateTargetMonthIndex,
        CreateGeneratedAtIndex
    )
}
