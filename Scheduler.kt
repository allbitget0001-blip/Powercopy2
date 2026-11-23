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

const val ACTION_SCHEDULE = "com.powerlock.app.SCHEDULE_TICK"
const val ACTION_MORNING = "com.powerlock.app.MORNING_REMINDER"

object Scheduler {

    private fun atMinute(base: Long, offsetDays: Int, minute: Int): Calendar {
        val c = Calendar.getInstance()
        c.timeInMillis = base
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        c.set(Calendar.HOUR_OF_DAY, minute / 60)
        c.set(Calendar.MINUTE, minute % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    private fun lengthMs(s: Schedule): Long {
        var len = ((s.endMin - s.startMin) + 1440) % 1440
        if (len == 0) len = 1440
        return len * 60_000L
    }

    /** If "now" is inside an enabled schedule window, returns the window end time. Handles overnight windows. */
    fun activeWindowEnd(ctx: Context, now: Long): Long? {
        var best: Long? = null
        for (s in Store.schedules(ctx)) {
            if (!s.enabled) continue
            for (off in -1..0) {
                val c = atMinute(now, off, s.startMin)
                if (((s.days shr dayIdx(c)) and 1) == 0) continue
                val start = c.timeInMillis
                val end = start + lengthMs(s)
                if (now >= start && now < end) {
                    val b = best
                    if (b == null || end > b) best = end
                }
            }
        }
        return best
    }

    /** Next schedule start strictly after "now". */
    fun nextStart(ctx: Context, now: Long): Pair<Schedule, Long>? {
        var best: Pair<Schedule, Long>? = null
        for (s in Store.schedules(ctx)) {
            if (!s.enabled) continue
            for (off in 0..7) {
                val c = atMinute(now, off, s.startMin)
                if (((s.days shr dayIdx(c)) and 1) == 0) continue
                val t = c.timeInMillis
                if (t > now) {
                    val b = best
                    if (b == null || t < b.second) best = Pair(s, t)
                    break
                }
            }
        }
        return best
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java)
        i.action = ACTION_SCHEDULE
        return PendingIntent.getBroadcast(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun reschedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx)
            am.cancel(pi)
            val next = nextStart(ctx, System.currentTimeMillis()) ?: return
            val at = next.second
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "reschedule failed", e)
        }
    }

    /** Called by alarm, boot, app open and schedule edits. Starts protection if we are inside a window. */
    fun onAlarm(ctx: Context) {
        try {
            Store.finalizeIfExpired(ctx)
            val now = System.currentTimeMillis()
            val w = activeWindowEnd(ctx, now)
            val suppress = Store.lock(ctx).getLong("suppress", 0)
            if (w != null && now >= suppress && !Store.isActive(ctx)) {
                Store.startSession(ctx, w - now, "schedule")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAlarm failed", e)
        }
        reschedule(ctx)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            L.load(context)
            Scheduler.onAlarm(context)
        } catch (e: Exception) {
            Log.e(TAG, "ScheduleReceiver failed", e)
        }
    }
}

// =====================================================================
// MORNING REMINDER (a daily nudge to start a focus session)
// =====================================================================

/**
 * A daily notification at a user-chosen time (default 07:00). Implemented
 * with AlarmManager, following the Scheduler's pattern: an exact alarm when
 * the permission is available, otherwise an inexact one. Each firing
 * schedules the next day, and BootReceiver re-schedules after a reboot or
 * time change. Tapping the notification opens the app. It uses a normal
 * notification channel — no Do Not Disturb interaction.
 */
object MorningReminder {

    private const val REQ_CODE = 78
    private const val NOTIF_ID = 4
    private const val CHANNEL_ID = "powerlock_morning"

    /** Default 07:00. */
    const val DEFAULT_MIN = 7 * 60

    fun isOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("morning_on", false)

    fun setOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("morning_on", on).apply()
        reschedule(ctx)
    }

    fun minutes(ctx: Context): Int =
        Store.settings(ctx).getInt("morning_min", DEFAULT_MIN).coerceIn(0, 1439)

    fun setMinutes(ctx: Context, min: Int) {
        Store.settings(ctx).edit().putInt("morning_min", min.coerceIn(0, 1439)).apply()
        reschedule(ctx)
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, MorningReceiver::class.java)
        i.action = ACTION_MORNING
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Next occurrence of the morning time (today if still ahead, else tomorrow). */
    fun nextTime(ctx: Context, now: Long): Long {
        val min = minutes(ctx)
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, min / 60)
        c.set(Calendar.MINUTE, min % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun reschedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx)
            am.cancel(pi)
            if (!isOn(ctx)) return
            val at = nextTime(ctx, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "morning reschedule failed", e)
        }
    }

    /** Shows the reminder notification, then schedules tomorrow's. */
    fun fire(ctx: Context) {
        try {
            L.load(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, L.t("Morning reminder", "সকালের রিমাইন্ডার"), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nb = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(L.t("Time to focus", "ফোকাস করার সময়"))
                .setContentText(L.t("Start a focus session and take back your morning.", "একটা ফোকাস সেশন শুরু করুন আর সকালটা নিজের করে নিন।"))
                .setAutoCancel(true)
                .setContentIntent(open)
            nm.notify(NOTIF_ID, nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "morning notify failed", e)
        }
        reschedule(ctx)
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (!MorningReminder.isOn(context)) return
            MorningReminder.fire(context)
        } catch (e: Exception) {
            Log.e(TAG, "MorningReceiver failed", e)
        }
    }
}
