package com.powerlock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

const val APP_VERSION = "2.0 Premium"
const val TAG = "PowerLock"

/** Longest allowed session: 15 days, in minutes. */
const val MAX_MIN = 15 * 24 * 60

/** Language helper: every visible text is written as (English, Bangla). */
object L {
    @Volatile
    var bn = true

    fun load(ctx: Context) {
        bn = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "bn") == "bn"
    }

    fun t(en: String, bnText: String): String = if (bn) bnText else en

    fun num(s: String): String {
        if (!bn) return s
        val sb = StringBuilder()
        for (c in s) {
            if (c in '0'..'9') sb.append('০' + (c - '0')) else sb.append(c)
        }
        return sb.toString()
    }

    fun dayShort(i: Int): String {
        val en = listOf("M", "T", "W", "T", "F", "S", "S")
        val b = listOf("সো", "মং", "বু", "বৃ", "শু", "শ", "র")
        return if (bn) b[i] else en[i]
    }

    fun dayLong(i: Int): String {
        val en = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val b = listOf("সোম", "মঙ্গল", "বুধ", "বৃহ", "শুক্র", "শনি", "রবি")
        return if (bn) b[i] else en[i]
    }

    fun month(i: Int): String {
        val en = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val b = listOf("জানু", "ফেব্রু", "মার্চ", "এপ্রি", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টে", "অক্টো", "নভে", "ডিসে")
        return if (bn) b[i] else en[i]
    }
}

enum class PStatus { READY, ACTIVE, SCHEDULED, COMPLETED }

data class Schedule(val id: Long, val days: Int, val startMin: Int, val endMin: Int, val enabled: Boolean)

data class Session(val start: Long, val end: Long, val dur: Long, val ok: Boolean, val src: String)

/** What the last completed lock achieved: how long it ran and how many blocks fired. */
data class SessionSummary(val durMs: Long, val blocks: Int, val endMs: Long)

/** 7-day detox challenge: "none" (not started), "active", "broken" (a day missed),
 * "done" (all 7 days checked). [days] are the 7 day keys, [dots] which are checked. */
data class DetoxState(val mode: String, val checked: Int, val days: List<String>, val dots: List<Boolean>, val startKey: String)

/** Focus report: current streak in days, total focus minutes, completed locks,
 * and focus minutes for the last 7 days (oldest first). */
data class FocusStats(val streak: Int, val totalMin: Long, val completed: Int, val last7: List<Int>)

/** Unlock condition for a running session: time only, location only, or either one. */
const val UNLOCK_TIME = "time"
const val UNLOCK_LOC = "loc"
const val UNLOCK_EITHER = "either"

/** Destination that ends a location-unlock session when the device arrives within [radius] metres. */
data class UnlockTarget(val lat: Double, val lng: Double, val radius: Int, val name: String)

/** Pomodoro state: which phase of the set is running and when it ends. */
data class PomState(val active: Boolean, val round: Int, val phase: String, val phaseEnd: Long, val phaseTotal: Long)

/** One milestone badge: earned once, shown with the date it was earned. */
data class BadgeDef(val id: String, val icon: Ic, val titleEn: String, val titleBn: String, val descEn: String, val descBn: String)

/** A daily habit: checked off once a day, with a consecutive-day streak. */
data class Habit(val id: Long, val name: String, val created: Long)

/** One focus note: a quick thought jotted during (or outside) a lock. */
data class FocusNote(val id: Long, val text: String, val time: Long, val duringLock: Boolean)

/** One task in today's plan: optional reminder time in minutes since midnight (-1 = none). */
data class PlanTask(val id: Long, val title: String, val timeMin: Int, val done: Boolean)

/** One repeating routine task: [timeMin] is the time, [graceMin] the window
 * before the phone locks, [verify] the check-off type ("manual" in v1 —
 * a later build adds sensor verification per task). */
data class RoutineTask(val id: Long, val title: String, val timeMin: Int, val graceMin: Int, val verify: String = "manual")

/** One usage-history session: which app, opened when, closed when (wall-clock ms). */
data class UsageSession(val pkg: String, val open: Long, val close: Long)

// ---------- formatting helpers ----------

fun dayIdx(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

fun fmtMin(m: Int): String {
    val h = m / 60
    val mm = m % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ap = if (h < 12) "AM" else "PM"
    return L.num(String.format(Locale.US, "%d:%02d %s", h12, mm, ap))
}

fun fmtClock(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return fmtMin(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
}

fun fmtHms(ms: Long): String {
    val s = if (ms < 0) 0L else ms / 1000
    return L.num(String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60))
}

fun fmtDur(ms: Long): String {
    if (ms <= 0L) return L.num(L.t("0m", "0 মিনিট"))
    val totalMin = ms / 60000
    if (totalMin == 0L) return L.t("<1m", "১ মিনিটের কম")
    val days = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    val en = StringBuilder()
    val bnb = StringBuilder()
    if (days > 0) {
        en.append("${days}d ")
        bnb.append("$days দিন ")
    }
    if (h > 0) {
        en.append("${h}h ")
        bnb.append("$h ঘণ্টা ")
    }
    if (m > 0) {
        en.append("${m}m ")
        bnb.append("$m মিনিট ")
    }
    return L.num(L.t(en.toString().trim(), bnb.toString().trim()))
}

fun fmtDate(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return L.dayLong(dayIdx(c)) + ", " + L.num(c.get(Calendar.DAY_OF_MONTH).toString()) + " " + L.month(c.get(Calendar.MONTH))
}

fun statusOf(ctx: Context, active: Boolean, now: Long): PStatus {
    if (active) return PStatus.ACTIVE
    val last = Store.lock(ctx).getLong("lastDone", 0)
    if (last > 0 && now - last < 30 * 60_000L) return PStatus.COMPLETED
    if (Scheduler.nextStart(ctx, now) != null) return PStatus.SCHEDULED
    return PStatus.READY
}

// ---------- permissions ----------

object Perm {
    fun accessibilityOn(ctx: Context): Boolean {
        val e = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return e.contains(ctx.packageName + "/")
    }

    fun notificationsOn(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
    }

    fun batteryOk(ctx: Context): Boolean {
        return try {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            true
        }
    }

    fun exactAlarmOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    fun locationOn(ctx: Context): Boolean {
        return ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED
    }

    fun backgroundLocationOn(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return ctx.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED
    }
}

// ---------- Do Not Disturb during a lock ----------

/**
 * Silences notifications (and calls) while a lock runs, and restores the
 * previous interruption mode when the lock ends. Alarms always ring
 * (INTERRUPTION_FILTER_ALARMS). If the user revokes the DND permission,
 * the feature turns itself off instead of crashing.
 */
object Dnd {
    fun isGranted(ctx: Context): Boolean {
        return try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        } catch (e: Exception) {
            false
        }
    }

    fun isOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("dnd_on", false)

    /** Called when a lock starts: silence notifications/calls (alarms still ring). */
    fun apply(ctx: Context) {
        try {
            if (!isOn(ctx)) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                Store.settings(ctx).edit().putBoolean("dnd_on", false).apply()
                return
            }
            val p = Store.lock(ctx)
            if (!p.contains("dnd_prev")) {
                p.edit().putInt("dnd_prev", nm.currentInterruptionFilter).apply()
            }
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } catch (e: Exception) {
            Log.e(TAG, "DND apply failed", e)
        }
    }

    /** Called when a lock ends: restore the previous interruption mode. */
    fun restore(ctx: Context) {
        try {
            val p = Store.lock(ctx)
            if (!p.contains("dnd_prev")) return
            val prev = p.getInt("dnd_prev", NotificationManager.INTERRUPTION_FILTER_ALL)
            p.edit().remove("dnd_prev").apply()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(prev)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DND restore failed", e)
        }
    }
}

