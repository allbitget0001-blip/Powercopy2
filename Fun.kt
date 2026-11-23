package com.powerlock.app

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin
import kotlin.random.Random

/**
 * XP & levels: every focus minute earns 1 XP and each completed lock adds a
 * +10 XP bonus. XP is awarded from Store.addSession (the single place every
 * finished lock passes through), so no focus minute is missed or counted
 * twice — Pomodoro focus rounds and routine penalty locks count like any
 * other completed lock. Older sessions are backfilled once from the stored
 * session list, so existing users keep the XP they already earned.
 */
object Xp {
    private val THRESHOLDS = listOf(0L, 100L, 250L, 500L, 800L, 1200L, 1700L, 2300L, 3000L, 4000L)

    /** One-time backfill from the stored sessions (minutes + 10 per completed lock). */
    private fun backfill(ctx: Context): Long {
        var xp = 0L
        try {
            for (s in Store.sessions(ctx)) {
                if (!s.ok) continue
                xp += (s.dur / 60000L).coerceAtLeast(1L) + 10L
            }
        } catch (e: Exception) {
            Log.e(TAG, "xp backfill failed", e)
        }
        Store.lock(ctx).edit().putLong("xp_total", xp).apply()
        return xp
    }

    /** Lifetime XP. */
    fun total(ctx: Context): Long {
        val p = Store.lock(ctx)
        if (!p.contains("xp_total")) return backfill(ctx)
        return p.getLong("xp_total", 0L)
    }

    fun levelFor(xp: Long): Int {
        if (xp < 4000L) {
            var level = 1
            for (i in THRESHOLDS.indices) if (xp >= THRESHOLDS[i]) level = i + 1
            return level
        }
        return 10 + ((xp - 4000L) / 1000L).toInt()
    }

    /** XP needed to reach this level. */
    fun thresholdFor(level: Int): Long =
        if (level <= 10) THRESHOLDS[(level - 1).coerceIn(0, 9)]
        else 4000L + (level - 10) * 1000L

    /** (level, XP into the current level, XP needed for this level). */
    fun progress(ctx: Context): Triple<Int, Long, Long> {
        val xp = total(ctx)
        val level = levelFor(xp)
        val base = thresholdFor(level)
        val next = thresholdFor(level + 1)
        return Triple(level, xp - base, next - base)
    }

    fun title(level: Int): String = when (level.coerceAtMost(10)) {
        1 -> L.t("Beginner", "নবীন")
        2 -> L.t("Learner", "শিক্ষার্থী")
        3 -> L.t("Focused", "মনোযোগী")
        4 -> L.t("Regular", "অভ্যাসী")
        5 -> L.t("Steadfast", "দৃঢ়")
        6 -> L.t("Disciplined", "শৃঙ্খলাবদ্ধ")
        7 -> L.t("Focus warrior", "ফোকাস যোদ্ধা")
        8 -> L.t("Focus expert", "ফোকাস বিশেষজ্ঞ")
        9 -> L.t("Focus guru", "ফোকাস গুরু")
        else -> L.t("Master", "মাস্টার")
    }

    @Synchronized
    private fun add(ctx: Context, amount: Long) {
        val p = Store.lock(ctx)
        val before = levelFor(total(ctx))
        val now = total(ctx) + amount
        val e = p.edit().putLong("xp_total", now)
        val after = levelFor(now)
        if (after > before) e.putInt("xp_levelup_pending", after)
        e.apply()
    }

    /** Called from Store.addSession for every completed lock: minutes + 10 bonus. */
    fun awardForSession(ctx: Context, s: Session) {
        if (!s.ok) return
        val min = (s.dur / 60000L).coerceAtLeast(1L)
        add(ctx, min + 10L)
    }

    /** Bonus XP (e.g. the daily challenge). Also arms the level-up dialog. */
    fun addBonus(ctx: Context, amount: Int) = add(ctx, amount.toLong())

    /** A level just crossed, waiting for its one-time dialog, or null. */
    fun levelUpPending(ctx: Context): Int? {
        val v = Store.lock(ctx).getInt("xp_levelup_pending", -1)
        return if (v > 0) v else null
    }

    fun clearLevelUpPending(ctx: Context) {
        Store.lock(ctx).edit().remove("xp_levelup_pending").apply()
    }
}

