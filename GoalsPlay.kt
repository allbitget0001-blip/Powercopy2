package com.powerlock.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject

/** Unique ids for routine tasks: wall-clock millis, bumped by 1 when two
 * are created in the same millisecond. */
private var lastRoutineId = 0L
private fun nextRoutineId(): Long {
    val n = System.currentTimeMillis()
    lastRoutineId = maxOf(n, lastRoutineId + 1)
    return lastRoutineId
}

// =====================================================================
// DAILY ROUTINE — a standing task checklist, plus a 30-minute
// consequence lock when the day ends with tasks undone.
//
// This is a daily task list, NOT a habit tracker: there are no habit
// streaks and no habit UI. A task is either done today or it is not.
//
// The consequence trigger is deliberately conservative — it must NEVER
// fire spuriously. It fires only when ALL of these hold:
//   1. the routine is enabled, right now;
//   2. a snapshot proves tasks existed YESTERDAY (no snapshot = no fire);
//   3. at least one of yesterday's tasks was not done;
//   4. yesterday has not already been settled (full or fired);
//   5. it is still morning (first 12 hours after midnight);
//   6. no lock session is running.
// The settled mark is written BEFORE the lock starts, so the consequence
// for one day can fire at most once, even if the app is killed mid-fire.
// =====================================================================

/** One standing routine task: the same list repeats every day. */
data class RoutineTask(val id: Long, val text: String)

object Routine {
    /** Length of the consequence lock, in minutes. */
    const val CONSEQUENCE_MIN = 30
    private const val MAX_TASKS = 20
    private const val KEY_ON = "routine_on"
    private const val KEY_TASKS = "routine_tasks"
    private const val KEY_DONE = "routine_done"
    private const val KEY_SNAP = "routine_snap"
    private const val KEY_SETTLED = "routine_settled"
    private const val NOTIF_ID = 6
    private const val CHANNEL_ID = "powerlock_routine"
    /** The consequence may only fire in the first 12 hours of the new day:
     * it must follow the missed day closely, never days later. */
    private const val FIRE_WINDOW_MS = 12 * 60 * 60_000L

