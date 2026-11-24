package com.powerlock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Unique ids for user-created content (notes, capsules): wall-clock millis,
 * bumped by 1 when two are created in the same millisecond. */
private var lastContentId = 0L
private fun nextContentId(): Long {
    val n = System.currentTimeMillis()
    lastContentId = maxOf(n, lastContentId + 1)
    return lastContentId
}

// =====================================================================
// FOCUS NOTES — short notes about why the user is focusing today
// =====================================================================

data class FocusNote(val id: Long, val text: String, val time: Long)

object FocusNotes {
    private const val KEY = "focus_notes"
    private const val MAX = 100

    /** Newest first. */
    fun list(ctx: Context): List<FocusNote> {
        val out = ArrayList<FocusNote>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(FocusNote(o.getLong("id"), o.getString("t"), o.getLong("time")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "focus notes read failed", e)
        }
        return out
    }

    /** Returns false when the text is blank. */
    fun add(ctx: Context, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val list = list(ctx).toMutableList()
        list.add(0, FocusNote(nextContentId(), t, System.currentTimeMillis()))
        save(ctx, list.take(MAX))
        return true
    }

    fun delete(ctx: Context, id: Long) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    private fun save(ctx: Context, list: List<FocusNote>) {
        try {
            val arr = JSONArray()
            for (n in list) {
                val o = JSONObject()
                o.put("id", n.id)
                o.put("t", n.text)
                o.put("time", n.time)
                arr.put(o)
            }
            Store.settings(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "focus notes save failed", e)
        }
    }
}

// =====================================================================
// FUN FACTS — one fresh focus fact per day, tap for the next one
// =====================================================================

object FunFacts {
    private val ALL = listOf(
        Pair(
            "Your brain can only truly focus on one thing at a time — multitasking is just fast switching.",
            "তোমার মস্তিষ্ক একসাথে সত্যিকার অর্থে একটাই কাজে ফোকাস করতে পারে — মাল্টিটাস্কিং আসলে দ্রুত বদলানো।"
        ),
        Pair(
            "It takes about 23 minutes to fully refocus after a single distraction.",
            "একবার মনোযোগ ভাঙলে আবার পুরো ফোকাস ফিরে পেতে প্রায় ২৩ মিনিট লাগে।"
        ),
        Pair(
            "The average person checks their phone more than 90 times a day.",
            "গড়ে একজন মানুষ দিনে ৯০ বারের বেশি ফোন দেখে।"
        ),
        Pair(
            "Short breaks every 25–30 minutes keep focus sharper than working for hours non-stop.",
            "টানা ঘণ্টার পর ঘণ্টা কাজের চেয়ে প্রতি ২৫–৩০ মিনিটে ছোট বিরতি ফোকাস ধরে রাখে।"
        ),
        Pair(
            "Writing down a goal makes you much more likely to reach it.",
            "লক্ষ্য লিখে রাখলে তা অর্জনের সম্ভাবনা অনেক বেড়ে যায়।"
        ),
        Pair(
            "Your willpower is strongest in the morning — spend it on what matters most.",
            "ইচ্ছাশক্তি সকালে সবচেয়ে শক্তিশালী থাকে — তাই সবচেয়ে গুরুত্বপূর্ণ কাজটা সকালেই করো।"
        ),
        Pair(
            "Silencing notifications can cut phone pickups nearly in half.",
            "নোটিফিকেশন বন্ধ রাখলে ফোন হাতে নেওয়া প্রায় অর্ধেক কমে যায়।"
        ),
        Pair(
            "A tidy desk lowers stress and helps your brain start faster.",
            "গোছানো টেবিল মানসিক চাপ কমায় আর মস্তিষ্ককে দ্রুত শুরু করতে সাহায্য করে।"
        ),
        Pair(
            "Sleep is when your brain files away what you learned — 7–8 hours protects focus.",
            "ঘুমের সময় মস্তিষ্ক শেখা জিনিস গুছিয়ে রাখে — ৭–৮ ঘণ্টা ঘুম ফোকাস রক্ষা করে।"
        ),
        Pair(
            "Drinking water helps concentration — even mild dehydration dulls focus.",
            "পানি খেলে মনোযোগ বাড়ে — সামান্য পানিশূন্যতাও ফোকাস কমিয়ে দেয়।"
        ),
        Pair(
            "The two-minute rule: if a task takes less than two minutes, do it now.",
            "দুই মিনিটের নিয়ম: কোনো কাজ দুই মিনিটের কম সময়ে হলে এখনই করে ফেলো।"
        ),
        Pair(
            "Music without lyrics — like rain or white noise — disturbs focus the least.",
            "কথাহীন সঙ্গীত — যেমন বৃষ্টি বা হোয়াইট নয়েজ — ফোকাসে সবচেয়ে কম ব্যাঘাত ঘটায়।"
        ),
        Pair(
            "Your phone's mere presence on the desk reduces working memory, even face-down.",
            "ফোনটা টেবিলে থাকলেই, উল্টো করে রাখলেও, কাজের স্মৃতি কমে যায়।"
        ),
        Pair(
            "Small wins release dopamine — finishing tiny tasks builds momentum for big ones.",
            "ছোট ছোট সাফল্য ডোপামিন ছাড়ে — ছোট কাজ শেষ করলে বড় কাজের গতি আসে।"
        ),
        Pair(
            "Standing up and stretching for two minutes restores attention.",
            "দুই মিনিট উঠে দাঁড়িয়ে স্ট্রেচ করলে মনোযোগ ফিরে আসে।"
        ),
        Pair(
            "People who plan tomorrow tonight start the day with less decision fatigue.",
            "যারা রাতে পরের দিনের পরিকল্পনা করে, তারা কম সিদ্ধান্ত-ক্লান্তি নিয়ে দিন শুরু করে।"
        ),
        Pair(
            "A 20-minute walk outdoors can improve focus for hours afterwards.",
            "বাইরে ২০ মিনিট হাঁটলে তারপর কয়েক ঘণ্টা ফোকাস ভালো থাকে।"
        ),
        Pair(
            "Saying your goal out loud makes your brain treat it as a commitment.",
            "লক্ষ্যটা জোরে বললে মস্তিষ্ক তাকে প্রতিশ্রুতি হিসেবে নেয়।"
        )
    )