/** One challenge definition: a daily goal with a progress kind. */
data class ChallengeDef(
    val id: String,
    val titleEn: String,
    val titleBn: String,
    val target: Int,
    val kind: String,
    val needsRoutine: Boolean = false
)

/**
 * Daily challenge: one challenge a day, picked deterministically from a
 * fixed pool (seeded by the date, so it is stable all day) and cached
 * date-keyed. Progress is computed live from existing data (focus minutes,
 * completed locks, routine completion, attempts). Completing it awards
 * +25 XP once and arms a one-time dialog. If the day's pick needs routine
 * tasks and the user has none, the next focus-only challenge is used.
 */
object DailyChallenge {
    const val BONUS_XP = 25

    private val POOL = listOf(
        ChallengeDef("focus60", "Focus for 1 hour", "১ ঘণ্টা ফোকাস করো", 60, "focus"),
        ChallengeDef("focus120", "Focus for 2 hours", "২ ঘণ্টা ফোকাস করো", 120, "focus"),
        ChallengeDef("locks2", "Complete 2 locks", "২টা লক শেষ করো", 2, "locks"),
        ChallengeDef("locks3", "Complete 3 locks", "৩টা লক শেষ করো", 3, "locks"),
        ChallengeDef("nobreak", "Finish a lock without stopping one early", "একটাও লক না ভেঙে একটা লক শেষ করো", 1, "nobreak"),
        ChallengeDef("routine", "Finish all routine tasks", "সব রুটিন কাজ শেষ করো", 1, "routine", true),
        ChallengeDef("morning30", "30 min focus before noon", "দুপুরের আগে ৩০ মিনিট ফোকাস", 30, "morning"),
        ChallengeDef("calm", "One lock, zero temptations", "একটা লক, কোনো প্রলোভন ছাড়া", 1, "calm")
    )

    fun byId(id: String): ChallengeDef? = POOL.firstOrNull { it.id == id }

    fun today(ctx: Context): ChallengeDef {
        val p = Store.lock(ctx)
        val day = Store.dayKey(0)
        if (p.getString("challenge_day", "") == day) {
            byId(p.getString("challenge_id", "") ?: "")?.let { return it }
        }
        val seed = day.toIntOrNull() ?: 0
        var idx = Math.abs(seed) % POOL.size
        var def = POOL[idx]
        var guard = 0
        while (def.needsRoutine && !Routine.hasAny(ctx) && guard < POOL.size) {
            idx = (idx + 1) % POOL.size
            def = POOL[idx]
            guard++
        }
        p.edit().putString("challenge_day", day).putString("challenge_id", def.id).apply()
        return def
    }

    /** (current, target) for a challenge, computed live from existing data. */
    fun progress(ctx: Context, def: ChallengeDef = today(ctx)): Pair<Int, Int> {
        val day = Store.dayKey(0)
        val score = FocusScore.today(ctx)
        val cur = when (def.kind) {
            "focus" -> score.focusMin // includes the running lock's live minutes
            "locks" -> score.locks
            "nobreak" -> {
                val early = Store.sessions(ctx).count { !it.ok && Store.dayKeyFor(it.end) == day }
                if (score.locks >= 1 && early == 0) 1 else 0
            }
            "routine" -> {
                val (done, total) = Routine.todaySummary(ctx)
                if (total > 0 && done == total) 1 else 0
            }
            "morning" -> {
                val noon = Store.dayStart(System.currentTimeMillis(), 0) + 12 * 60 * 60 * 1000L
                var min = 0
                for (s in Store.sessions(ctx)) {
                    if (s.ok && Store.dayKeyFor(s.end) == day && s.end < noon) {
                        min += (s.dur / 60000L).toInt().coerceAtLeast(1)
                    }
                }
                min
            }
            "calm" -> if (score.locks >= 1 && score.attempts == 0) 1 else 0
            else -> 0
        }
        return Pair(cur.coerceAtLeast(0), def.target)
    }

    fun isDone(ctx: Context): Boolean =
        Store.lock(ctx).getString("challenge_done", "") == Store.dayKey(0)

