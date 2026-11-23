package com.powerlock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

const val ACTION_MORNING = "com.powerlock.app.MORNING_REMIND"

/**
 * Daily morning reminder, through AlarmManager (same pattern as the
 * schedule alarms: exact when the permission allows it, inexact otherwise).
 * Alarms are re-armed on boot, app open and every settings change, so a
 * killed process never loses them.
 */
object Reminders {
    private const val REQ_MORNING = 91

    fun morningOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("morn_on", false)

    /** Reminder time in minutes since midnight (default 8:00 AM). */
    fun morningMin(ctx: Context): Int = Store.settings(ctx).getInt("morn_min", 480)

    fun setMorning(ctx: Context, on: Boolean, min: Int) {
        Store.settings(ctx).edit().putBoolean("morn_on", on).putInt("morn_min", min).apply()
        rescheduleMorning(ctx)
    }

    /** Next fire time for a daily minute-of-day (today if still ahead, else tomorrow). */
    private fun nextDaily(minOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, minOfDay / 60)
        c.set(Calendar.MINUTE, minOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun morningPending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_MORNING)
        return PendingIntent.getBroadcast(ctx, REQ_MORNING, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun rescheduleMorning(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = morningPending(ctx)
            am.cancel(pi)
            if (!morningOn(ctx)) return
            val at = nextDaily(morningMin(ctx))
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "morning reschedule failed", e)
        }
    }

    /** Re-schedules everything (boot, app update, settings change). Idempotent. */
    fun rescheduleAll(ctx: Context) {
        rescheduleMorning(ctx)
        Routine.rescheduleAlarms(ctx)
        TimeCapsules.rescheduleAlarms(ctx)
    }

    /** Fires at the morning time: a lock reminder. */
    fun onMorning(ctx: Context) {
        try {
            L.load(ctx)
            show(
                ctx, 11,
                L.t("Start today's first lock", "আজকের প্রথম লক শুরু করো"),
                L.t("A focused day starts with one lock.", "একটা লক দিয়ে ফোকাসের দিন শুরু করো।")
            )
        } catch (e: Exception) {
            Log.e(TAG, "morning reminder failed", e)
        }
        rescheduleMorning(ctx)
    }

    /** Shared notification helper (the routine reminders use it too). */
    fun show(ctx: Context, id: Int, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("powerlock_remind", "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val nb = Notification.Builder(ctx, "powerlock_remind")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
            nm.notify(id, nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "reminder notify failed", e)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            when (intent?.action) {
                ACTION_MORNING -> Reminders.onMorning(context)
                ACTION_ROUTINE_REMIND -> Routine.onRemind(
                    context,
                    intent.getLongExtra("task_id", 0),
                    intent.getStringExtra("day") ?: ""
                )
                ACTION_ROUTINE_OVERDUE -> Routine.onOverdue(
                    context,
                    intent.getLongExtra("task_id", 0),
                    intent.getStringExtra("day") ?: ""
                )
                ACTION_ROUTINE_REPORT -> Routine.onReport(context)
                ACTION_CAPSULE_DUE -> TimeCapsules.checkDue(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ReminderReceiver failed", e)
        }
    }
}