// ---------- focus score (0–100, one day) ----------

/** One day's focus score with its visible breakdown. */
data class ScoreBreakdown(
    val total: Int,
    val focusPts: Int,
    val locksPts: Int,
    val streakPts: Int,
    val calmPts: Int,
    val focusMin: Int,
    val locks: Int,
    val streak: Int,
    val attempts: Int
)

/**
 * Daily focus score, 0–100, computed transparently from existing data:
 *  - focus minutes: up to 40 pts against a 120-minute daily goal
 *  - completed locks: up to 20 pts (10 per lock)
 *  - streak: up to 20 pts (2 per streak day)
 *  - low temptation: up to 20 pts (20 minus 1 per blocked-open attempt, min 0)
 * Today's score includes the running lock's elapsed minutes, so it moves
 * live; past days are rebuilt from focus_days / sessions / attempts.
 * Each day's score is stored date-keyed ("focus_scores") so the 7-day
 * list stays stable even as the attempts list is pruned.
 */
object FocusScore {
    const val GOAL_MIN = 120

    private fun build(focusMin: Int, locks: Int, streak: Int, attempts: Int): ScoreBreakdown {
        val focusPts = (focusMin * 40 / GOAL_MIN).coerceIn(0, 40)
        val locksPts = (locks * 10).coerceIn(0, 20)
        val streakPts = (streak * 2).coerceIn(0, 20)
        val calmPts = (20 - attempts).coerceIn(0, 20)
        val total = (focusPts + locksPts + streakPts + calmPts).coerceIn(0, 100)
        return ScoreBreakdown(total, focusPts, locksPts, streakPts, calmPts, focusMin, locks, streak, attempts)
    }

    /** Streak ending on the day N days ago (today: ending today or yesterday).
     * A day saved by a streak-protection token counts as complete. */
    private fun streakFor(ctx: Context, focusMap: Map<String, Int>, daysAgo: Int): Int {
        fun hasDay(ago: Int) = (focusMap[Store.dayKey(ago)] ?: 0) > 0 ||
            StreakProtection.isFocusProtected(ctx, Store.dayKey(ago))
        val startAgo = if (daysAgo == 0) {
            if (hasDay(0)) 0 else if (hasDay(1)) 1 else -1
        } else if (hasDay(daysAgo)) daysAgo else -1
        if (startAgo < 0) return 0
        var streak = 0
        var i = startAgo
        while (hasDay(i)) {
            streak++
            i++
        }
        return streak
    }

    /** Elapsed minutes of the lock running right now (0 when idle). */
    private fun liveMin(ctx: Context): Int {
        if (!Store.isActive(ctx)) return 0
        val start = Store.lock(ctx).getLong("start", 0L)
        if (start <= 0) return 0
        return ((System.currentTimeMillis() - start) / 60000L).toInt().coerceAtLeast(0)
    }

    fun forDay(ctx: Context, daysAgo: Int): ScoreBreakdown {
        val key = Store.dayKey(daysAgo)
        val focusMap = Store.focusDayMap(ctx)
        var focusMin = focusMap[key] ?: 0
        if (daysAgo == 0) focusMin += liveMin(ctx)
        val locks = Store.sessions(ctx).count { it.ok && Store.dayKeyFor(it.end) == key }
        val now = System.currentTimeMillis()
        val from = Store.dayStart(now, -daysAgo)
        val to = Store.dayStart(now, -daysAgo + 1)
        val attempts = Extra.attempts(ctx).count { it.first >= from && it.first < to }
        return build(focusMin, locks, streakFor(ctx, focusMap, daysAgo), attempts)
    }

    fun today(ctx: Context): ScoreBreakdown = forDay(ctx, 0)

    /** Scores for the last 7 days, oldest first — one pass over the stored data. */
    fun week(ctx: Context): List<ScoreBreakdown> {
        val focusMap = Store.focusDayMap(ctx)
        val locksByDay = HashMap<String, Int>()
        for (s in Store.sessions(ctx)) {
            if (!s.ok) continue
            val k = Store.dayKeyFor(s.end)
            locksByDay[k] = (locksByDay[k] ?: 0) + 1
        }
        val attemptsByDay = HashMap<String, Int>()
        for ((t, _) in Extra.attempts(ctx)) {
            val k = Store.dayKeyFor(t)
            attemptsByDay[k] = (attemptsByDay[k] ?: 0) + 1
        }
        val live = liveMin(ctx)
        val out = ArrayList<ScoreBreakdown>()
        for (ago in 6 downTo 0) {
            val key = Store.dayKey(ago)
            var focusMin = focusMap[key] ?: 0
            if (ago == 0) focusMin += live
            out.add(build(focusMin, locksByDay[key] ?: 0, streakFor(ctx, focusMap, ago), attemptsByDay[key] ?: 0))
        }
        return out
    }