    /** Awards the +25 XP bonus once when the challenge is reached. */
    fun checkCompletion(ctx: Context) {
        if (isDone(ctx)) return
        val def = today(ctx)
        val (cur, target) = progress(ctx, def)
        if (cur < target) return
        Store.lock(ctx).edit()
            .putString("challenge_done", Store.dayKey(0))
            .putString("challenge_pending", Store.dayKey(0))
            .apply()
        Xp.addBonus(ctx, BONUS_XP)
    }

    fun pending(ctx: Context): Boolean =
        Store.lock(ctx).getString("challenge_pending", "") == Store.dayKey(0)

    fun clearPending(ctx: Context) {
        Store.lock(ctx).edit().remove("challenge_pending").apply()
    }
}

/**
 * Confetti celebration: fires when the day's routine is FULLY complete
 * (every routine task checked off) — never after locks. At most once a day:
 * if the tasks were all checked off while the app was closed, it shows once
 * on the next Home open.
 */
object Confetti {
    fun shouldCelebrate(ctx: Context): Boolean {
        val (done, total) = Routine.todaySummary(ctx)
        if (total <= 0 || done != total) return false
        return Store.lock(ctx).getString("confetti_day", "") != Store.dayKey(0)
    }

    fun markShown(ctx: Context) {
        Store.lock(ctx).edit().putString("confetti_day", Store.dayKey(0)).apply()
    }

    /** Full-screen confetti + a celebration message; dismisses itself after ~5 s. */
    fun show(a: MainActivity) {
        try {
            val dlg = Dialog(a)
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val root = FrameLayout(a)
            val cv = ConfettiView(a)
            root.addView(cv, ViewGroup.LayoutParams(MATCH, MATCH))
            val card = a.card(20)
            val t1 = a.tv(L.t("Routine complete!", "রুটিন সম্পন্ন!"), 26f, C.text, 1)
            t1.gravity = Gravity.CENTER
            val t2 = a.tv(L.t("Every task done today. Well done!", "আজকের সব কাজ শেষ। অভিনন্দন!"), 15f, C.muted)
            t2.gravity = Gravity.CENTER
            card.addView(t1)
            card.addView(t2, lp(MATCH, WRAP, t = a.dp(8)))
            root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            root.setOnClickListener { dlg.dismiss() }
            dlg.setContentView(root)
            dlg.setCancelable(true)
            dlg.setCanceledOnTouchOutside(true)
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.window?.setLayout(MATCH, MATCH)
            dlg.setOnDismissListener { cv.stop() }
            dlg.show()
            cv.post { cv.start() }
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        if (dlg.isShowing) dlg.dismiss()
                    } catch (_: Exception) {
                    }
                },
                5000L
            )
        } catch (e: Exception) {
            Log.e(TAG, "confetti failed", e)
        }
    }
}

/** Canvas-drawn falling confetti in theme colours. Call start() once it has a size. */
class ConfettiView(ctx: Context) : View(ctx) {
    private data class Piece(
        var x: Float, var y: Float, var size: Float, var speed: Float,
        var sway: Float, var phase: Float, var color: Int, var rot: Float, var rotSpeed: Float
    )

    private val pieces = ArrayList<Piece>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var started = false
    private var anim: ValueAnimator? = null

