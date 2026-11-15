package com.example.powerlocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Runs after reboot, app update and time changes: restores the protection state and schedules. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            L.load(context)
            Scheduler.onAlarm(context)
            if (Store.isActive(context)) Store.kickService(context)
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver failed", e)
        }
    }
}
