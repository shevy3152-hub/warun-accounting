package com.warun.accounting.ui.model

import java.text.Normalizer

internal const val MaxRecentSupplierCandidates = 6

internal fun normalizeSupplierCandidateName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

internal fun shouldSaveCustomSupplierCandidate(
    customSupplierSelected: Boolean,
    supplierName: String,
    displayedCandidateNames: List<String>
): Boolean {
    if (!customSupplierSelected) return false
    val normalizedName = normalizeSupplierCandidateName(supplierName)
    if (normalizedName.isBlank()) return false
    return displayedCandidateNames.none {
        normalizeSupplierCandidateName(it) == normalizedName
    }
}

internal fun shouldPersistSupplierCandidateAfterExpenseSave(
    saveSucceeded: Boolean,
    candidateEligible: Boolean
): Boolean = saveSucceeded && candidateEligible