    /** Today's fact (same all day) plus the user's tap offset. */
    fun current(ctx: Context): String {
        val day = System.currentTimeMillis() / 86_400_000L
        val off = Store.settings(ctx).getInt("fun_fact_off", 0)
        val q = ALL[((day + off) % ALL.size).toInt()]
        return L.t(q.first, q.second)
    }

    /** Advance to the next fact and return it. */
    fun next(ctx: Context): String {
        val st = Store.settings(ctx)
        st.edit().putInt("fun_fact_off", st.getInt("fun_fact_off", 0) + 1).apply()
        return current(ctx)
    }
}

// =====================================================================
// TIME CAPSULE — a message to your future self, sealed until its date
// =====================================================================

data class Capsule(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val openAt: Long,
    val opened: Boolean
)

object TimeCapsules {
    private const val KEY = "time_capsules"
    private const val MAX = 50

    /** Newest first. */
    fun list(ctx: Context): List<Capsule> {
        val out = ArrayList<Capsule>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Capsule(
                        o.getLong("id"), o.getString("t"), o.getLong("created"),
                        o.getLong("open"), o.optBoolean("opened", false)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "capsules read failed", e)
        }
        return out
    }

    /**
     * Seals a new capsule. Returns null when the list is full (50) and there
     * is no already-opened capsule to drop — the user must delete one first.
     */
    fun add(ctx: Context, text: String, openAt: Long): Capsule? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val list = list(ctx).toMutableList()
        if (list.size >= MAX) {
            val dropIdx = list.indexOfLast { it.opened }
            if (dropIdx < 0) return null
            val dropped = list.removeAt(dropIdx)
            CapsuleAlarms.cancel(ctx, dropped.id)
        }
        val c = Capsule(nextContentId(), t, System.currentTimeMillis(), openAt, false)
        list.add(0, c)
        save(ctx, list)
        CapsuleAlarms.schedule(ctx, c)
        return c
    }

    fun markOpened(ctx: Context, id: Long) {
        save(ctx, list(ctx).map { if (it.id == id) it.copy(opened = true) else it })
    }

    fun delete(ctx: Context, id: Long) {
        CapsuleAlarms.cancel(ctx, id)
        save(ctx, list(ctx).filter { it.id != id })
    }

    private fun save(ctx: Context, list: List<Capsule>) {
        try {
            val arr = JSONArray()
            for (c in list) {
                val o = JSONObject()
                o.put("id", c.id)
                o.put("t", c.text)
                o.put("created", c.createdAt)
                o.put("open", c.openAt)
                o.put("opened", c.opened)
                arr.put(o)
            }
            Store.settings(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "capsules save failed", e)
        }
    }
}

