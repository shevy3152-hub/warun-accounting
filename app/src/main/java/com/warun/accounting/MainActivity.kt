package com.warun.accounting

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.warun.accounting.ui.WarunApp
import com.warun.accounting.ui.theme.WarunTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WarunTheme {
                WarunApp()
            }
        }
    }

    fun restartAfterRestore() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: error("Application launch intent is unavailable")
        launchIntent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            7_315,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 750L,
            pendingIntent
        )
        finishAffinity()
        Process.killProcess(Process.myPid())
    }
}
