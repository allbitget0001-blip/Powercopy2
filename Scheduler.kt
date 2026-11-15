package com.example.powerlocks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

const val ACTION_SCHEDULE = "com.example.powerlocks.SCHEDULE_TICK"

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
