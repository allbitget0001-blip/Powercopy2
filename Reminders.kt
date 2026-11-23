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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

const val ACTION_MORNING = "com.powerlock.app.MORNING_REMIND"
const val ACTION_PLAN = "com.powerlock.app.PLAN_REMIND"

/**
 * Daily morning reminder + today's-plan task reminders, all through
 * AlarmManager (same pattern as the schedule alarms: exact when the
 * permission allows it, inexact otherwise). Alarms are re-armed on boot,
 * app open and every settings change, so a killed process never loses them.
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
        Plan.rescheduleAlarms(ctx)
        Routine.rescheduleAlarms(ctx)
        TimeCapsules.rescheduleAlarms(ctx)
    }

    /** Fires at the morning time: a lock reminder + today's task summary (if any). */
    fun onMorning(ctx: Context) {
        try {
            L.load(ctx)
            show(
                ctx, 11,
                L.t("Start today's first lock", "আজকের প্রথম লক শুরু করো"),
                L.t("A focused day starts with one lock.", "একটা লক দিয়ে ফোকাসের দিন শুরু করো।")
            )
            val tasks = Plan.tasksToday(ctx).filter { !it.done }
            if (tasks.isNotEmpty()) {
                val lines = tasks.take(5).joinToString("\n") { "• " + it.title }
                show(ctx, 12, L.t("Today's plan", "আজকের পরিকল্পনা"), lines)
            }
        } catch (e: Exception) {
            Log.e(TAG, "morning reminder failed", e)
        }
        rescheduleMorning(ctx)
    }

    /** Task reminder: fires at the task's time (skipped when done or deleted). */
    fun onPlanTask(ctx: Context, taskId: Long, dayKey: String) {
        try {
            L.load(ctx)
            val t = Plan.tasks(ctx, dayKey).find { it.id == taskId } ?: return
            if (t.done) return
            show(ctx, 10000 + (taskId % 1000).toInt(), L.t("Reminder", "রিমাইন্ডার"), t.title) // plan range 10000–10999
        } catch (e: Exception) {
            Log.e(TAG, "plan reminder failed", e)
        }
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
                ACTION_PLAN -> Reminders.onPlanTask(
                    context,
                    intent.getLongExtra("task_id", 0),
                    intent.getStringExtra("day") ?: ""
                )
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

/**
 * Today's plan: date-keyed to-do tasks with an optional reminder time.
 * Only today (and yesterday, for the one-tap carry-over) is kept; older
 * days are pruned on every save.
 */
object Plan {

    fun tasks(ctx: Context, dayKey: String): List<PlanTask> {
        val out = ArrayList<PlanTask>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString("plan_$dayKey", "[]"))
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    out.add(PlanTask(o.getLong("id"), o.getString("t"), o.optInt("m", -1), o.optBoolean("d", false)))
                } catch (_: Exception) {
                    // one malformed entry: skip it, keep the rest
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "plan read failed", e)
        }
        return out
    }

    fun tasksToday(ctx: Context): List<PlanTask> = tasks(ctx, Store.dayKey(0))

    fun saveTasks(ctx: Context, dayKey: String, list: List<PlanTask>) {
        // a corrupt stored list must never be overwritten with a fresh one
        if (JsonGuard.isCorrupt(Store.settings(ctx), "plan_$dayKey")) {
            Log.e(TAG, "plan corrupt, keeping stored value")
            return
        }
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("t", t.title)
            o.put("m", t.timeMin)
            o.put("d", t.done)
            arr.put(o)
        }
        val e = Store.settings(ctx).edit().putString("plan_$dayKey", arr.toString())
        // prune days older than yesterday (carry-over only needs yesterday)
        for (ago in 2..10) e.remove("plan_" + Store.dayKey(ago))
        e.apply()
    }

    fun addTask(ctx: Context, title: String, timeMin: Int) {
        val list = tasksToday(ctx).toMutableList()
        list.add(PlanTask(Store.newId(), title, timeMin, false))
        saveTasks(ctx, Store.dayKey(0), list)
        rescheduleAlarms(ctx)
    }

    fun toggleTask(ctx: Context, id: Long) {
        val day = Store.dayKey(0)
        val list = tasks(ctx, day).map { if (it.id == id) it.copy(done = !it.done) else it }
        saveTasks(ctx, day, list)
        rescheduleAlarms(ctx)
    }

    fun deleteTask(ctx: Context, id: Long) {
        val day = Store.dayKey(0)
        saveTasks(ctx, day, tasks(ctx, day).filter { it.id != id })
        rescheduleAlarms(ctx)
    }

    /** Yesterday's undone tasks, for the one-tap carry-over. */
    fun yesterdayUndone(ctx: Context): List<PlanTask> =
        tasks(ctx, Store.dayKey(1)).filter { !it.done }

    /** Moves yesterday's undone tasks to today (fresh ids, same times). */
    fun carryOver(ctx: Context) {
        val undone = yesterdayUndone(ctx)
        if (undone.isEmpty()) return
        val list = tasksToday(ctx).toMutableList()
        for (t in undone) {
            list.add(PlanTask(Store.newId(), t.title, t.timeMin, false))
        }
        saveTasks(ctx, Store.dayKey(0), list)
        // clear yesterday's copied tasks so a repeat tap has nothing to copy
        // again (and the carry-over button disappears)
        saveTasks(ctx, Store.dayKey(1), tasks(ctx, Store.dayKey(1)).filter { it.done })
        rescheduleAlarms(ctx)
    }

    private fun taskPending(ctx: Context, task: PlanTask, dayKey: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_PLAN)
            .putExtra("task_id", task.id).putExtra("day", dayKey)
        val code = (task.id % Int.MAX_VALUE).toInt()
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Cancels today's + yesterday's task alarms and re-arms today's upcoming ones. Idempotent. */
    fun rescheduleAlarms(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (day in listOf(Store.dayKey(0), Store.dayKey(1))) {
                for (t in tasks(ctx, day)) {
                    if (t.timeMin >= 0) am.cancel(taskPending(ctx, t, day))
                }
            }
            val now = System.currentTimeMillis()
            for (t in tasksToday(ctx)) {
                if (t.done || t.timeMin < 0) continue
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, t.timeMin / 60)
                c.set(Calendar.MINUTE, t.timeMin % 60)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis <= now) continue
                val pi = taskPending(ctx, t, Store.dayKey(0))
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "plan reschedule failed", e)
        }
    }
}
