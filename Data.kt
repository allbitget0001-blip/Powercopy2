package com.example.powerlocks

import android.app.AlarmManager
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
    val h = totalMin / 60
    val m = totalMin % 60
    val s = if (h > 0 && m > 0) L.t("${h}h ${m}m", "$h ঘণ্টা $m মিনিট")
    else if (h > 0) L.t("${h}h", "$h ঘণ্টা")
    else L.t("${m}m", "$m মিনিট")
    return L.num(s)
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
        p.edit().putBoolean("active", false).putLong("lastDone", end).apply()
    }

    fun startSession(ctx: Context, durationMs: Long, src: String): Boolean {
        if (isActive(ctx)) return false
        val now = System.currentTimeMillis()
        lock(ctx).edit()
            .putBoolean("active", true)
            .putLong("start", now)
            .putLong("end", now + durationMs)
            .putLong("total", durationMs)
            .putString("src", src)
            .putLong("lastDone", 0)
            .apply()
        kickService(ctx)
        return true
    }

    fun stopSession(ctx: Context) {
        if (!isActive(ctx)) return
        val p = lock(ctx)
        val now = System.currentTimeMillis()
        val start = p.getLong("start", now)
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(start, now, now - start, false, src))
        val w = Scheduler.activeWindowEnd(ctx, now)
        val e = p.edit().putBoolean("active", false).putLong("end", now)
        if (w != null) e.putLong("suppress", w)
        e.apply()
        kickService(ctx)
    }

    fun kickService(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "kickService failed", e)
        }
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