    /** Persist today's score date-keyed (only when the total changed). */
    fun recordToday(ctx: Context, b: ScoreBreakdown = today(ctx)) {
        try {
            val p = Store.lock(ctx)
            val obj = try {
                JSONObject(p.getString("focus_scores", "{}"))
            } catch (e: Exception) {
                JSONObject()
            }
            val key = Store.dayKey(0)
            val prev = obj.optJSONObject(key)?.optInt("t", -1) ?: -1
            if (prev == b.total) return
            val o = JSONObject()
            o.put("t", b.total)
            o.put("f", b.focusPts)
            o.put("l", b.locksPts)
            o.put("s", b.streakPts)
            o.put("c", b.calmPts)
            obj.put(key, o)
            // keep 90 days, like focus_days
            val keep = (0 until 90).map { Store.dayKey(it) }.toSet()
            val keys = obj.keys()
            val drop = ArrayList<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k !in keep) drop.add(k)
            }
            for (k in drop) obj.remove(k)
            p.edit().putString("focus_scores", obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "focus score record failed", e)
        }
    }

    /** Stored total for a day, or null when it was never recorded. */
    fun storedTotal(ctx: Context, daysAgo: Int): Int? {
        return try {
            val obj = JSONObject(Store.lock(ctx).getString("focus_scores", "{}"))
            val o = obj.optJSONObject(Store.dayKey(daysAgo)) ?: return null
            o.optInt("t", -1).takeIf { it >= 0 }
        } catch (e: Exception) {
            null
        }
    }
}

// ---------- persistent storage ----------

