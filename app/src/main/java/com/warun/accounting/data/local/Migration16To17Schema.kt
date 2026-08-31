package com.warun.accounting.data.local

/** Schema-only SQL. No existing rows, files, journals, or pending images are modified. */
internal object Migration16To17Schema {
    const val AddEvidenceMediaType = """
        ALTER TABLE evidence_records ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'image/jpeg'
    """

    const val CreateFixedCostReceiptApplications = """
        CREATE TABLE fixed_cost_receipt_applications (
            applicationId TEXT NOT NULL,
            receiptId TEXT NOT NULL,
            dailyReportId TEXT NOT NULL,
            fixedCostType TEXT NOT NULL,
            paymentMethod TEXT NOT NULL,
            appliedAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY(applicationId),
            FOREIGN KEY(receiptId) REFERENCES receipts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(dailyReportId) REFERENCES daily_reports(id) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
    """

    const val CreateFixedCostReceiptApplicationReceiptIndex = """
        CREATE UNIQUE INDEX index_fixed_cost_receipt_applications_receiptId
        ON fixed_cost_receipt_applications(receiptId)
    """

    const val CreateFixedCostReceiptApplicationTypeIndex = """
        CREATE UNIQUE INDEX index_fixed_cost_receipt_applications_dailyReportId_fixedCostType
        ON fixed_cost_receipt_applications(dailyReportId, fixedCostType)
    """

    const val CreateFixedCostEvidenceLinks = """
        CREATE TABLE fixed_cost_evidence_links (
            applicationId TEXT NOT NULL,
            evidenceId TEXT NOT NULL,
            sortOrder INTEGER NOT NULL,
            linkedAt INTEGER NOT NULL,
            PRIMARY KEY(applicationId, evidenceId),
            FOREIGN KEY(applicationId) REFERENCES fixed_cost_receipt_applications(applicationId)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(evidenceId) REFERENCES evidence_records(id)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
    """

    const val CreateFixedCostEvidenceSortIndex = """
        CREATE UNIQUE INDEX index_fixed_cost_evidence_links_applicationId_sortOrder
        ON fixed_cost_evidence_links(applicationId, sortOrder)
    """

    const val CreateFixedCostEvidenceIdIndex = """
        CREATE UNIQUE INDEX index_fixed_cost_evidence_links_evidenceId
        ON fixed_cost_evidence_links(evidenceId)
    """

    val Statements = listOf(
        AddEvidenceMediaType,
        CreateFixedCostReceiptApplications,
        CreateFixedCostReceiptApplicationReceiptIndex,
        CreateFixedCostReceiptApplicationTypeIndex,
        CreateFixedCostEvidenceLinks,
        CreateFixedCostEvidenceSortIndex,
        CreateFixedCostEvidenceIdIndex
    )
}
