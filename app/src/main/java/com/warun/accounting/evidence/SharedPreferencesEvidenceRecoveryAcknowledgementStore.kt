package com.warun.accounting.evidence

import android.content.SharedPreferences

class SharedPreferencesEvidenceRecoveryAcknowledgementStore(
    private val preferences: SharedPreferences
) : EvidenceRecoveryAcknowledgementStore {
    override fun acknowledgedSignature(): String? =
        preferences.getString(AcknowledgedSignatureKey, null)

    override fun acknowledge(signature: String) {
        preferences.edit().putString(AcknowledgedSignatureKey, signature).commit()
    }

    private companion object {
        const val AcknowledgedSignatureKey = "acknowledged_evidence_recovery_signature"
    }
}
