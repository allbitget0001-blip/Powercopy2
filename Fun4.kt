package com.powerlock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

// =====================================================================
// TIME CAPSULE
// =====================================================================

/** A letter to the user's future self. Sealed until [open]; [notified] marks the fired notification. */
data class TimeCapsule(
    val id: Long,
    val text: String,
    val created: Long,
    val open: Long,
    val opened: Boolean,
    val notified: Boolean
)

/**
 * Time capsule: write a letter, seal it until a chosen date. The content
 * stays hidden until the open date; on the date a notification fires and
 * the capsule can be opened and read. Stored as a JSON list in prefs.
 */
object TimeCapsules {
    private const val KEY = "time_capsules"
    private const val MAX_CAPSULES = 50
    private const val MAX_TEXT = 1000

    fun all(ctx: Context): List<TimeCapsule> {
        val out = ArrayList<TimeCapsule>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    TimeCapsule(
                        o.getLong("id"), o.getString("t"), o.optLong("c", 0),
                        o.getLong("o"), o.optBoolean("d", false), o.optBoolean("n", false)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "capsules read failed", e)
        }
        return out
    }

    private fun save(ctx: Context, list: List<TimeCapsule>) {
        val arr = JSONArray()
        for (c in list.takeLast(MAX_CAPSULES)) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("t", c.text)
            o.put("c", c.created)
            o.put("o", c.open)
            o.put("d", c.opened)
            o.put("n", c.notified)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Seals a new letter. Returns false when the text is empty. */
    fun add(ctx: Context, text: String, openMs: Long): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val now = System.currentTimeMillis()
        val list = all(ctx).toMutableList()
        list.add(TimeCapsule(now, clean.take(MAX_TEXT), now, openMs, false, false))
        save(ctx, list)
        return true
    }

    fun markOpened(ctx: Context, id: Long) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(opened = true) else it })
    }

    fun delete(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    fun isDue(c: TimeCapsule, now: Long = System.currentTimeMillis()): Boolean = now >= c.open

    /** "3 days left" / "Opens today" / "Open now!" — never reveals the content. */
    fun countdown(c: TimeCapsule, now: Long = System.currentTimeMillis()): String {
        if (isDue(c, now)) return L.t("Open now!", "এখন খুলুন!")
        val days = ceil((c.open - now).toDouble() / 86_400_000.0).toInt().coerceAtLeast(1)
        return if (days == 1) L.t("Opens today", "আজ খুলবে")
        else L.num(days.toString()) + " " + L.t("days left", "দিন বাকি")
    }

    /** Notifies once per capsule when its date arrives. Called on app open, boot, and the Home tick. */
    fun checkDue(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val list = all(ctx)
            var changed = false
            val updated = list.map { c ->
                if (isDue(c, now) && !c.notified) {
                    changed = true
                    Reminders.show(
                        ctx, 30 + (c.id % 50).toInt(),
                        L.t("Your time capsule opened!", "তোমার টাইম ক্যাপসুল খুলে গেছে!"),
                        L.t("A letter from your past self is ready to read.", "তোমার অতীতের নিজের চিঠি পড়ার সময় হয়েছে।")
                    )
                    c.copy(notified = true)
                } else c
            }
            if (changed) save(ctx, updated)
        } catch (e: Exception) {
            Log.e(TAG, "capsule due check failed", e)
        }
    }
}

// =====================================================================
// SOUND MIXER
// =====================================================================

/**
 * Sound mixer: per-channel volumes (0–100) for the four generated focus
 * sounds, played together during a lock when the "My mix" choice is on.
 * Ocean is only mixable once the lucky wheel has unlocked it.
 */
object SoundMixer {
    val CHANNELS = listOf(FocusSound.WHITE, FocusSound.BROWN, FocusSound.RAIN, FocusSound.OCEAN)

    private fun key(ch: String) = "mix_$ch"

    private fun defaultVol(ch: String): Int = when (ch) {
        FocusSound.WHITE -> 50
        FocusSound.RAIN -> 50
        else -> 0
    }

    /** Stored volumes for all four channels (0–100). */
    fun volumes(ctx: Context): Map<String, Int> {
        val p = Store.settings(ctx)
        return CHANNELS.associateWith { p.getInt(key(it), defaultVol(it)).coerceIn(0, 100) }
    }

    fun save(ctx: Context, vols: Map<String, Int>) {
        val e = Store.settings(ctx).edit()
        for (ch in CHANNELS) e.putInt(key(ch), (vols[ch] ?: 0).coerceIn(0, 100))
        e.apply()
    }

    /** Channels that actually play: volume > 0, Ocean only when unlocked. Volume is a 0–1 AudioTrack gain. */
    fun activeChannels(ctx: Context, override: Map<String, Int>? = null): List<Pair<String, Float>> {
        val vols = override ?: volumes(ctx)
        val out = ArrayList<Pair<String, Float>>()
        for (ch in CHANNELS) {
            val v = (vols[ch] ?: 0).coerceIn(0, 100)
            if (v <= 0) continue
            if (ch == FocusSound.OCEAN && !LuckyWheel.isOceanUnlocked(ctx)) continue
            out.add(ch to (v / 100f) * 0.6f)
        }
        return out
    }

