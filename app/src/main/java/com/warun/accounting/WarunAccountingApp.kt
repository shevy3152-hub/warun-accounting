package com.warun.accounting

import android.app.Application
import com.warun.accounting.backup.BackupPaths
import com.warun.accounting.backup.RestoreStartupRecovery
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WarunAccountingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RestoreStartupRecovery(BackupPaths(this)).recoverIfNeeded()
    }
}