    fun start() {
        if (started) return
        started = true
        val w = if (width > 0) width.toFloat() else 1080f
        val colors = intArrayOf(
            C.primary, C.mint,
            Color.parseColor("#FFD54F"), Color.parseColor("#F48FB1"),
            Color.parseColor("#64B5F6"), Color.parseColor("#BA68C8")
        )
        val rnd = Random(System.currentTimeMillis())
        repeat(90) {
            pieces.add(
                Piece(
                    x = rnd.nextFloat() * w,
                    y = -rnd.nextFloat() * 600f,
                    size = 8f + rnd.nextFloat() * 14f,
                    speed = 3f + rnd.nextFloat() * 5f,
                    sway = 20f + rnd.nextFloat() * 40f,
                    phase = rnd.nextFloat() * 6.28f,
                    color = colors[rnd.nextInt(colors.size)],
                    rot = rnd.nextFloat() * 360f,
                    rotSpeed = -3f + rnd.nextFloat() * 6f
                )
            )
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4500L
            addUpdateListener {
                for (p in pieces) {
                    p.y += p.speed
                    p.rot += p.rotSpeed
                }
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        for (p in pieces) {
            if (p.y > h + 40f) continue
            val x = p.x + sin(p.phase + p.y * 0.01f) * p.sway * 0.3f
            paint.color = p.color
            canvas.save()
            canvas.rotate(p.rot, x, p.y)
            canvas.drawRect(x - p.size / 2, p.y - p.size / 4, x + p.size / 2, p.y + p.size / 4, paint)
            canvas.restore()
        }
    }
}

/** A simple horizontal progress bar: a rounded track with a fill that follows [frac] (0..1). */
class BarView(ctx: Context) : LinearLayout(ctx) {
    private val fill: View
    private val spacer: View

    init {
        orientation = HORIZONTAL
        background = rounded(C.chipFill, context.dp(5).toFloat())
        fill = View(ctx)
        fill.background = rounded(C.primary, context.dp(5).toFloat())
        spacer = View(ctx)
        addView(fill, LayoutParams(0, LayoutParams.MATCH_PARENT, 0f))
        addView(spacer, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    var frac: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            fill.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, field)
            spacer.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - field)
        }
}

private data class Riddle(val qEn: String, val qBn: String, val aEn: String, val aBn: String)

/**
 * Five one-minute mini-games for Pomodoro breaks and after locks — each a
 * simple dialog, self-contained, no leaderboards, no server. No breathing
 * game: the user explicitly excluded it.
 */
object MiniGames {
    private val RIDDLES = listOf(
        Riddle("The more I dry, the wetter I get. What am I?", "আমি যত শুকাই, তত ভিজে যাই। আমি কী?", "A towel", "তোয়ালে"),
        Riddle("I have a mouth but never speak. What am I?", "আমার মুখ আছে, কিন্তু কথা বলি না। আমি কী?", "A river", "নদী"),
        Riddle("The more you take from me, the bigger I get. What am I?", "আমার থেকে যত নেবে, তত বড় হবো। আমি কী?", "A hole", "গর্ত"),
        Riddle("I always come but never arrive. What am I?", "আমি সবসময় আসি, কিন্তু কখনো পৌঁছাই না। আমি কী?", "Tomorrow", "আগামীকাল"),
        Riddle("I have keys but open no locks. What am I?", "আমার চাবি আছে, কিন্তু কোনো তালা খুলি না। আমি কী?", "A keyboard", "কিবোর্ড"),
        Riddle("I move forward and never look back. What am I?", "আমি সামনে যাই, কখনো পেছনে ফিরি না। আমি কী?", "Time", "সময়"),
        Riddle("You must break me to use me. What am I?", "আমাকে ভেঙে তবে ব্যবহার করতে হয়। আমি কী?", "An egg", "ডিম"),
        Riddle("I'm in every home but no one sees me. What am I?", "আমি সব ঘরে থাকি, কিন্তু কেউ আমাকে দেখে না। আমি কী?", "Air", "বাতাস"),
        Riddle("The bigger I get, the less you see. What am I?", "আমি যত বড় হই, তত কম দেখা যায়। আমি কী?", "Darkness", "অন্ধকার"),
        Riddle("I run but have no legs. What am I?", "আমি দৌড়াই, কিন্তু পা নেই। আমি কী?", "Water", "পানি"),
        Riddle("I speak only when spoken to, and repeat every word. What am I?", "আমাকে বললে তবেই বলি, আর সব কথা ফিরিয়ে দিই। আমি কী?", "An echo", "প্রতিধ্বনি"),
        Riddle("Catch me and I'm gone. What am I?", "আমাকে ধরতে গেলে আমি হারিয়ে যাই। আমি কী?", "A shadow", "ছায়া")
    )

    private fun gameDialog(a: MainActivity, title: String, content: View): AlertDialog {
        return a.dlg().setTitle(title).setView(content)
            .setNegativeButton(L.t("Close", "বন্ধ"), null).show()
    }

    private fun gameRoot(a: MainActivity): LinearLayout {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        return root
    }