const val ACTION_CAPSULE = "com.powerlock.app.CAPSULE_OPEN"

/**
 * One AlarmManager alarm per sealed capsule, following the MorningReminder
 * pattern (exact alarm when allowed, otherwise inexact). Each capsule gets
 * its own PendingIntent via a distinct data URI, so scheduling one never
 * overwrites another. BootReceiver + MainActivity re-arm them after a
 * reboot, an update, or a force-stop.
 */
object CapsuleAlarms {
    private const val REQ_CODE = 81
    private const val NOTIF_ID = 5
    private const val CHANNEL_ID = "powerlock_capsule"

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, CapsuleReceiver::class.java)
        i.action = ACTION_CAPSULE
        i.data = Uri.parse("capsule://$id")
        return PendingIntent.getBroadcast(
            ctx, REQ_CODE, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun schedule(ctx: Context, c: Capsule) {
        try {
            if (c.opened) return
            if (c.openAt <= System.currentTimeMillis()) return
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx, c.id)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.openAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.openAt, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "capsule schedule failed", e)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx, id))
        } catch (e: Exception) {
            Log.e(TAG, "capsule cancel failed", e)
        }
    }

    /** Re-arms every sealed capsule's alarm (idempotent — same PendingIntent replaces itself). */
    fun rescheduleAll(ctx: Context) {
        for (c in TimeCapsules.list(ctx)) schedule(ctx, c)
    }

    /** Shows the "your capsule is ready" notification for one capsule. */
    fun fire(ctx: Context, id: Long) {
        try {
            L.load(ctx)
            val c = TimeCapsules.list(ctx).firstOrNull { it.id == id } ?: return
            if (c.opened || c.openAt > System.currentTimeMillis()) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, L.t("Time capsule", "টাইম ক্যাপসুল"), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nb = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(L.t("Your time capsule is ready", "তোমার টাইম ক্যাপসুল প্রস্তুত"))
                .setContentText(L.t("Open PowerLock 2 to read the message you wrote to yourself.", "নিজেকে লেখা মেসেজটি পড়তে PowerLock 2 খুলুন।"))
                .setAutoCancel(true)
                .setContentIntent(open)
            nm.notify(NOTIF_ID, nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "capsule notify failed", e)
        }
    }
}

class CapsuleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val id = intent?.data?.host?.toLongOrNull() ?: return
            CapsuleAlarms.fire(context, id)
        } catch (e: Exception) {
            Log.e(TAG, "CapsuleReceiver failed", e)
        }
    }
}

// =====================================================================
// HOME WALLPAPER — a gallery photo behind the Home screen
// =====================================================================

object Wallpaper {
    private const val KEY = "wallpaper_uri"

    fun uriString(ctx: Context): String? = Store.settings(ctx).getString(KEY, null)

    fun hasWallpaper(ctx: Context): Boolean = uriString(ctx) != null

    fun setUri(ctx: Context, uri: Uri) {
        Store.settings(ctx).edit().putString(KEY, uri.toString()).apply()
    }

