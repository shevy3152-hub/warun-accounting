package com.warun.accounting.ui.receipt

import android.content.Context

enum class CameraPermissionState {
    FirstRequest,
    DeniedCanRetry,
    SettingsRequired,
    Granted,
    NoCamera
}

interface CameraPermissionRequestHistory {
    fun hasRequestedCameraPermission(): Boolean
    fun markCameraPermissionRequested()
}

class SharedPreferencesCameraPermissionRequestHistory(context: Context) : CameraPermissionRequestHistory {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun hasRequestedCameraPermission(): Boolean = preferences.getBoolean(RequestedKey, false)

    override fun markCameraPermissionRequested() {
        preferences.edit().putBoolean(RequestedKey, true).apply()
    }

    private companion object {
        const val PreferencesName = "receipt_camera_permission"
        const val RequestedKey = "camera_permission_requested"
    }
}