object Store {
    fun lock(ctx: Context): SharedPreferences = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)
    fun settings(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun soundVib(ctx: Context): Boolean = settings(ctx).getBoolean("sound", true)

    fun isActive(ctx: Context): Boolean {
        finalizeIfExpired(ctx)
        val p = lock(ctx)
        return p.getBoolean("active", false) && System.currentTimeMillis() < p.getLong("end", 0)
    }

    /** Records the session once when its time is over. Safe to call many times. */
    @Synchronized
    fun finalizeIfExpired(ctx: Context) {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return
        val end = p.getLong("end", 0)
        if (System.currentTimeMillis() < end) return
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(p.getLong("start", end), end, p.getLong("total", 0), true, src))
        FocusTree.recordOutcome(ctx, true, end) // the lock completed: the tree survives
        val e = p.edit().putBoolean("active", false).putLong("lastDone", end)
        clearUnlock(e)
        e.remove("s_profile_apps").remove("s_profile_name")
        e.remove("pen_task_id").remove("pen_task")
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlock.sync(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
    }

    /** The running session's unlock destination, or null for a time-only session. */
    fun unlockTarget(ctx: Context): UnlockTarget? {
        val p = lock(ctx)
        if (p.getString("u_mode", UNLOCK_TIME) == UNLOCK_TIME) return null
        if (!p.contains("u_lat")) return null
        return UnlockTarget(
            Double.fromBits(p.getLong("u_lat", 0)),
            Double.fromBits(p.getLong("u_lng", 0)),
            p.getInt("u_radius", 200),
            p.getString("u_name", "") ?: ""
        )
    }

    /** The running session's unlock mode: "time", "loc" or "either". */
    fun unlockMode(ctx: Context): String = lock(ctx).getString("u_mode", UNLOCK_TIME) ?: UNLOCK_TIME

    /**
     * Ends the running session because the device arrived at its unlock destination.
     * Works like a time expiry (a completed session) and bypasses strict mode, because
     * arriving was the agreed unlock condition. Returns true when a session was ended.
     */
    @Synchronized
    fun finalizeByLocation(ctx: Context): Boolean {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return false
        if (unlockTarget(ctx) == null) return false
        val now = System.currentTimeMillis()
        val start = p.getLong("start", now)
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(start, now, now - start, true, src))
        FocusTree.recordOutcome(ctx, true, now) // arrived: the lock completed, the tree survives
        val e = p.edit().putBoolean("active", false).putLong("lastDone", now).putLong("end", now)
        clearUnlock(e)
        e.remove("s_profile_apps").remove("s_profile_name")
        e.remove("pen_task_id").remove("pen_task")
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlock.sync(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
        kickService(ctx) // refresh the notification: the lock is off now
        return true
    }

    /**
     * Ends a penalty lock early because the user finished the overdue routine
     * task and checked it off. Counts as a completed session (the task was
     * done), unlike stopSession. Returns true when a penalty lock was ended.
     */
    @Synchronized
    fun finalizePenalty(ctx: Context): Boolean {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return false
        if ((p.getString("src", "manual") ?: "manual") != "routine") return false
        val now = System.currentTimeMillis()
        val start = p.getLong("start", now)
        addSession(ctx, Session(start, now, now - start, true, "routine"))
        FocusTree.recordOutcome(ctx, true, now) // task done: the lock completed, the tree survives
        val e = p.edit().putBoolean("active", false).putLong("lastDone", now).putLong("end", now)
        clearUnlock(e)
        e.remove("s_profile_apps").remove("s_profile_name")
        e.remove("pen_task_id").remove("pen_task")
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlock.sync(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
        kickService(ctx) // refresh the notification: the lock is off now
        return true
    }

    private fun clearUnlock(e: SharedPreferences.Editor) {
        e.remove("u_mode").remove("u_lat").remove("u_lng").remove("u_radius").remove("u_name")
    }

    fun startSession(ctx: Context, durationMs: Long, src: String): Boolean =
        startSession(ctx, durationMs, src, null, UNLOCK_TIME)

    /**
     * Starts a session. [unlock] + [mode] make it a location session: "loc" ends only on
     * arrival (durationMs is then just the 15-day safety cap), "either" ends on arrival
     * or when the time runs out, whichever comes first. [profileId] snapshots that
     * profile's apps into the session — they are blocked for this session on top of
     * the global full-block list, and later profile edits do not affect it.
     */
    fun startSession(ctx: Context, durationMs: Long, src: String, unlock: UnlockTarget?, mode: String, profileId: Long = -1L): Boolean {
        if (isActive(ctx)) return false
        val now = System.currentTimeMillis()
        val st = settings(ctx)
        val e = lock(ctx).edit()
            .putBoolean("s_strict", st.getBoolean("strict", false))
            .putBoolean("s_guard", st.getBoolean("guard", true))
            .putBoolean("active", true)
            .putLong("start", now)
            .putLong("end", now + durationMs)
            .putLong("total", durationMs)
            .putString("src", src)
            .putLong("lastDone", 0)
        val prof = if (profileId > 0) Extra.lockProfiles(ctx).find { it.id == profileId } else null
        if (prof != null && prof.pkgs.isNotEmpty()) {
            e.putString("s_profile_apps", prof.pkgs.joinToString(","))
            e.putString("s_profile_name", prof.name)
        } else {
            e.remove("s_profile_apps").remove("s_profile_name")
        }
        if (unlock != null && mode != UNLOCK_TIME) {
            e.putString("u_mode", mode)
                .putLong("u_lat", unlock.lat.toRawBits())
                .putLong("u_lng", unlock.lng.toRawBits())
                .putInt("u_radius", unlock.radius)
                .putString("u_name", unlock.name)
        } else {
            clearUnlock(e)
        }
        // a normal lock plants a seed; Pomodoro focus rounds never grow trees
        if (src == "pomodoro") e.remove("tree_planted") else e.putBoolean("tree_planted", true)
        e.remove("pen_task_id").remove("pen_task") // only a penalty lock sets these (after this call)
        e.apply()
        Dnd.apply(ctx) // lock started: silence notifications if the user wants it
        FocusSound.sync(ctx) // lock started: play the chosen focus sound, if any
        SiteBlock.sync(ctx) // lock started: start the site-block VPN, if enabled
        LockWidget.refresh(ctx)
        kickService(ctx)
        return true
    }

    /** True while a running session was started in strict mode (no early stop). */
    fun isStrict(ctx: Context): Boolean = isActive(ctx) && lock(ctx).getBoolean("s_strict", false)

    /** The profile name of the running session, or null when none was chosen. */
    fun sessionProfileName(ctx: Context): String? =
        if (isActive(ctx)) lock(ctx).getString("s_profile_name", null) else null

    /** True when the running session was started by the location lock (only those may be auto-stopped). */
    fun isLocationSession(ctx: Context): Boolean =
        isActive(ctx) && (lock(ctx).getString("src", "manual") ?: "manual") == "location"

    /**
     * Location protection: the power menu is blocked while at a saved place, without any Home
     * session. The single location switch controls it — the main lock does not need to be on.
     */
    fun setLocProtecting(ctx: Context, on: Boolean) {
        lock(ctx).edit().putBoolean("loc_protecting", on).apply()
    }

    fun isLocProtecting(ctx: Context): Boolean = lock(ctx).getBoolean("loc_protecting", false)

    /**
     * [force] is only for the location lock: leaving/arriving ends the session even in strict
     * mode, because the lock follows presence. The manual Stop button never passes force.
     */
    fun stopSession(ctx: Context, force: Boolean = false) {
        if (!isActive(ctx)) return
        if (!force && lock(ctx).getBoolean("s_strict", false)) return
        Pomodoro.clearState(ctx) // an early stop ends any running Pomodoro set too
        val p = lock(ctx)
        val now = System.currentTimeMillis()
        val start = p.getLong("start", now)
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(start, now, now - start, false, src))
        FocusTree.recordOutcome(ctx, false, now) // stopped early: the tree withers
        val w = Scheduler.activeWindowEnd(ctx, now)
        val e = p.edit().putBoolean("active", false).putLong("end", now)
        if (w != null) e.putLong("suppress", w)
        clearUnlock(e)
        e.remove("s_profile_apps").remove("s_profile_name")
        e.remove("pen_task_id").remove("pen_task")
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlock.sync(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
        kickService(ctx)
    }

    fun kickService(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "kickService failed", e)
        }
    }

    // ----- route (30 days) + entry/exit history (30 days) -----

    fun locData(ctx: Context): SharedPreferences = ctx.getSharedPreferences("locdata", Context.MODE_PRIVATE)

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Day key ("yyyyMMdd") for N days ago (0 = today). */
    fun dayKey(daysAgo: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Day key ("yyyyMMdd") for an arbitrary timestamp. */
    fun dayKeyFor(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Stored day keys, newest first (max 30). */
    fun routeDays(ctx: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(locData(ctx).getString("route_days", "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
            Log.e(TAG, "route days read failed", e)
        }
        return out
    }

    /** Points recorded on one day ("yyyyMMdd"). */
    fun routeForDay(ctx: Context, day: String): List<RoutePoint> {
        val out = ArrayList<RoutePoint>()
        try {
            // one-time fallback: the first version kept only today under "route"
            val raw = locData(ctx).getString("route_$day", null)
                ?: if (day == todayKey()) locData(ctx).getString("route", null) else null
            if (raw == null) return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RoutePoint(o.getDouble("la"), o.getDouble("lo"), o.getLong("t")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "route read failed", e)
        }
        return out
    }

    /** Points recorded today. */
    fun todayRoute(ctx: Context): List<RoutePoint> = routeForDay(ctx, todayKey())

    /** Distance of one day's route, in metres. */
    fun distanceForDay(ctx: Context, day: String): Float {
        var total = 0f
        val pts = routeForDay(ctx, day)
        for (i in 1 until pts.size) {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng, res)
            total += res[0]
        }
        return total
    }

    /** Total distance walked/driven today, in metres. */
    fun todayDistanceM(ctx: Context): Float = distanceForDay(ctx, todayKey())

    /** Total distance of all stored days (up to 30), in metres. */
    fun monthDistanceM(ctx: Context): Float {
        var total = 0f
        for (day in routeDays(ctx)) total += distanceForDay(ctx, day)
        return total
    }

    /** Record a point (at most one per 20 m / 60 s, max 2000 points a day, kept 30 days). */
    fun addRoutePoint(ctx: Context, lat: Double, lng: Double) {
        try {
            val p = locData(ctx)
            val day = todayKey()
            val pts = routeForDay(ctx, day).toMutableList()
            val now = System.currentTimeMillis()
            val last = pts.lastOrNull()
            if (last != null) {
                val res = FloatArray(1)
                android.location.Location.distanceBetween(last.lat, last.lng, lat, lng, res)
                if (res[0] < 20f && now - last.t < 60_000L) return
            }
            pts.add(RoutePoint(lat, lng, now))
            while (pts.size > 2000) pts.removeAt(0)
            val arr = JSONArray()
            for (pt in pts) {
                val o = JSONObject()
                o.put("la", pt.lat)
                o.put("lo", pt.lng)
                o.put("t", pt.t)
                arr.put(o)
            }
            val e = p.edit().putString("route_$day", arr.toString())
            // keep the day index (newest first, max 30) and drop older days
            val days = ArrayList<String>()
            days.add(day)
            for (d in routeDays(ctx)) if (d != day) days.add(d)
            val kept = days.take(30)
            for (d in days.drop(30)) e.remove("route_$d")
            val daysArr = JSONArray()
            for (d in kept) daysArr.put(d)
            e.putString("route_days", daysArr.toString())
            e.apply()
        } catch (e: Exception) {
            Log.e(TAG, "route save failed", e)
        }
    }

    /** Entry/exit events, newest first (max 500, kept 30 days). outTime 0 means still inside. */
    fun placeEvents(ctx: Context): List<PlaceEvent> {
        val out = ArrayList<PlaceEvent>()
        try {
            val arr = JSONArray(locData(ctx).getString("events", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(PlaceEvent(o.getLong("id"), o.getString("n"), o.getLong("in"), o.optLong("out", 0)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "events read failed", e)
        }
        return out
    }

    private fun saveEvents(ctx: Context, list: List<PlaceEvent>) {
        // drop closed events older than 30 days; an open visit is always kept
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val kept = list.filter { it.outTime == 0L || it.inTime > cutoff }.take(500)
        val arr = JSONArray()
        for (e in kept) {
            val o = JSONObject()
            o.put("id", e.placeId)
            o.put("n", e.name)
            o.put("in", e.inTime)
            o.put("out", e.outTime)
            arr.put(o)
        }
        locData(ctx).edit().putString("events", arr.toString()).apply()
    }

    /** A place was entered: open a new event (only if none is open for it). */
    fun recordEnter(ctx: Context, pl: Place) {
        val list = placeEvents(ctx).toMutableList()
        if (list.any { it.placeId == pl.id && it.outTime == 0L }) return
        list.add(0, PlaceEvent(pl.id, pl.name, System.currentTimeMillis(), 0))
        saveEvents(ctx, list)
    }

    /** A place was left: close the open event for it. */
    fun recordExit(ctx: Context, pl: Place) {
        val list = placeEvents(ctx).toMutableList()
        val idx = list.indexOfFirst { it.placeId == pl.id && it.outTime == 0L }
        if (idx < 0) return
        list[idx] = list[idx].copy(outTime = System.currentTimeMillis())
        saveEvents(ctx, list)
    }

    fun clearEvents(ctx: Context) {
        locData(ctx).edit().remove("events").apply()
    }

    // ----- sessions -----

    fun sessions(ctx: Context): List<Session> {
        val out = ArrayList<Session>()
        try {
            val arr = JSONArray(lock(ctx).getString("sessions", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Session(o.getLong("s"), o.getLong("e"), o.getLong("d"), o.getBoolean("ok"), o.optString("src", "manual")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sessions read failed", e)
        }
        return out
    }

    private fun addSession(ctx: Context, s: Session) {
        val list = sessions(ctx).toMutableList()
        list.add(s)
        val trimmed = if (list.size > 500) list.subList(list.size - 500, list.size) else list
        val arr = JSONArray()
        for (x in trimmed) {
            val o = JSONObject()
            o.put("s", x.start)
            o.put("e", x.end)
            o.put("d", x.dur)
            o.put("ok", x.ok)
            o.put("src", x.src)
            arr.put(o)
        }
        lock(ctx).edit().putString("sessions", arr.toString()).apply()
        if (s.ok) recordFocus(ctx, s)
        if (s.ok) Xp.awardForSession(ctx, s) // XP: minutes + 10 per completed lock
        if (s.ok) saveSessionSummary(ctx, s)
        if (s.ok) Badges.check(ctx)
        if (s.ok) FocusScore.recordToday(ctx)
        if (s.ok) FocusPet.onLockCompleted(ctx) // the pet is happier
        if (s.ok) LuckyWheel.onLockCompleted(ctx) // +1 wheel spin
        if (!s.ok) FocusPet.onLockBroken(ctx) // the pet is sadder
    }

    /** The last completed lock's summary (duration + blocks fired in its window),
     * shown once as a dialog and kept as a line on Home. */
    private fun saveSessionSummary(ctx: Context, s: Session) {
        try {
            val blocks = Extra.attempts(ctx).count { it.first >= s.start && it.first <= s.end }
            val o = JSONObject()
            o.put("d", s.dur)
            o.put("b", blocks)
            o.put("e", s.end)
            lock(ctx).edit().putString("last_summary", o.toString()).putBoolean("sum_pending", true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "summary save failed", e)
        }
    }

    fun lastSummary(ctx: Context): SessionSummary? {
        return try {
            val raw = lock(ctx).getString("last_summary", null) ?: return null
            val o = JSONObject(raw)
            SessionSummary(o.getLong("d"), o.getInt("b"), o.getLong("e"))
        } catch (e: Exception) {
            null
        }
    }

    /** True when a fresh summary is waiting to be shown as a dialog. */
    fun summaryPending(ctx: Context): Boolean = lock(ctx).getBoolean("sum_pending", false)

    fun clearSummaryPending(ctx: Context) {
        lock(ctx).edit().putBoolean("sum_pending", false).apply()
    }

    /** A completed lock counts toward the streak + focus totals (date-keyed, kept 90 days). */
    private fun recordFocus(ctx: Context, s: Session) {
        try {
            val p = lock(ctx)
            val day = dayKeyFor(s.end)
            val min = (s.dur / 60000L).toInt().coerceAtLeast(1)
            val obj = JSONObject(p.getString("focus_days", "{}"))
            obj.put(day, obj.optInt(day, 0) + min)
            val keep = (0 until 90).map { dayKey(it) }.toSet()
            val keys = obj.keys()
            val drop = ArrayList<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k !in keep) drop.add(k)
            }
            for (k in drop) obj.remove(k)
            p.edit().putString("focus_days", obj.toString())
                .putLong("focus_total_min", p.getLong("focus_total_min", 0) + min)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "focus record failed", e)
        }
    }

    /** Streak (consecutive days with a completed lock, ending today or yesterday),
     * total focus minutes, completed locks, and focus minutes for the last 7 days
     * (oldest first). */
    fun focusStats(ctx: Context): FocusStats {
        val p = lock(ctx)
        // one-time backfill: older builds recorded sessions but no focus store
        if (!p.contains("focus_days")) {
            try {
                val all = sessions(ctx)
                val obj = JSONObject()
                var total = 0L
                for (s in all) {
                    if (!s.ok) continue
                    val day = dayKeyFor(s.end)
                    val min = (s.dur / 60000L).toInt().coerceAtLeast(1)
                    obj.put(day, obj.optInt(day, 0) + min)
                    total += min
                }
                p.edit().putString("focus_days", obj.toString()).putLong("focus_total_min", total).apply()
            } catch (e: Exception) {
                Log.e(TAG, "focus backfill failed", e)
                p.edit().putString("focus_days", "{}").apply()
            }
        }
        val obj = try {
            JSONObject(p.getString("focus_days", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        // a day saved by a streak-protection token counts as complete
        fun hasDay(daysAgo: Int) = obj.optInt(dayKey(daysAgo), 0) > 0 ||
            StreakProtection.isFocusProtected(ctx, dayKey(daysAgo))
        var streak = 0
        if (hasDay(0) || hasDay(1)) {
            var i = if (hasDay(0)) 0 else 1
            while (hasDay(i)) {
                streak++
                i++
            }
        }
        val last7 = (6 downTo 0).map { obj.optInt(dayKey(it), 0) }
        return FocusStats(streak, p.getLong("focus_total_min", 0), sessions(ctx).count { it.ok }, last7)
    }

    /** "yyyyMMdd" key shifted by N days (negative = past). */
    fun dayKeyPlus(key: String, days: Int): String {
        return try {
            val c = Calendar.getInstance()
            c.set(key.substring(0, 4).toInt(), key.substring(4, 6).toInt() - 1, key.substring(6, 8).toInt(), 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.DAY_OF_YEAR, days)
            String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        } catch (e: Exception) {
            key
        }
    }

    /** Focus minutes per day key (a day counts when a lock completed on it). */
    fun focusDayMap(ctx: Context): Map<String, Int> {
        val p = lock(ctx)
        if (!p.contains("focus_days")) focusStats(ctx) // one-time backfill, then cheap reads
        val out = HashMap<String, Int>()
        try {
            val obj = JSONObject(p.getString("focus_days", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "focus days read failed", e)
        }
        return out
    }

    // ----- 7-day detox challenge -----

    /** Day 1 = today. A day is checked when a lock completed on it (focus_days). */
    fun startDetox(ctx: Context) {
        lock(ctx).edit().putString("detox_start", dayKey(0)).remove("detox_celebrated").apply()
    }

    fun detoxState(ctx: Context): DetoxState {
        val start = lock(ctx).getString("detox_start", "") ?: ""
        if (start.isEmpty()) {
            return DetoxState("none", 0, (0 until 7).map { dayKey(it) }.reversed(), List(7) { false }, "")
        }
        val focus = focusDayMap(ctx)
        val today = dayKey(0)
        val days = (0 until 7).map { dayKeyPlus(start, it) }
        val dots = days.map { (focus[it] ?: 0) > 0 }
        val checked = dots.count { it }
        val mode = when {
            checked == 7 -> "done"
            days.indices.any { i -> !dots[i] && days[i] < today } -> "broken"
            else -> "active"
        }
        return DetoxState(mode, checked, days, dots, start)
    }

    /** True once the "challenge complete" dialog has been shown for this run. */
    fun detoxCelebrated(ctx: Context): Boolean {
        val start = lock(ctx).getString("detox_start", "") ?: ""
        return start.isNotEmpty() && lock(ctx).getString("detox_celebrated", "") == start
    }

    fun markDetoxCelebrated(ctx: Context) {
        val start = lock(ctx).getString("detox_start", "") ?: ""
        if (start.isNotEmpty()) lock(ctx).edit().putString("detox_celebrated", start).apply()
    }

    // ----- habit tracker -----

    /** All habits, in creation order. */
    fun habits(ctx: Context): List<Habit> {
        val out = ArrayList<Habit>()
        try {
            val arr = JSONArray(settings(ctx).getString("habits", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Habit(o.getLong("id"), o.getString("n"), o.optLong("c", 0)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "habits read failed", e)
        }
        return out
    }

    fun saveHabits(ctx: Context, list: List<Habit>) {
        val arr = JSONArray()
        for (h in list) {
            val o = JSONObject()
            o.put("id", h.id)
            o.put("n", h.name)
            o.put("c", h.created)
            arr.put(o)
        }
        settings(ctx).edit().putString("habits", arr.toString()).apply()
    }

    fun addHabit(ctx: Context, name: String) {
        val list = habits(ctx).toMutableList()
        list.add(Habit(System.currentTimeMillis(), name, System.currentTimeMillis()))
        saveHabits(ctx, list)
    }

    /** Day keys ("yyyyMMdd") each habit was checked, keyed by habit id. */
    fun habitChecks(ctx: Context): Map<Long, Set<String>> {
        val out = HashMap<Long, Set<String>>()
        try {
            val obj = JSONObject(settings(ctx).getString("habit_checks", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val set = HashSet<String>()
                val arr = obj.getJSONArray(k)
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                out[k.toLong()] = set
            }
        } catch (e: Exception) {
            Log.e(TAG, "habit checks read failed", e)
        }
        return out
    }

    private fun saveHabitChecks(ctx: Context, map: Map<Long, Set<String>>) {
        val obj = JSONObject()
        for ((id, days) in map) {
            val arr = JSONArray()
            for (d in days.sorted().takeLast(365)) arr.put(d)
            obj.put(id.toString(), arr)
        }
        settings(ctx).edit().putString("habit_checks", obj.toString()).apply()
    }

    /** Toggles today's check for one habit. */
    fun toggleHabit(ctx: Context, id: Long) {
        val map = habitChecks(ctx).mapValues { it.value.toMutableSet() }.toMutableMap()
        val set = map.getOrPut(id) { HashSet() }
        val today = dayKey(0)
        if (today in set) set.remove(today) else set.add(today)
        saveHabitChecks(ctx, map)
    }

    /** Consecutive days checked, ending today or yesterday. */
    fun habitStreak(ctx: Context, id: Long): Int {
        val set = habitChecks(ctx)[id] ?: return 0
        fun has(daysAgo: Int) = dayKey(daysAgo) in set
        if (!has(0) && !has(1)) return 0
        var streak = 0
        var i = if (has(0)) 0 else 1
        while (has(i)) {
            streak++
            i++
        }
        return streak
    }

    // ----- focus notes: quick thoughts jotted during a lock -----

    /** All notes, in creation order (oldest first). */
    fun focusNotes(ctx: Context): List<FocusNote> {
        val out = ArrayList<FocusNote>()
        try {
            val arr = JSONArray(settings(ctx).getString("focus_notes", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(FocusNote(o.getLong("id"), o.getString("t"), o.optLong("tm", 0), o.optBoolean("l", false)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "focus notes read failed", e)
        }
        return out
    }

    fun saveFocusNotes(ctx: Context, list: List<FocusNote>) {
        val arr = JSONArray()
        for (n in list.takeLast(100)) {
            val o = JSONObject()
            o.put("id", n.id)
            o.put("t", n.text)
            o.put("tm", n.time)
            o.put("l", n.duringLock)
            arr.put(o)
        }
        settings(ctx).edit().putString("focus_notes", arr.toString()).apply()
    }

    /** Adds a note; capped at 100, the oldest are dropped. Empty text is ignored. */
    fun addFocusNote(ctx: Context, text: String, duringLock: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val list = focusNotes(ctx).toMutableList()
        val now = System.currentTimeMillis()
        list.add(FocusNote(now, clean.take(500), now, duringLock))
        saveFocusNotes(ctx, list)
    }

    fun deleteFocusNote(ctx: Context, id: Long) {
        saveFocusNotes(ctx, focusNotes(ctx).filter { it.id != id })
    }

    /** Lifetime block count (the attempts list is capped at 1000): seeded once from the list. */
    fun totalBlocks(ctx: Context): Long {
        val p = lock(ctx)
        if (!p.contains("blocks_total")) {
            p.edit().putLong("blocks_total", Extra.attempts(ctx).size.toLong()).apply()
        }
        return p.getLong("blocks_total", 0)
    }

    fun dayStart(ms: Long, offsetDays: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }

    fun monthStart(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun sumRange(list: List<Session>, from: Long, to: Long): Long {
        var total = 0L
        for (s in list) {
            if (s.start >= from && s.start < to) total += s.dur
        }
        return total
    }

    // ----- schedules -----

    fun schedules(ctx: Context): List<Schedule> {
        val out = ArrayList<Schedule>()
        try {
            val arr = JSONArray(settings(ctx).getString("schedules", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Schedule(o.getLong("id"), o.getInt("d"), o.getInt("s"), o.getInt("e"), o.getBoolean("on")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "schedules read failed", e)
        }
        return out
    }

    fun saveSchedules(ctx: Context, list: List<Schedule>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("d", x.days)
            o.put("s", x.startMin)
            o.put("e", x.endMin)
            o.put("on", x.enabled)
            arr.put(o)
        }
        settings(ctx).edit().putString("schedules", arr.toString()).apply()
    }
}

// ---------- Pomodoro ----------

/**
 * Pomodoro sets: 25 min focus (normal lock on) + 5 min break (lock off),
 * 4 focus rounds, then a 15 min long break and the set ends. The phase is
 * stored in prefs (phase + round + phase-end), so a restart or a dead
 * service never loses the set — sync() recomputes from the stored end time.
 * Focus intervals reuse the normal lock (Store.startSession/finalize), so
 * they count toward streaks and focus totals like any completed lock.
 */
object Pomodoro {
    const val PHASE_FOCUS = "focus"
    const val PHASE_BREAK = "break"
    const val PHASE_LONG = "longbreak"
    const val FOCUS_MS = 25 * 60_000L
    const val BREAK_MS = 5 * 60_000L
    const val LONG_MS = 15 * 60_000L
    const val ROUNDS = 4

    fun state(ctx: Context): PomState {
        val p = Store.lock(ctx)
        if (!p.getBoolean("pom_active", false)) return PomState(false, 0, PHASE_FOCUS, 0, 0)
        return PomState(
            true,
            p.getInt("pom_round", 1),
            p.getString("pom_phase", PHASE_FOCUS) ?: PHASE_FOCUS,
            p.getLong("pom_phase_end", 0),
            p.getLong("pom_phase_total", FOCUS_MS)
        )
    }

    private fun saveState(ctx: Context, round: Int, phase: String, end: Long, total: Long) {
        Store.lock(ctx).edit()
            .putBoolean("pom_active", true)
            .putInt("pom_round", round)
            .putString("pom_phase", phase)
            .putLong("pom_phase_end", end)
            .putLong("pom_phase_total", total)
            .apply()
    }

    /** Clears the set without touching any running session (used on early stops). */
    fun clearState(ctx: Context) {
        Store.lock(ctx).edit()
            .remove("pom_active").remove("pom_round").remove("pom_phase")
            .remove("pom_phase_end").remove("pom_phase_total")
            .apply()
    }

    /** Starts a set with focus round 1. False when a lock is already running. */
    fun start(ctx: Context): Boolean {
        if (Store.isActive(ctx)) return false
        if (state(ctx).active) return false
        val now = System.currentTimeMillis()
        if (!Store.startSession(ctx, FOCUS_MS, "pomodoro")) return false
        saveState(ctx, 1, PHASE_FOCUS, now + FOCUS_MS, FOCUS_MS)
        return true
    }

    /**
     * Ends the set. During a focus round this also stops the session (the
     * normal early-stop path); strict mode blocks it, like a manual stop.
     * Returns false when strict mode refused.
     */
    fun cancel(ctx: Context): Boolean {
        if (Store.isStrict(ctx)) return false
        val st = state(ctx)
        clearState(ctx)
        if (st.active && st.phase == PHASE_FOCUS) Store.stopSession(ctx)
        return true
    }

    /**
     * Advances the set when the current phase is over. Called from the Home
     * tick (1 s), the service heartbeat (60 s) and the Watchdog (15 min), so
     * phases turn over even with the app closed. One transition per call —
     * the next call advances again if several phases were missed.
     */
    @Synchronized
    fun sync(ctx: Context) {
        try {
            val st = state(ctx)
            if (!st.active) return
            val now = System.currentTimeMillis()
            if (now < st.phaseEnd) return
            when (st.phase) {
                PHASE_FOCUS -> {
                    // the focus session ends now: record it as a completed lock
                    Store.finalizeIfExpired(ctx)
                    if (st.round >= ROUNDS) {
                        saveState(ctx, st.round, PHASE_LONG, now + LONG_MS, LONG_MS)
                        notifyPhase(
                            ctx,
                            L.t("Long break", "লম্বা বিরতি"),
                            L.t("All 4 focus rounds are done. Take a 15-minute break!", "৪ রাউন্ড ফোকাস শেষ! ১৫ মিনিটের লম্বা বিরতি নিন।")
                        )
                    } else {
                        saveState(ctx, st.round, PHASE_BREAK, now + BREAK_MS, BREAK_MS)
                        notifyPhase(
                            ctx,
                            L.t("Break time", "বিরতি"),
                            L.t("Focus round ${st.round} done. 5-minute break!", "ফোকাস রাউন্ড ${st.round} শেষ। ৫ মিনিটের বিরতি!")
                        )
                    }
                }
                PHASE_BREAK -> {
                    val next = st.round + 1
                    if (!Store.startSession(ctx, FOCUS_MS, "pomodoro")) {
                        // the user started something else during the break: the set ends
                        clearState(ctx)
                        return
                    }
                    saveState(ctx, next, PHASE_FOCUS, now + FOCUS_MS, FOCUS_MS)
                    notifyPhase(
                        ctx,
                        L.t("Focus round $next", "ফোকাস রাউন্ড $next"),
                        L.t("Break over — protection is on again.", "বিরতি শেষ — প্রটেকশন আবার চালু।")
                    )
                }
                else -> { // long break over: the set is complete
                    clearState(ctx)
                    notifyPhase(
                        ctx,
                        L.t("Pomodoro complete!", "Pomodoro সম্পন্ন!"),
                        L.t("Great work — the full set is done.", "দারুণ হয়েছে — পুরো সেট শেষ!")
                    )
                }
            }
            LockWidget.refresh(ctx)
            Store.kickService(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "pomodoro sync failed", e)
        }
    }

    private fun notifyPhase(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("powerlock_done", "Session finished", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("powerlock_done_quiet", "Session finished (quiet)", NotificationManager.IMPORTANCE_LOW))
            val channel = if (Store.soundVib(ctx)) "powerlock_done" else "powerlock_done_quiet"
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val nb = Notification.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
            nm.notify(5, nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "pomodoro notify failed", e)
        }
    }
}

// ---------- milestone badges ----------

object Badges {
    val ALL = listOf(
        BadgeDef("first_lock", Ic.CHECK, "First lock", "প্রথম লক", "Complete your first lock", "প্রথম লক সম্পন্ন করুন"),
        BadgeDef("streak_7", Ic.CAL, "7-day streak", "৭ দিনের স্ট্রিক", "Lock every day for 7 days", "টানা ৭ দিন প্রতিদিন লক করুন"),
        BadgeDef("streak_30", Ic.CAL, "30-day streak", "৩০ দিনের স্ট্রিক", "Lock every day for 30 days", "টানা ৩০ দিন প্রতিদিন লক করুন"),
        BadgeDef("locks_10", Ic.LOCK, "10 locks", "১০টা লক", "Complete 10 locks", "১০টা লক সম্পন্ন করুন"),
        BadgeDef("locks_50", Ic.LOCK, "50 locks", "৫০টা লক", "Complete 50 locks", "৫০টা লক সম্পন্ন করুন"),
        BadgeDef("focus_100h", Ic.CLOCK, "100 hours", "১০০ ঘণ্টা", "100 hours of total focus", "মোট ১০০ ঘণ্টা ফোকাস"),
        BadgeDef("detox_done", Ic.SHIELD, "Detox champion", "ডিটক্স চ্যাম্পিয়ন", "Complete a 7-day detox challenge", "৭ দিনের ডিটক্স চ্যালেঞ্জ সম্পন্ন করুন"),
        BadgeDef("blocks_1000", Ic.BARS, "1000 blocks", "১০০০ বার ব্লক", "1000 blocked attempts", "১০০০ বার ব্লক হয়েছে"),
        BadgeDef("wheel_first", Ic.CHECK, "First spin", "প্রথম স্পিন", "Win your first lucky wheel reward", "লাকি হুইল থেকে প্রথম পুরস্কার জিতুন"),
        BadgeDef("wheel_lucky", Ic.SHIELD, "Lucky!", "ভাগ্যবান!", "Win a badge from the lucky wheel", "লাকি হুইল থেকে ব্যাজ জিতুন"),
        BadgeDef("wheel_collector", Ic.BARS, "Collector", "সংগ্রাহক", "Win a tree species and an animal from the wheel", "হুইল থেকে গাছের প্রজাতি ও প্রাণী দুটোই জিতুন"),
        BadgeDef("journey_done", Ic.PIN, "Journey complete", "যাত্রা সম্পূর্ণ", "Walk from Dhaka to Cox's Bazar", "ঢাকা থেকে কক্সবাজার হাঁটুন")
    )

    fun byId(id: String): BadgeDef? = ALL.firstOrNull { it.id == id }

    /** Earned badges: id → timestamp earned. */
    fun earned(ctx: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val obj = JSONObject(Store.lock(ctx).getString("badges", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getLong(k)
            }
        } catch (e: Exception) {
            Log.e(TAG, "badges read failed", e)
        }
        return out
    }

    /** Awards a badge the first time it is earned; sets the one-time dialog flag. */
    fun award(ctx: Context, id: String): Boolean {
        val got = earned(ctx)
        if (got.containsKey(id)) return false
        try {
            val obj = JSONObject(Store.lock(ctx).getString("badges", "{}"))
            obj.put(id, System.currentTimeMillis())
            Store.lock(ctx).edit().putString("badges", obj.toString()).putString("badge_pending", id).apply()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "badge award failed", e)
            return false
        }
    }

    /** Checks every lock-completion milestone. Called whenever a lock completes. */
    fun check(ctx: Context) {
        try {
            val fs = Store.focusStats(ctx)
            if (fs.completed >= 1) award(ctx, "first_lock")
            if (fs.streak >= 7) award(ctx, "streak_7")
            if (fs.streak >= 30) award(ctx, "streak_30")
            if (fs.completed >= 10) award(ctx, "locks_10")
            if (fs.completed >= 50) award(ctx, "locks_50")
            if (fs.totalMin >= 6000) award(ctx, "focus_100h")
            if (FocusJourney.progress(ctx).first >= 1) award(ctx, "journey_done")
            if (Store.detoxState(ctx).mode == "done") award(ctx, "detox_done")
            if (Store.totalBlocks(ctx) >= 1000) award(ctx, "blocks_1000")
        } catch (e: Exception) {
            Log.e(TAG, "badge check failed", e)
        }
    }

    /** A freshly earned badge waiting for its one-time dialog, or null. */
    fun pending(ctx: Context): BadgeDef? {
        val id = Store.lock(ctx).getString("badge_pending", null) ?: return null
        return byId(id)
    }

    fun clearPending(ctx: Context) {
        Store.lock(ctx).edit().remove("badge_pending").apply()
    }
}