    fun clear(ctx: Context) {
        val raw = uriString(ctx)
        Store.settings(ctx).edit().remove(KEY).apply()
        if (raw != null) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // already released — ignore
            }
        }
    }

    /**
     * Decodes the stored photo, downscaled so its longest side is ~1080 px.
     * Returns null when there is no photo or it can no longer be read
     * (permission revoked, file deleted): the caller falls back to the plain
     * background. A dead URI is forgotten so we stop retrying it.
     */
    fun loadBitmap(ctx: Context): Bitmap? {
        val raw = uriString(ctx) ?: return null
        try {
            val uri = Uri.parse(raw)
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) {
                Store.settings(ctx).edit().remove(KEY).apply()
                return null
            }
            var sample = 1
            while (longest / sample > 1080) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp == null) {
                Store.settings(ctx).edit().remove(KEY).apply()
            }
            return bmp
        } catch (e: SecurityException) {
            // permission revoked: forget the URI, fall back to plain background
            Log.e(TAG, "wallpaper permission lost", e)
            Store.settings(ctx).edit().remove(KEY).apply()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "wallpaper load failed", e)
            return null
        }
    }

    /**
     * Wraps the Home ScrollView with the photo behind it (center-cropped)
     * plus a dark scrim so text stays readable. Returns the ScrollView
     * unchanged when there is no usable photo.
     */
    fun wrap(ctx: Context, sv: ScrollView): View {
        val bmp = loadBitmap(ctx) ?: return sv
        val frame = FrameLayout(ctx)
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(bmp)
        frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(ctx)
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0))
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }
}

// =====================================================================
// VOICE QUOTES — today's motivational quote, spoken aloud (TTS)
// =====================================================================

/**
 * Speaks today's quote with TextToSpeech: Bengali when the UI is Bengali
 * and a Bengali voice is installed, otherwise the English quote with an
 * English voice. Missing TTS engine or init failure shows a toast, never
 * a crash. Speech stops when leaving the screen (MainActivity calls stop()
 * on tab change and onPause).
 */
object VoiceQuotes {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingSpeak = false

    fun speak(ctx: Context) {
        try {
            val t = tts
            if (t == null) {
                pendingSpeak = true
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    try {
                        if (status == TextToSpeech.SUCCESS) {
                            ready = true
                            if (pendingSpeak) {
                                pendingSpeak = false
                                pickAndSpeak(ctx.applicationContext)
                            }
                        } else {
                            pendingSpeak = false
                            Toast.makeText(
                                ctx.applicationContext,
                                L.t("Text-to-speech is not available on this phone", "এই ফোনে টেক্সট-টু-স্পিচ নেই"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "voice quote init failed", e)
                    }
                }
                return
            }
            if (!ready) {
                pendingSpeak = true
                return
            }
            pickAndSpeak(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "voice quote failed", e)
            Toast.makeText(
                ctx,
                L.t("Text-to-speech is not available on this phone", "এই ফোনে টেক্সট-টু-স্পিচ নেই"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun pickAndSpeak(ctx: Context) {
        val t = tts ?: return
        val (en, bn) = Quotes.todayPair()
        var useBn = false
        if (L.bn) {
            try {
                val r = t.isLanguageAvailable(Locale("bn"))
                useBn = r == TextToSpeech.LANG_AVAILABLE ||
                    r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            } catch (e: Exception) {
                useBn = false
            }
        }
        try {
            if (useBn) {
                t.language = Locale("bn")
                t.speak(bn, TextToSpeech.QUEUE_FLUSH, null, "quote")
            } else {
                t.language = Locale.ENGLISH
                t.speak(en, TextToSpeech.QUEUE_FLUSH, null, "quote")
            }
        } catch (e: Exception) {
            Log.e(TAG, "voice quote speak failed", e)
            Toast.makeText(
                ctx, L.t("Could not speak the quote", "উক্তিটি পড়া যায়নি"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ready = false
        pendingSpeak = false
    }
}