    /** Memory cards: flip tiles to match all 6 pairs (4x3 grid). */
    fun showMemory(a: MainActivity) {
        val symbols = listOf("★", "◆", "●", "▲", "■", "♥")
        val deck = (symbols + symbols).shuffled()
        val tiles = ArrayList<TextView>()
        val flipped = ArrayList<Int>()
        val matched = HashSet<Int>()
        var moves = 0
        var lock = false
        val handler = Handler(Looper.getMainLooper())
        val root = gameRoot(a)
        val status = a.tv(L.t("Find all 6 pairs", "৬ জোড়া মিলান"), 14f, C.muted)
        status.gravity = Gravity.CENTER
        root.addView(status, lp(MATCH, WRAP, b = a.dp(12)))
        for (row in 0 until 3) {
            val r = LinearLayout(a)
            for (col in 0 until 4) {
                val idx = row * 4 + col
                val t = a.tv("?", 26f, C.text, 1)
                t.gravity = Gravity.CENTER
                t.background = rounded(C.card2, a.dp(10).toFloat())
                t.setOnClickListener {
                    if (lock || idx in matched || idx in flipped) return@setOnClickListener
                    t.text = deck[idx]
                    t.setTextColor(C.primary)
                    flipped.add(idx)
                    if (flipped.size == 2) {
                        moves++
                        val (f1, f2) = flipped
                        if (deck[f1] == deck[f2]) {
                            matched.add(f1)
                            matched.add(f2)
                            tiles[f1].setTextColor(C.mint)
                            tiles[f2].setTextColor(C.mint)
                            flipped.clear()
                            if (matched.size == 12) {
                                status.text = L.t(
                                    "All matched in $moves moves!",
                                    "${L.num(moves.toString())} চালে সব মিলে গেছে!"
                                )
                            }
                        } else {
                            lock = true
                            handler.postDelayed({
                                tiles[f1].text = "?"
                                tiles[f2].text = "?"
                                tiles[f1].setTextColor(C.text)
                                tiles[f2].setTextColor(C.text)
                                flipped.clear()
                                lock = false
                            }, 700)
                        }
                    }
                }
                tiles.add(t)
                r.addView(t, lp(0, a.dp(64), r = if (col < 3) a.dp(8) else 0, b = a.dp(8), weight = 1f))
            }
            root.addView(r)
        }
        gameDialog(a, L.t("Memory cards", "মেমোরি কার্ড"), root)
    }

    /** Stroop colour-word: tap the button matching the ink COLOUR, not the word. 10 rounds. */
    fun showStroop(a: MainActivity) {
        val colors = listOf(
            Triple("Red", "লাল", Color.parseColor("#E53935")),
            Triple("Green", "সবুজ", Color.parseColor("#43A047")),
            Triple("Blue", "নীল", Color.parseColor("#1E88E5")),
            Triple("Yellow", "হলুদ", Color.parseColor("#F9A825"))
        )
        val rnd = Random(System.currentTimeMillis())
        val total = 10
        var round = 0
        var score = 0
        var ink = 0
        var finished = false
        val root = gameRoot(a)
        val status = a.tv("", 14f, C.muted)
        status.gravity = Gravity.CENTER
        root.addView(status, lp(MATCH, WRAP, b = a.dp(8)))
        val word = a.tv("", 34f, C.text, 1)
        word.gravity = Gravity.CENTER
        root.addView(word, lp(MATCH, WRAP, b = a.dp(4)))
        val feedback = a.tv("", 20f, C.mint, 1)
        feedback.gravity = Gravity.CENTER
        root.addView(feedback, lp(MATCH, WRAP, b = a.dp(12)))
        val btnRow1 = LinearLayout(a)
        val btnRow2 = LinearLayout(a)
        fun nextRound() {
            if (round >= total) {
                finished = true
                word.text = L.t("Done!", "শেষ!")
                word.setTextColor(C.text)
                status.text = L.t(
                    "Score: $score / $total",
                    "স্কোর: ${L.num(score.toString())} / ${L.num(total.toString())}"
                )
                btnRow1.visibility = View.GONE
                btnRow2.visibility = View.GONE
                return
            }
            val wordIdx = rnd.nextInt(4)
            ink = rnd.nextInt(4)
            word.text = L.t(colors[wordIdx].first, colors[wordIdx].second)
            word.setTextColor(colors[ink].third)
            status.text = L.t(
                "Round ${round + 1}/$total · Score $score",
                "রাউন্ড ${L.num((round + 1).toString())}/${L.num(total.toString())} · স্কোর ${L.num(score.toString())}"
            )
        }
        val buttons = ArrayList<TextView>()
        for (i in 0 until 4) {
            val b = a.outlineBtn(L.t(colors[i].first, colors[i].second), colors[i].third)
            b.setOnClickListener {
                if (finished) return@setOnClickListener
                if (i == ink) {
                    score++
                    feedback.text = "✓"
                    feedback.setTextColor(C.mint)
                } else {
                    feedback.text = "✗"
                    feedback.setTextColor(C.danger)
                }
                round++
                nextRound()
            }
            buttons.add(b)
            val row = if (i < 2) btnRow1 else btnRow2
            row.addView(b, lp(0, a.dp(52), r = if (i % 2 == 0) a.dp(8) else 0, b = a.dp(8), weight = 1f))
        }
        root.addView(btnRow1)
        root.addView(btnRow2)
        nextRound()
        gameDialog(a, L.t("Colour-word", "রঙ-শব্দ"), root)
    }