    /** One-line summary for the Settings row ("White 50% · Rain 50%"). */
    fun summary(ctx: Context): String {
        val vols = volumes(ctx)
        val parts = CHANNELS.filter { (vols[it] ?: 0) > 0 && (it != FocusSound.OCEAN || LuckyWheel.isOceanUnlocked(ctx)) }
            .map { FocusSound.label(it) + " " + L.num((vols[it] ?: 0).toString()) + "%" }
        return if (parts.isEmpty()) L.t("No sound in the mix yet", "মিক্সে এখনো কোনো সাউন্ড নেই")
        else parts.joinToString(" · ")
    }
}

// =====================================================================
// MOOD TRACKER
// =====================================================================

/**
 * Mood tracker: a daily 1–5 log on Home, plus an optional after-lock mood
 * in the session-complete flow. Daily logs drive the 7-day graph; the
 * comparison pits the average after-lock mood against the average daily
 * mood (only when there is enough data to mean anything).
 */
object Mood {
    const val MIN = 1
    const val MAX = 5

    fun label(v: Int): String = when (v.coerceIn(MIN, MAX)) {
        1 -> L.t("Very bad", "খুব খারাপ")
        2 -> L.t("Bad", "খারাপ")
        3 -> L.t("Okay", "ঠিকঠাক")
        4 -> L.t("Good", "ভালো")
        else -> L.t("Very good", "খুব ভালো")
    }

    private fun dayKey(ctx: Context, daysAgo: Int): String = Store.dayKey(daysAgo)

    fun logDaily(ctx: Context, v: Int) {
        Store.settings(ctx).edit()
            .putInt("mood_day_" + dayKey(ctx, 0), v.coerceIn(MIN, MAX))
            .apply()
        prune(ctx)
    }

    fun todayDaily(ctx: Context): Int? {
        val p = Store.settings(ctx)
        val k = "mood_day_" + dayKey(ctx, 0)
        return if (p.contains(k)) p.getInt(k, 0).takeIf { it in MIN..MAX } else null
    }

    /** Appends an after-lock mood for today. */
    fun logAfter(ctx: Context, v: Int) {
        val p = Store.settings(ctx)
        val k = "mood_after_" + dayKey(ctx, 0)
        val cur = p.getString(k, "") ?: ""
        val next = if (cur.isEmpty()) v.toString() else "$cur,${v.coerceIn(MIN, MAX)}"
        p.edit().putString(k, next.take(200)).apply()
        prune(ctx)
    }

    private fun afterList(ctx: Context, daysAgo: Int): List<Int> {
        val raw = Store.settings(ctx).getString("mood_after_" + dayKey(ctx, daysAgo), "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { it.toIntOrNull() }.filter { it in MIN..MAX }
    }

    private fun dailyValue(ctx: Context, daysAgo: Int): Int? {
        val p = Store.settings(ctx)
        val k = "mood_day_" + dayKey(ctx, daysAgo)
        return if (p.contains(k)) p.getInt(k, 0).takeIf { it in MIN..MAX } else null
    }

    /** The day's mood for the graph: the daily log, else the after-lock average. */
    fun dayValue(ctx: Context, daysAgo: Int): Float? {
        val d = dailyValue(ctx, daysAgo)
        if (d != null) return d.toFloat()
        val a = afterList(ctx, daysAgo)
        return if (a.isNotEmpty()) a.average().toFloat() else null
    }

    /** 7 values, oldest first (6 days ago … today), null for days with no mood. */
    fun weekValues(ctx: Context): List<Float?> = (6 downTo 0).map { dayValue(ctx, it) }

    /** Daily-log average vs after-lock average over the last 30 days (null when too little data). */
    fun comparison(ctx: Context): String? {
        val daily = ArrayList<Int>()
        val after = ArrayList<Int>()
        for (i in 0 until 30) {
            dailyValue(ctx, i)?.let { daily.add(it) }
            after.addAll(afterList(ctx, i))
        }
        if (daily.size < 3 || after.size < 3) return null
        val dAvg = daily.average()
        val aAvg = after.average()
        val diff = aAvg - dAvg
        fun one(x: Double): String = L.num(String.format(Locale.US, "%.1f", x))
        val nums = " (${one(dAvg)} → ${one(aAvg)})"
        return when {
            diff >= 0.3 -> L.t("Your mood is usually better after a lock", "লকের পর মুড গড়ে ভালো হয়") + nums
            diff <= -0.3 -> L.t("Your mood is usually worse after a lock", "লকের পর মুড গড়ে খারাপ হয়") + nums
            else -> L.t("Your mood stays about the same after a lock", "লকের পর মুড প্রায় একই থাকে") + nums
        }
    }

    /** Keeps 30 days of mood data; older days are dropped. */
    private fun prune(ctx: Context) {
        try {
            val e = Store.settings(ctx).edit()
            for (i in 31..60) {
                e.remove("mood_day_" + dayKey(ctx, i))
                e.remove("mood_after_" + dayKey(ctx, i))
            }
            e.apply()
        } catch (e: Exception) {
            Log.e(TAG, "mood prune failed", e)
        }
    }
}

/** 7-day mood graph: dots + a line of the daily mood (1–5), with day labels. */
class MoodGraphView(ctx: Context) : View(ctx) {
    var values: List<Float?> = listOf()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || values.size < 2) return
        val padL = context.dp(28).toFloat()
        val padR = context.dp(10).toFloat()
        val padT = context.dp(8).toFloat()
        val padB = context.dp(24).toFloat()
        val left = padL
        val right = w - padR
        val top = padT
        val bottom = h - padB
        fun xFor(i: Int): Float = left + (right - left) * i / (values.size - 1).toFloat()
        fun yFor(v: Float): Float = bottom - (bottom - top) * (v - 1f) / 4f