    fun isOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean(KEY_ON, on).apply()
        // Enabling starts the record today, so the first day is covered
        // from the moment the user opts in.
        if (on) snapshotToday(ctx)
    }

    // ----- tasks -----

    fun list(ctx: Context): List<RoutineTask> {
        val out = ArrayList<RoutineTask>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY_TASKS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RoutineTask(o.getLong("id"), o.getString("t")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine tasks read failed", e)
        }
        return out
    }

    private fun saveTasks(ctx: Context, list: List<RoutineTask>) {
        try {
            val arr = JSONArray()
            for (t in list) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("t", t.text)
                arr.put(o)
            }
            Store.settings(ctx).edit().putString(KEY_TASKS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "routine tasks save failed", e)
        }
        // Any edit changes what "today's tasks" means: re-snapshot at once.
        snapshotToday(ctx, list)
    }

    /** Returns false when the text is blank or the list is full. */
    fun add(ctx: Context, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val cur = list(ctx)
        if (cur.size >= MAX_TASKS) return false
        saveTasks(ctx, cur + RoutineTask(nextRoutineId(), t))
        return true
    }

    /** Returns false when the text is blank or the task is gone. */
    fun update(ctx: Context, id: Long, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val cur = list(ctx)
        if (cur.none { it.id == id }) return false
        saveTasks(ctx, cur.map { if (it.id == id) it.copy(text = t) else it })
        return true
    }

    fun delete(ctx: Context, id: Long) {
        saveTasks(ctx, list(ctx).filter { it.id != id })
    }

    // ----- per-day maps (day key -> task ids), pruned to 90 days -----

    private fun keep90(): Set<String> = (0 until 90).map { Store.dayKey(it) }.toSet()

    private fun readIdMap(ctx: Context, key: String): MutableMap<String, MutableList<Long>> {
        val out = HashMap<String, MutableList<Long>>()
        try {
            val obj = JSONObject(Store.settings(ctx).getString(key, "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.getJSONArray(k)
                val list = ArrayList<Long>()
                for (i in 0 until arr.length()) list.add(arr.getLong(i))
                out[k] = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine $key read failed", e)
        }
        return out
    }

    private fun saveIdMap(ctx: Context, key: String, map: Map<String, List<Long>>) {
        try {
            val keep = keep90()
            val obj = JSONObject()
            for ((k, v) in map) {
                if (k !in keep) continue
                val arr = JSONArray()
                for (x in v) arr.put(x)
                obj.put(k, arr)
            }
            Store.settings(ctx).edit().putString(key, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "routine $key save failed", e)
        }
    }

    /** Task ids marked done on one day ("yyyyMMdd"). */
    fun doneIds(ctx: Context, day: String): Set<Long> =
        readIdMap(ctx, KEY_DONE)[day]?.toSet() ?: emptySet()

    fun doneToday(ctx: Context): Set<Long> = doneIds(ctx, Store.dayKey(0))

    /** Toggles one task's done state for today. */
    fun toggleDone(ctx: Context, id: Long) {
        val day = Store.dayKey(0)
        val map = readIdMap(ctx, KEY_DONE)
        val cur = map[day]?.toMutableList() ?: ArrayList()
        if (id in cur) cur.remove(id) else cur.add(id)
        map[day] = cur
        saveIdMap(ctx, KEY_DONE, map)
    }

    /**
     * Records which tasks exist today. Written on every check pass and on
     * every edit, so tomorrow's check knows exactly which tasks existed
     * today — even if the app stayed closed all day (the Watchdog runs
     * the check every 15 minutes). Writes only when the list changed.
     */
    fun snapshotToday(ctx: Context, tasks: List<RoutineTask> = list(ctx)) {
        val day = Store.dayKey(0)
        val map = readIdMap(ctx, KEY_SNAP)
        val ids = tasks.map { it.id }
        if (map[day] == ids) return
        map[day] = ids.toMutableList()
        saveIdMap(ctx, KEY_SNAP, map)
    }

    private fun snapshotFor(ctx: Context, day: String): List<Long>? =
        readIdMap(ctx, KEY_SNAP)[day]

    // ----- settled days: day key -> "full" (all done) or "fired" -----

    /** Days already decided: "full" when every task was done, "fired" when
     * the consequence lock started. A settled day is never re-evaluated. */
    fun settled(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val obj = JSONObject(Store.settings(ctx).getString(KEY_SETTLED, "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine settled read failed", e)
        }
        return out
    }

    private fun markSettled(ctx: Context, day: String, how: String) {
        try {
            val map = settled(ctx).toMutableMap()
            map[day] = how
            val keep = keep90()
            val obj = JSONObject()
            for ((k, v) in map) if (k in keep) obj.put(k, v)
            Store.settings(ctx).edit().putString(KEY_SETTLED, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "routine settled save failed", e)
        }
    }

    /** Full-routine days in the last 7 days (today included). */
    fun fullDaysLast7(ctx: Context): Int {
        val s = settled(ctx)
        return (0 until 7).count { s[Store.dayKey(it)] == "full" }
    }

    // ----- the day-end check -----

    /**
     * Evaluates YESTERDAY and, only when every guard holds, starts the
     * 30-minute strict consequence lock. Called on app open (MainActivity)
     * and every 15 minutes by the Watchdog, so it also fires while the
     * app is closed. Safe to call often: it returns early in every case
     * where firing would be wrong, and each day settles at most once.
     */
    @Synchronized
    fun checkDayEnd(ctx: Context) {
        try {
            if (!isOn(ctx)) return
            val tasks = list(ctx)
            snapshotToday(ctx, tasks)
            val now = System.currentTimeMillis()
            // Morning only: a consequence must follow the missed day closely.
            if (now - Store.dayStart(now, 0) > FIRE_WINDOW_MS) return
            val yday = Store.dayKey(1)
            if (settled(ctx).containsKey(yday)) return
            // No snapshot for yesterday means we cannot prove tasks existed
            // then (routine was off, or no tasks yet): never fire on a guess.
            val snap = snapshotFor(ctx, yday) ?: return
            if (snap.isEmpty()) return
            val done = doneIds(ctx, yday)
            if (snap.all { it in done }) {
                // Every task done: a full day — record it, never fire.
                markSettled(ctx, yday, "full")
                return
            }
            // A lock is already running: wait for a later pass (still morning).
            if (Store.isActive(ctx)) return
            // Fire, exactly once: record the decision BEFORE starting the lock.
            markSettled(ctx, yday, "fired")
            val missed = snap.count { it !in done }
            val ok = Store.startSession(ctx, CONSEQUENCE_MIN * 60_000L, "routine")
            if (ok) {
                // A consequence lock is always strict: it cannot be stopped
                // early, whatever the user's normal strict setting is.
                Store.lock(ctx).edit().putBoolean("s_strict", true).apply()
                notifyConsequence(ctx, missed)
            }
            // If startSession failed (a session began in between), the
            // settled mark stands: one attempt per day, never a retry loop.
        } catch (e: Exception) {
            Log.e(TAG, "routine day-end check failed", e)
        }
    }

    private fun notifyConsequence(ctx: Context, missed: Int) {
        try {
            L.load(ctx)
            if (!Perm.notificationsOn(ctx)) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, L.t("Daily routine", "দৈনিক রুটিন"), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = if (missed == 1) {
                L.t(
                    "1 task left undone yesterday — a 30-minute lock has started.",
                    "গতকাল ১টি কাজ বাকি ছিল — ৩০ মিনিটের লক শুরু হয়েছে।"
                )
            } else {
                L.num(
                    L.t(
                        "$missed tasks left undone yesterday — a 30-minute lock has started.",
                        "$missed টি কাজ গতকাল বাকি ছিল — ৩০ মিনিটের লক শুরু হয়েছে।"
                    )
                )
            }
            val nb = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(L.t("Routine consequence lock", "রুটিনের শাস্তি লক"))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
            nm.notify(NOTIF_ID, nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "routine notify failed", e)
        }
    }
}

// =====================================================================
// LUCKY WHEEL — 1 spin per completed lock, max 5 banked. Rewards are
// cosmetic only: a garden plant, a pet treat, a lucky quote, a fun fact,
// or a lucky star. NEVER break minutes, NEVER lock time — a reward must
// not be able to weaken any protection.
// =====================================================================

/** One wheel segment: short labels, so the text fits inside its slice. */
data class WheelReward(
    val id: String,
    val color: Int,
    val labelEn: String,
    val labelBn: String
)

object LuckyWheel {
    /** Spins stop banking here: complete locks to earn more, spin to spend. */
    const val MAX_BANKED = 5
    private const val KEY_SPINS = "wheel_spins"
    private const val KEY_SEEN = "wheel_seen"
    private const val KEY_STARS = "wheel_stars"
    private const val KEY_LAST = "wheel_last"

    val REWARDS = listOf(
        WheelReward("flower", Color.parseColor("#F472B6"), "Flower", "ফুল"),
        WheelReward("treat", Color.parseColor("#FBBF24"), "Treat", "ট্রিট"),
        WheelReward("quote", Color.parseColor("#38BDF8"), "Quote", "উক্তি"),
        WheelReward("fact", Color.parseColor("#A78BFA"), "Fact", "তথ্য"),
        WheelReward("bush", Color.parseColor("#2E9E5B"), "Bush", "ঝোপ"),
        WheelReward("star", Color.parseColor("#FF9F43"), "Star", "তারা")
    )

    private val LUCKY_LINES = listOf(
        Pair(
            "A small step today is a big journey tomorrow.",
            "আজকের ছোট পদক্ষেপ, কালকের বড় যাত্রা।"
        ),
        Pair(
            "Luck favours the focused.",
            "ভাগ্য ফোকাস করা মানুষের পক্ষে থাকে।"
        ),
        Pair(
            "Your future self is already proud of you.",
            "তোমার ভবিষ্যতের তুমি ইতিমধ্যে তোমাকে নিয়ে গর্বিত।"
        ),
        Pair(
            "Calm is a superpower — you are building it daily.",
            "শান্ত থাকা একটা সুপারপাওয়ার — তুমি প্রতিদিন তা গড়ছো।"
        ),
        Pair(
            "The best time to focus was yesterday. The second best is now.",
            "ফোকাস করার সেরা সময় ছিল গতকাল। দ্বিতীয় সেরা এখন।"
        ),
        Pair(
            "Every lock is a promise you kept to yourself.",
            "প্রতিটি লক নিজের কাছে রাখা একটা প্রতিশ্রুতি।"
        )
    )

    /** Spins banked right now. Syncs first: every completed lock since the
     * last sync earns one spin, up to the cap. Old locks backfill at once. */
    fun spinsAvailable(ctx: Context): Int {
        val st = Store.settings(ctx)
        val completed = Store.focusStats(ctx).completed
        val seen = st.getInt(KEY_SEEN, 0)
        val banked = st.getInt(KEY_SPINS, 0)
        val add = (completed - seen).coerceAtLeast(0)
        val total = (banked + add).coerceAtMost(MAX_BANKED)
        if (add > 0 || total != banked) {
            st.edit().putInt(KEY_SEEN, completed).putInt(KEY_SPINS, total).apply()
        }
        return total
    }

    /** Spends one banked spin. Returns false when none is left. */
    fun useSpin(ctx: Context): Boolean {
        val n = spinsAvailable(ctx)
        if (n <= 0) return false
        Store.settings(ctx).edit().putInt(KEY_SPINS, n - 1).apply()
        return true
    }

    /** Lucky stars won so far (cosmetic counter). */
    fun stars(ctx: Context): Int = Store.settings(ctx).getInt(KEY_STARS, 0)

    /** Last reward: reward id + time won, or null when never spun. */
    fun lastReward(ctx: Context): Pair<String, Long>? {
        return try {
            val raw = Store.settings(ctx).getString(KEY_LAST, null) ?: return null
            val o = JSONObject(raw)
            Pair(o.getString("id"), o.getLong("t"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Applies a reward and returns the line shown in the result dialog.
     * Every effect is isolated to its own feature: a garden plant (via
     * Garden.addBonus, which never touches lock plants), a pet treat
     * (a feeding that does not reset the meal cooldown), a lucky star
     * counter, or text only (quote / fun fact).
     */
    fun applyReward(ctx: Context, reward: WheelReward): String {
        try {
            when (reward.id) {
                "flower" -> Garden.addBonus(ctx, "flower")
                "bush" -> Garden.addBonus(ctx, "bush")
                "treat" -> Pet.treat(ctx)
                "star" -> {
                    val st = Store.settings(ctx)
                    st.edit().putInt(KEY_STARS, st.getInt(KEY_STARS, 0) + 1).apply()
                }
                else -> { /* quote + fact are text only; shown in the dialog */ }
            }
            val o = JSONObject()
            o.put("id", reward.id)
            o.put("t", System.currentTimeMillis())
            Store.settings(ctx).edit().putString(KEY_LAST, o.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "wheel reward failed", e)
        }
        return when (reward.id) {
            "flower" -> L.t("A new flower blooms in your garden!", "তোমার বাগানে একটা নতুন ফুল ফুটল!")
            "bush" -> L.t("A new bush grows in your garden!", "তোমার বাগানে একটা নতুন ঝোপ গজাল!")
            "treat" -> L.t("Mimi got a tasty treat!", "মিমি একটা মজার ট্রিট পেল!")
            "star" -> L.t("A lucky star is yours now.", "একটা ভাগ্যের তারা এখন তোমার।")
            "fact" -> FunFacts.next(ctx)
            else -> {
                val q = LUCKY_LINES.random()
                L.t(q.first, q.second)
            }
        }
    }
}

// =====================================================================
// FOCUS JOURNEY — Dhaka to Cox's Bazar, 400 km. Progress is cumulative
// travelled distance from the route plumbing (Store.distanceForDay).
// Each day's distance is counted once; days pruned from the 30-day
// route store are banked, so progress is never lost.
// =====================================================================

/** One stop on the road to Cox's Bazar, at [km] from Dhaka. */
data class JourneyStop(val km: Int, val nameEn: String, val nameBn: String)

object Journey {
    /** Dhaka to Cox's Bazar by road, in kilometres. */
    const val TOTAL_KM = 400
    private const val KEY_DAYS = "journey_days"
    private const val KEY_BANKED = "journey_banked_m"

    val STOPS = listOf(
        JourneyStop(0, "Dhaka", "ঢাকা"),
        JourneyStop(20, "Narayanganj", "নারায়ণগঞ্জ"),
        JourneyStop(55, "Daudkandi", "দাউদকান্দি"),
        JourneyStop(100, "Cumilla", "কুমিল্লা"),
        JourneyStop(160, "Feni", "ফেনী"),
        JourneyStop(250, "Chattogram", "চট্টগ্রাম"),
        JourneyStop(275, "Patiya", "পটিয়া"),
        JourneyStop(330, "Chakaria", "চকরিয়া"),
        JourneyStop(400, "Cox's Bazar", "কক্সবাজার")
    )

    /** Total travelled distance in km. Syncs first: today's distance is
     * re-read (it still grows), past days are frozen once counted, and
     * days pruned from route storage move their metres into the bank. */
    fun totalKm(ctx: Context): Double {
        try {
            val st = Store.settings(ctx)
            val days = Store.routeDays(ctx).toSet()
            val obj = try {
                JSONObject(st.getString(KEY_DAYS, "{}"))
            } catch (e: Exception) {
                JSONObject()
            }
            var banked = st.getLong(KEY_BANKED, 0L)
            var changed = false
            // Days gone from route storage: bank their distance, drop them.
            val keys = ArrayList<String>()
            val it = obj.keys()
            while (it.hasNext()) keys.add(it.next())
            for (k in keys) {
                if (k !in days) {
                    banked += obj.optLong(k, 0L)
                    obj.remove(k)
                    changed = true
                }
            }
            // Count each stored day once; only today can still grow.
            val today = Store.dayKey(0)
            for (d in days) {
                if (d != today && obj.has(d)) continue
                val m = Store.distanceForDay(ctx, d).toLong()
                if (obj.optLong(d, -1L) != m) {
                    obj.put(d, m)
                    changed = true
                }
            }
            if (changed) {
                st.edit().putString(KEY_DAYS, obj.toString()).putLong(KEY_BANKED, banked).apply()
            }
            var total = banked
            val it2 = obj.keys()
            while (it2.hasNext()) total += obj.optLong(it2.next(), 0L)
            return total / 1000.0
        } catch (e: Exception) {
            Log.e(TAG, "journey sync failed", e)
            return 0.0
        }
    }

    /** Stops already reached at [km]. */
    fun reached(km: Double): List<JourneyStop> = STOPS.filter { km >= it.km }

    /** The next stop ahead, or null at the destination. */
    fun nextStop(km: Double): JourneyStop? = STOPS.firstOrNull { km < it.km }
}

// =====================================================================
// MINI-GAMES — best scores only; the games themselves are small dialogs
// in Features.kt. All three are breathers: nothing here can touch a lock.
// =====================================================================

object MiniGames {
    /** Best (fewest) moves in memory match, 0 = never played. */
    fun memoryBest(ctx: Context): Int = Store.settings(ctx).getInt("game_memory_best", 0)

    fun recordMemory(ctx: Context, moves: Int) {
        val st = Store.settings(ctx)
        val best = st.getInt("game_memory_best", 0)
        if (best == 0 || moves < best) st.edit().putInt("game_memory_best", moves).apply()
    }

    /** Best (fastest) reaction in ms, 0 = never played. */
    fun reactionBest(ctx: Context): Long = Store.settings(ctx).getLong("game_reaction_best", 0L)

    fun recordReaction(ctx: Context, ms: Long) {
        val st = Store.settings(ctx)
        val best = st.getLong("game_reaction_best", 0L)
        if (best == 0L || ms < best) st.edit().putLong("game_reaction_best", ms).apply()
    }

    /** Best (fewest) guesses in number guess, 0 = never played. */
    fun guessBest(ctx: Context): Int = Store.settings(ctx).getInt("game_guess_best", 0)

    fun recordGuess(ctx: Context, tries: Int) {
        val st = Store.settings(ctx)
        val best = st.getInt("game_guess_best", 0)
        if (best == 0 || tries < best) st.edit().putInt("game_guess_best", tries).apply()
    }
}

// =====================================================================
// DRAWING — the lucky wheel and the journey track, both Canvas-drawn
// (no image assets).
// =====================================================================

/** The lucky wheel: one coloured segment per reward, with a fixed pointer
 * at the top. Spins with a decelerating animation and lands exactly on
 * a chosen segment. */
class WheelView(ctx: Context, private val rewards: List<WheelReward>) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wheelAngle = 0f
    private var animator: ValueAnimator? = null

    val isSpinning: Boolean get() = animator?.isRunning == true

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Segment index under the top pointer for a wheel angle. Segments
     * start at the top and run clockwise, so rotating the wheel by
     * [angle] moves segment 0's start [angle] clockwise, and the pointer
     * sees segment floor((360 - angle mod 360) / 60).
     */
    private fun segmentAt(angle: Float): Int {
        val norm = ((360f - (angle % 360f)) % 360f + 360f) % 360f
        return (norm / 60f).toInt().coerceIn(0, rewards.size - 1)
    }

    /** Wheel angle (mod 360) whose pointer lands on the centre of [segment]. */
    private fun angleFor(segment: Int): Float {
        return ((330f - segment * 60f) % 360f + 360f) % 360f
    }

    /**
     * Spins the wheel and lands on [target]. [onEnd] runs on the UI thread
     * when the wheel stops. Ignored while already spinning; if the view is
     * detached mid-spin the animation cancels and [onEnd] never runs, so
     * no reward is granted for a spin the user never saw land.
     */
    fun spinTo(target: Int, onEnd: () -> Unit) {
        if (isSpinning) return
        val t = target.coerceIn(0, rewards.size - 1)
        val start = wheelAngle
        val targetMod = angleFor(t)
        val curMod = ((start % 360f) + 360f) % 360f
        val delta = ((targetMod - curMod) % 360f + 360f) % 360f
        val end = start + 5 * 360f + delta
        val anim = ValueAnimator.ofFloat(start, end)
        anim.duration = 4000
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            wheelAngle = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Normalise to the same visual position (segmentAt checks out:
                // the pointer now sits on the target segment's centre).
                wheelAngle = ((end % 360f) + 360f) % 360f
                invalidate()
                animator = null
                if (segmentAt(wheelAngle) == t) onEnd()
            }

            override fun onAnimationCancel(animation: Animator) {
                animator = null
            }
        })
        animator = anim
        anim.start()
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - 8f
        if (r <= 0f) return
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        cv.save()
        cv.rotate(wheelAngle, cx, cy)
        // segments
        p.style = Paint.Style.FILL
        for (i in rewards.indices) {
            p.color = rewards[i].color
            cv.drawArc(oval, -90f + i * 60f, 60f, true, p)
        }
        // dividers
        p.color = Color.parseColor("#0A1510")
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        for (i in rewards.indices) {
            val a = Math.toRadians((-90 + i * 60).toDouble())
            cv.drawLine(cx, cy, cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(), p)
        }
        // labels, centred in each segment (short labels fit horizontally)
        textP.color = Color.parseColor("#0A1510")
        textP.textSize = r * 0.15f
        textP.textAlign = Paint.Align.CENTER
        textP.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        for (i in rewards.indices) {
            val mid = Math.toRadians((-90 + i * 60 + 30).toDouble())
            val tx = cx + (Math.cos(mid) * r * 0.62f).toFloat()
            val ty = cy + (Math.sin(mid) * r * 0.62f).toFloat() + textP.textSize * 0.35f
            cv.drawText(L.t(rewards[i].labelEn, rewards[i].labelBn), tx, ty, textP)
        }
        cv.restore()
        // rim
        p.style = Paint.Style.STROKE
        p.color = Color.parseColor("#F2FBF6")
        p.strokeWidth = 5f
        cv.drawCircle(cx, cy, r, p)
        // hub
        p.style = Paint.Style.FILL
        p.color = C.primary
        cv.drawCircle(cx, cy, r * 0.12f, p)
        // pointer, fixed at the top
        p.color = Color.parseColor("#F2FBF6")
        val pw = r * 0.10f
        val ph = r * 0.16f
        val ptr = Path()
        ptr.moveTo(cx - pw, cy - r - 4f)
        ptr.lineTo(cx + pw, cy - r - 4f)
        ptr.lineTo(cx, cy - r + ph)
        ptr.close()
        cv.drawPath(ptr, p)
    }
}

/** The Dhaka to Cox's Bazar route: a horizontal track with a dot per stop
 * and a gold marker at the current position. */
class JourneyTrackView(ctx: Context, private val km: Double) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = w * 0.04f
        val x0 = pad
        val x1 = w - pad
        val y = h * 0.5f
        fun xFor(stopKm: Int): Float =
            x0 + (x1 - x0) * (stopKm.toFloat() / Journey.TOTAL_KM)
        // track
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.06f
        p.strokeCap = Paint.Cap.ROUND
        p.color = C.card2
        cv.drawLine(x0, y, x1, y, p)
        // travelled part
        val prog = (km / Journey.TOTAL_KM).coerceIn(0.0, 1.0).toFloat()
        p.color = C.primary
        cv.drawLine(x0, y, x0 + (x1 - x0) * prog, y, p)
        // stops
        p.style = Paint.Style.FILL
        for (s in Journey.STOPS) {
            val reached = km >= s.km
            p.color = if (reached) C.primary else C.muted
            cv.drawCircle(xFor(s.km), y, h * 0.10f, p)
        }
        // you-are-here marker
        val mx = x0 + (x1 - x0) * prog
        p.color = Color.parseColor("#FFD66B")
        cv.drawCircle(mx, y, h * 0.16f, p)
        p.color = Color.parseColor("#0A1510")
        cv.drawCircle(mx, y, h * 0.07f, p)
    }
}