    /** Math quiz: 10 quick sums, score + time at the end. */
    fun showMath(a: MainActivity) {
        val total = 10
        var round = 0
        var score = 0
        var finished = false
        var options = listOf(0, 0, 0, 0)
        var answer = 0
        val startMs = System.currentTimeMillis()
        val root = gameRoot(a)
        val status = a.tv("", 14f, C.muted)
        status.gravity = Gravity.CENTER
        root.addView(status, lp(MATCH, WRAP, b = a.dp(8)))
        val question = a.tv("", 30f, C.text, 1)
        question.gravity = Gravity.CENTER
        root.addView(question, lp(MATCH, WRAP, b = a.dp(12)))
        val btnRow1 = LinearLayout(a)
        val btnRow2 = LinearLayout(a)
        val buttons = ArrayList<TextView>()
        fun nextRound() {
            if (round >= total) {
                finished = true
                val secs = (System.currentTimeMillis() - startMs) / 1000
                question.text = L.t("Done!", "শেষ!")
                status.text = L.t(
                    "Score: $score / $total in ${secs}s",
                    "স্কোর: ${L.num(score.toString())} / ${L.num(total.toString())} · ${L.num(secs.toString())} সেকেন্ডে"
                )
                btnRow1.visibility = View.GONE
                btnRow2.visibility = View.GONE
                return
            }
            val rnd = Random(System.currentTimeMillis() + round * 7919L)
            val plus = rnd.nextBoolean()
            val x = rnd.nextInt(2, 21)
            val y = rnd.nextInt(2, 21)
            if (plus) {
                question.text = "$x + $y = ?"
                answer = x + y
            } else {
                val big = maxOf(x, y)
                val small = minOf(x, y)
                question.text = "$big - $small = ?"
                answer = big - small
            }
            val opts = mutableSetOf(answer)
            while (opts.size < 4) {
                val d = rnd.nextInt(-10, 11)
                opts.add((answer + (if (d == 0) 1 else d)).coerceAtLeast(0))
            }
            options = opts.shuffled()
            for (i in 0 until 4) buttons[i].text = L.num(options[i].toString())
            status.text = L.t(
                "Round ${round + 1}/$total · Score $score",
                "রাউন্ড ${L.num((round + 1).toString())}/${L.num(total.toString())} · স্কোর ${L.num(score.toString())}"
            )
        }
        for (i in 0 until 4) {
            val b = a.outlineBtn("", C.primary)
            b.setOnClickListener {
                if (finished) return@setOnClickListener
                if (options[i] == answer) score++
                round++
                nextRound()
            }
            buttons.add(b)
            val row = if (i < 2) btnRow1 else btnRow2
            row.addView(b, lp(0, a.dp(52), r = if (i % 2 == 0) a.dp(8) else 0, b = a.dp(8), weight = 1f))
        }
        root.addView(btnRow1)
        root.addView(btnRow2)
        nextRound()
        gameDialog(a, L.t("Math quiz", "গণিত কুইজ"), root)
    }