        // grid lines at 1 / 3 / 5 with labels
        textPaint.textSize = context.dp(11).toFloat()
        textPaint.textAlign = Paint.Align.RIGHT
        for (g in listOf(1f, 3f, 5f)) {
            val y = yFor(g)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = C.border
            canvas.drawLine(left, y, right, y, paint)
            textPaint.color = C.muted
            canvas.drawText(L.num(g.toInt().toString()), left - context.dp(6), y + context.dp(4), textPaint)
        }

        // connecting line (breaks at days with no mood)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(3).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = C.primary
        var prev: Pair<Float, Float>? = null
        for ((i, v) in values.withIndex()) {
            if (v == null) {
                prev = null
                continue
            }
            val x = xFor(i)
            val y = yFor(v)
            val p = prev
            if (p != null) canvas.drawLine(p.first, p.second, x, y, paint)
            prev = x to y
        }

        // dots + day labels
        textPaint.textAlign = Paint.Align.CENTER
        for ((i, v) in values.withIndex()) {
            val x = xFor(i)
            if (v != null) {
                paint.style = Paint.Style.FILL
                paint.color = C.mint
                canvas.drawCircle(x, yFor(v), context.dp(5).toFloat(), paint)
            }
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, i - (values.size - 1))
            textPaint.color = C.muted
            canvas.drawText(L.dayShort(dayIdx(c)), x, h - context.dp(4), textPaint)
        }
    }
}

// =====================================================================
// VOICE QUOTES (TTS)
// =====================================================================

/**
 * Voice quotes: reads the lock-end quote aloud with the system TextToSpeech
 * engine (no downloads). Prefers the Bengali voice for the Bengali quote;
 * falls back to the English quote when the device has no Bengali voice.
 * TTS is created lazily and shut down from MainActivity.onDestroy.
 */
object VoiceQuotes {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Triple<Context, String, String>? = null

    fun speak(ctx: Context, en: String, bn: String) {
        try {
            if (failed) {
                toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
                return
            }
            if (tts == null) {
                pending = Triple(ctx.applicationContext, en, bn)
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ready = true
                        val p = pending
                        pending = null
                        if (p != null) speakNow(p.first, p.second, p.third)
                    } else {
                        failed = true
                        pending = null
                        toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
                    }
                }
                return
            }
            if (!ready) {
                pending = Triple(ctx.applicationContext, en, bn)
                return
            }
            speakNow(ctx.applicationContext, en, bn)
        } catch (e: Exception) {
            Log.e(TAG, "voice quote failed", e)
            toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
        }
    }

    private fun speakNow(ctx: Context, en: String, bn: String) {
        val engine = tts ?: return
        try {
            if (L.bn) {
                val avail = engine.isLanguageAvailable(Locale("bn"))
                if (avail == TextToSpeech.LANG_AVAILABLE ||
                    avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    engine.language = Locale("bn")
                    engine.speak(bn, TextToSpeech.QUEUE_FLUSH, null, "quote")
                    return
                }
                // no Bengali voice: fall back to English, and say so once
                val p = Store.settings(ctx)
                if (!p.getBoolean("tts_bn_warned", false)) {
                    p.edit().putBoolean("tts_bn_warned", true).apply()
                    toast(ctx, L.t("No Bengali voice on this phone — reading in English", "এই ফোনে বাংলা ভয়েস নেই — ইংরেজিতে শোনাচ্ছি"))
                }
            }
            engine.language = Locale.ENGLISH
            engine.speak(en, TextToSpeech.QUEUE_FLUSH, null, "quote")
        } catch (e: Exception) {
            Log.e(TAG, "voice quote speak failed", e)
            toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
        }
    }

    /** Stops any speech and releases the engine. Called from MainActivity.onDestroy. */
    fun shutdown() {
        try {
            pending = null
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "voice quote shutdown failed", e)
        }
        tts = null
        ready = false
        failed = false
    }

    private fun toast(ctx: Context, m: String) {
        try {
            android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "voice quote toast failed", e)
        }
    }
}
