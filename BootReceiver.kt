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
            Watchdog.enqueue(context) // safety net: restart the service every 15 min if it dies
            // the location lock has no session, so it must restart the service too
            if (Store.isActive(context) || Store.isLocProtecting(context)) Store.kickService(context)
            // image guard: after a reboot the capture grant is gone, so this
            // posts the re-grant notification when a lock is running
            try { ImageGuardService.sync(context) } catch (e: Exception) { Log.e(TAG, "image guard sync failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver failed", e)
        }
    }
}
