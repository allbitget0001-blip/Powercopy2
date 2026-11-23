package com.powerlock.app

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
            Reminders.rescheduleAll(context) // morning + routine alarms survive a reboot
            TimeCapsules.checkDue(context) // a capsule may have opened while the phone was off
            Routine.sweep(context) // a routine task may have become overdue while the phone was off
            Watchdog.enqueue(context) // safety net: restart the service every 15 min if it dies
            // the location lock has no session, so it must restart the service too
            if (Store.isActive(context) || Store.isLocProtecting(context)) Store.kickService(context)
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver failed", e)
        }
    }
}