    /** Reaction game: wait for green, tap as fast as you can. Best time is saved. */
    fun showReaction(a: MainActivity) {
        val handler = Handler(Looper.getMainLooper())
        var state = 0 // 0 idle, 1 waiting, 2 green
        var greenAt = 0L
        var best = Store.lock(a).getLong("reaction_best", 0L)
        val root = gameRoot(a)
        val status = a.tv(
            if (best > 0) L.t("Best: ${best} ms", "সেরা: ${L.num(best.toString())} মিলিসেকেন্ড")
            else L.t("Tap the button, wait for green, then tap fast!", "বাটন চাপুন, সবুজ হলে দ্রুত চাপুন!"),
            14f, C.muted
        )
        status.gravity = Gravity.CENTER
        root.addView(status, lp(MATCH, WRAP, b = a.dp(12)))
        val pad = a.primaryBtn(L.t("Tap to start", "শুরু করতে চাপুন"))
        fun primaryBg(): GradientDrawable {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.primary, C.mint))
            g.cornerRadius = a.dp(16).toFloat()
            return g
        }
        pad.setOnClickListener {
            when (state) {
                0 -> {
                    state = 1
                    pad.text = L.t("Wait for green…", "সবুজের জন্য অপেক্ষা করুন…")
                    pad.background = withRipple(rounded(Color.parseColor("#C62828"), a.dp(16).toFloat()))
                    status.text = L.t("Wait…", "অপেক্ষা করুন…")
                    handler.postDelayed({
                        if (state != 1) return@postDelayed
                        state = 2
                        greenAt = SystemClock.uptimeMillis()
                        pad.text = L.t("TAP!", "চাপুন!")
                        pad.background = withRipple(rounded(C.mint, a.dp(16).toFloat()))
                        pad.setTextColor(Color.parseColor("#062B1D"))
                    }, 1000L + Random(System.currentTimeMillis()).nextLong(2000L))
                }
                1 -> {
                    handler.removeCallbacksAndMessages(null)
                    state = 0
                    pad.text = L.t("Too early! Tap to try again", "আগে চেপে ফেলেছেন! আবার চেষ্টা করুন")
                    pad.background = withRipple(primaryBg())
                    pad.setTextColor(C.onPrimary)
                }
                2 -> {
                    val ms = SystemClock.uptimeMillis() - greenAt
                    state = 0
                    if (best == 0L || ms < best) {
                        best = ms
                        Store.lock(a).edit().putLong("reaction_best", best).apply()
                    }
                    pad.text = L.t("Tap to try again", "আবার চেষ্টা করতে চাপুন")
                    pad.background = withRipple(primaryBg())
                    pad.setTextColor(C.onPrimary)
                    status.text = L.t(
                        "Reaction: ${ms} ms · Best: ${best} ms",
                        "প্রতিক্রিয়া: ${L.num(ms.toString())} মিলিসেকেন্ড · সেরা: ${L.num(best.toString())} মিলিসেকেন্ড"
                    )
                }
            }
        }
        root.addView(pad, lp(MATCH, a.dp(72)))
        gameDialog(a, L.t("Reaction game", "প্রতিক্রিয়া খেলা"), root)
    }

    /** Daily riddle: one riddle a day from the built-in list, with a reveal button. */
    fun showRiddle(a: MainActivity) {
        val idx = Math.abs((Store.dayKey(0).toIntOrNull() ?: 0)) % RIDDLES.size
        val r = RIDDLES[idx]
        val root = gameRoot(a)
        val q = a.tv(L.t(r.qEn, r.qBn), 18f, C.text, 1)
        q.gravity = Gravity.CENTER
        root.addView(q, lp(MATCH, WRAP, b = a.dp(12)))
        val ans = a.tv("", 16f, C.mint, 1)
        ans.gravity = Gravity.CENTER
        root.addView(ans, lp(MATCH, WRAP, b = a.dp(12)))
        val reveal = a.outlineBtn(L.t("Show the answer", "উত্তর দেখুন"), C.primary)
        reveal.setOnClickListener {
            ans.text = L.t(r.aEn, r.aBn)
            reveal.visibility = View.GONE
        }
        root.addView(reveal, lp(MATCH, a.dp(48)))
        gameDialog(a, L.t("Daily riddle", "দিনের ধাঁধা"), root)
    }
}
