package com.powerlock.app

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

// =====================================================================
// HOME
// =====================================================================
class HomeScreen(private val a: MainActivity) {
    private lateinit var ring: RingView
    private lateinit var pill: TextView
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var previewText: TextView
    private lateinit var schedPill: TextView
    private lateinit var lastSummaryText: TextView
    private var summaryShown = false
    private var lastSummarySig = ""
    private lateinit var screenTimeText: TextView
    private var cachedUsageSec = -1L
    private var cachedUsageAt = 0L
    private var cachedUsageDay = ""
    private lateinit var scoreText: TextView
    private var cachedScore: ScoreBreakdown? = null
    private var cachedScoreAt = 0L
    private lateinit var treeView: TreeView
    private lateinit var treeLabel: TextView
    private var treeSig = ""
    private lateinit var profileRow: LinearLayout
    private lateinit var profileNameText: TextView
    private lateinit var profileSubText: TextView
    private var profileSig = ""
    private val detoxDots = ArrayList<TextView>()
    private lateinit var detoxLabel: TextView
    private lateinit var detoxBtn: TextView
    private var detoxSig = ""
    private lateinit var pomLabel: TextView
    private lateinit var pomStartBtn: TextView
    private lateinit var pomStopBtn: TextView
    private var pomSig = ""
    private var lastPom: Boolean? = null
    private lateinit var habitBox: LinearLayout
    private var habitSig = ""
    private lateinit var planBox: LinearLayout
    private lateinit var planCarryBtn: TextView
    private var lastPlanDay = ""
    private var badgeShown = false
    private lateinit var startBtn: LinearLayout
    private lateinit var stopBtn: LinearLayout
    private lateinit var extendBtn: LinearLayout
    private lateinit var warnBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private val tplChips = ArrayList<TextView>()
    private val quick = listOf(5, 10, 15, 30, 60)
    private val modeChips = ArrayList<TextView>()
    private val modes = listOf(UNLOCK_TIME, UNLOCK_LOC, UNLOCK_EITHER)
    private lateinit var destRow: LinearLayout
    private lateinit var destNameText: TextView
    private lateinit var destRadiusText: TextView
    private var warnSig = ""
    private var lastStatus: PStatus? = null
    private var lastActive: Boolean? = null
    private var lastSched: Boolean? = null

    private fun quickLabel(m: Int): String = L.num(L.t("$m min", "$m মিনিট"))

    fun build(): View {
        val (sv, col) = a.page()

        // ---- header ----
        val header = LinearLayout(a)
        header.gravity = Gravity.CENTER_VERTICAL
        val tile = LinearLayout(a)
        tile.gravity = Gravity.CENTER
        tile.background = rounded(Color.parseColor("#14392A"), a.dp(16).toFloat())
        tile.addView(IconView(a, Ic.SHIELD, C.primary), lp(a.dp(30), a.dp(30)))
        header.addView(tile, lp(a.dp(52), a.dp(52)))
        val names = LinearLayout(a)
        names.orientation = LinearLayout.VERTICAL
        names.addView(a.tv("PowerLock 2", 24f, C.text, 1))
        val sub = a.tv(L.t("PREMIUM EDITION", "প্রিমিয়াম এডিশন"), 12f, C.muted, 1)
        sub.letterSpacing = 0.1f
        names.addView(sub)
        header.addView(names, lp(0, WRAP, l = a.dp(14), weight = 1f))
        pill = a.tv("", 13f, C.primary, 1)
        pill.letterSpacing = 0.05f
        pill.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        header.addView(pill)
        col.addView(header)

        warnBox = LinearLayout(a)
        warnBox.orientation = LinearLayout.VERTICAL
        col.addView(warnBox, lp(MATCH, WRAP, t = a.dp(14)))

        // ---- status card ----
        val sc = a.card(20)
        sc.gravity = Gravity.CENTER_HORIZONTAL
        sc.background = rounded(C.card, a.dp(28).toFloat())
        val lbl = a.tv(L.t("POWER LOCK STATUS", "পাওয়ার লক স্ট্যাটাস"), 12f, C.muted, 1)
        lbl.letterSpacing = 0.12f
        lbl.gravity = Gravity.CENTER
        sc.addView(lbl)
        ring = RingView(a)
        sc.addView(ring, lp(a.dp(170), a.dp(170), t = a.dp(16)))
        titleText = a.tv("", 26f, C.text, 1)
        titleText.gravity = Gravity.CENTER
        sc.addView(titleText, lp(MATCH, WRAP, t = a.dp(16)))
        subText = a.tv("", 15f, C.muted)
        subText.gravity = Gravity.CENTER
        sc.addView(subText, lp(MATCH, WRAP, t = a.dp(6)))
        startBtn = a.bigButton(Ic.LOCK, L.t("Start Power Lock", "পাওয়ার লক শুরু"), C.primary, C.onPrimary)
        startBtn.setOnClickListener { onStart() }
        sc.addView(startBtn, lp(MATCH, a.dp(60), t = a.dp(20)))
        stopBtn = a.bigButton(Ic.LOCK, L.t("Stop Power Lock", "পাওয়ার লক বন্ধ"), Color.TRANSPARENT, C.danger, C.danger)
        stopBtn.setOnClickListener { onStop() }
        sc.addView(stopBtn, lp(MATCH, a.dp(60), t = a.dp(20)))
        extendBtn = a.bigButton(Ic.CLOCK, L.t("Add time", "সময় বাড়ান"), C.card2, C.primary, C.primary)
        extendBtn.setOnClickListener { a.extendFlow { tick() } }
        sc.addView(extendBtn, lp(MATCH, a.dp(60), t = a.dp(12)))
        col.addView(sc, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- focus tree: grows while a lock runs, withers if stopped early ----
        val treeCard = a.card(20)
        treeCard.addView(a.tv(L.t("Focus tree", "ফোকাস গাছ"), 18f, C.text, 1))
        treeView = TreeView(a)
        treeCard.addView(treeView, lp(MATCH, a.dp(170), t = a.dp(8)))
        treeLabel = a.tv("", 13f, C.muted)
        treeLabel.gravity = Gravity.CENTER
        treeCard.addView(treeLabel, lp(MATCH, WRAP, t = a.dp(6)))
        col.addView(treeCard, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- 7-day detox challenge: one completed lock a day, 7 days in a row ----
        val detox = a.card(20)
        detox.addView(a.tv(L.t("Detox challenge — 7 days", "ডিটক্স চ্যালেঞ্জ — ৭ দিন"), 18f, C.text, 1))
        detoxLabel = a.tv("", 13f, C.muted)
        detox.addView(detoxLabel, lp(MATCH, WRAP, t = a.dp(6)))
        val dotsRow = LinearLayout(a)
        dotsRow.gravity = Gravity.CENTER_VERTICAL
        for (i in 0 until 7) {
            val d = a.tv("○", 22f, C.muted, 1)
            dotsRow.addView(d, lp(WRAP, WRAP, r = a.dp(8)))
            detoxDots.add(d)
        }
        detox.addView(dotsRow, lp(MATCH, WRAP, t = a.dp(10)))
        detoxBtn = a.outlineBtn("", C.primary)
        detoxBtn.setOnClickListener {
            Store.startDetox(a)
            updateDetox()
            tick()
        }
        detox.addView(detoxBtn, lp(MATCH, a.dp(48), t = a.dp(12)))
        col.addView(detox, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- Pomodoro: 25 min focus + 5 min break, 4 rounds, then a long break ----
        val pom = a.card(20)
        pom.addView(a.tv(L.t("Pomodoro", "পোমোডোরো"), 18f, C.text, 1))
        pomLabel = a.tv("", 13f, C.muted)
        pom.addView(pomLabel, lp(MATCH, WRAP, t = a.dp(6)))
        pomStartBtn = a.outlineBtn(L.t("Start Pomodoro", "পোমোডোরো শুরু করুন"), C.primary)
        pomStartBtn.setOnClickListener { startPomodoro() }
        pom.addView(pomStartBtn, lp(MATCH, a.dp(48), t = a.dp(12)))
        pomStopBtn = a.outlineBtn(L.t("Stop Pomodoro", "পোমোডোরো বন্ধ করুন"), C.danger)
        pomStopBtn.setOnClickListener {
            if (Pomodoro.cancel(a)) {
                a.toast(L.t("Pomodoro stopped", "পোমোডোরো বন্ধ হয়েছে"))
                tick()
            } else {
                a.toast(L.t("Strict mode: the focus round cannot be stopped early", "স্ট্রিক্ট মোড: ফোকাস রাউন্ড আগেই বন্ধ করা যাবে না"))
            }
        }
        pom.addView(pomStopBtn, lp(MATCH, a.dp(48), t = a.dp(12)))
        col.addView(pom, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- habit tracker: check off today's habits, build a streak ----
        val hab = a.card(20)
        hab.addView(a.tv(L.t("Habit tracker", "অভ্যাস ট্র্যাকার"), 18f, C.text, 1))
        habitBox = LinearLayout(a)
        habitBox.orientation = LinearLayout.VERTICAL
        hab.addView(habitBox, lp(MATCH, WRAP, t = a.dp(6)))
        val habAdd = a.outlineBtn(L.t("+ Add a habit", "+ অভ্যাস যোগ করুন"), C.primary)
        habAdd.setOnClickListener { addHabitDialog() }
        hab.addView(habAdd, lp(MATCH, a.dp(48), t = a.dp(12)))
        col.addView(hab, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- today's plan: tasks for today, with optional reminder times ----
        val plan = a.card(20)
        plan.addView(a.tv(L.t("Today's plan", "আজকের পরিকল্পনা"), 18f, C.text, 1))
        planBox = LinearLayout(a)
        planBox.orientation = LinearLayout.VERTICAL
        plan.addView(planBox, lp(MATCH, WRAP, t = a.dp(6)))
        val planAdd = a.outlineBtn(L.t("+ Add a task", "+ কাজ যোগ করুন"), C.primary)
        planAdd.setOnClickListener { addPlanDialog() }
        plan.addView(planAdd, lp(MATCH, a.dp(48), t = a.dp(12)))
        planCarryBtn = a.outlineBtn(L.t("Bring yesterday's unfinished tasks", "গতকালের বাকি কাজ আনুন"), C.muted)
        planCarryBtn.setOnClickListener {
            Plan.carryOver(a)
            updatePlan()
            a.toast(L.t("Yesterday's tasks added", "গতকালের কাজ যোগ হয়েছে"))
        }
        plan.addView(planCarryBtn, lp(MATCH, a.dp(48), t = a.dp(8)))
        col.addView(plan, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- lock templates: one tap starts a lock with that duration ----
        col.addView(a.heading(L.t("Lock templates", "লক টেমপ্লেট")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val templates = listOf(
            25 to L.t("Focus 25 min", "ফোকাস ২৫ মিনিট"),
            120 to L.t("Study 2 h", "পড়াশোনা ২ ঘণ্টা"),
            60 to L.t("Work 1 h", "কাজ ১ ঘণ্টা"),
            480 to L.t("Sleep 8 h", "ঘুম ৮ ঘণ্টা")
        )
        val tplRow1 = LinearLayout(a)
        val tplRow2 = LinearLayout(a)
        for ((i, t) in templates.withIndex()) {
            val c = a.chip(t.second, false)
            c.setOnClickListener { startTemplate(t.first) }
            tplChips.add(c)
            val target = if (i < 2) tplRow1 else tplRow2
            target.addView(c, lp(0, a.dp(56), r = if (i % 2 == 0) a.dp(10) else 0, t = a.dp(10), weight = 1f))
        }
        col.addView(tplRow1)
        col.addView(tplRow2)

        // ---- duration ----
        col.addView(a.heading(L.t("Select Lock Duration", "লক সময় বাছুন")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val row1 = LinearLayout(a)
        val row2 = LinearLayout(a)
        for ((i, m) in quick.withIndex()) {
            val c = a.chip(quickLabel(m), false)
            c.setOnClickListener { pick(m) }
            chips.add(c)
            val rm = if (i == 2) 0 else a.dp(10)
            val target = if (i < 3) row1 else row2
            target.addView(c, lp(0, a.dp(56), r = rm, t = a.dp(10), weight = 1f))
        }
        val custom = a.chip(L.t("Custom", "কাস্টম"), false)
        custom.setOnClickListener { customDialog() }
        chips.add(custom)
        row2.addView(custom, lp(0, a.dp(56), t = a.dp(10), weight = 1f))
        col.addView(row1)
        col.addView(row2)
        previewText = a.tv("", 13f, C.muted)
        col.addView(previewText, lp(MATCH, WRAP, t = a.dp(12)))
        lastSummaryText = a.tv("", 13f, C.mint)
        col.addView(lastSummaryText, lp(MATCH, WRAP, t = a.dp(6)))
        // live screen time today: stored totals + the app open right now
        screenTimeText = a.tv("", 13f, C.muted)
        col.addView(screenTimeText, lp(MATCH, WRAP, t = a.dp(4)))
        // focus score today (0–100): tap to see the breakdown in Statistics
        scoreText = a.tv("", 13f, C.primary, 1)
        scoreText.setOnClickListener { a.go(2) }
        scoreText.pressFeedback()
        col.addView(scoreText, lp(MATCH, WRAP, t = a.dp(4)))

        // ---- unlock condition: time only, location only, or either one ----
        col.addView(a.heading(L.t("When does the lock open?", "লক খুলবে কখন?")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val modeRow = LinearLayout(a)
        val modeLabels = listOf(
            L.t("Time only", "শুধু সময়"),
            L.t("Location only", "শুধু লোকেশন"),
            L.t("Either one", "যেকোনো একটা")
        )
        for ((i, m) in modes.withIndex()) {
            val ch = a.chip(modeLabels[i], false)
            ch.setOnClickListener { pickMode(m) }
            modeChips.add(ch)
            modeRow.addView(ch, lp(0, a.dp(56), r = if (i < 2) a.dp(10) else 0, t = a.dp(10), weight = 1f))
        }
        col.addView(modeRow)
        // destination row (only for the location modes): name opens the picker, radius is tappable
        destRow = LinearLayout(a)
        destRow.orientation = LinearLayout.HORIZONTAL
        destRow.gravity = Gravity.CENTER_VERTICAL
        destRow.background = rounded(C.card2, a.dp(18).toFloat())
        destRow.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        destRow.addView(IconView(a, Ic.PIN, C.primary), lp(a.dp(26), a.dp(26), r = a.dp(12)))
        val destCol = LinearLayout(a)
        destCol.orientation = LinearLayout.VERTICAL
        destNameText = a.tv("", 16f, C.text, 1)
        destCol.addView(destNameText)
        destRadiusText = a.tv("", 12f, C.primary)
        destRadiusText.setOnClickListener { pickRadius() }
        destCol.addView(destRadiusText)
        destRow.addView(destCol, lp(0, WRAP, weight = 1f))
        destRow.addView(a.tv("›", 22f, C.muted, 1))
        destRow.setOnClickListener { pickDestination() }
        destRow.pressFeedback()
        col.addView(destRow, lp(MATCH, WRAP, t = a.dp(10)))

        // ---- lock profile: extra apps blocked for this session ----
        profileRow = LinearLayout(a)
        profileRow.orientation = LinearLayout.HORIZONTAL
        profileRow.gravity = Gravity.CENTER_VERTICAL
        profileRow.background = rounded(C.card2, a.dp(18).toFloat())
        profileRow.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        profileRow.addView(IconView(a, Ic.SHIELD, C.primary), lp(a.dp(26), a.dp(26), r = a.dp(12)))
        val profCol = LinearLayout(a)
        profCol.orientation = LinearLayout.VERTICAL
        profileNameText = a.tv("", 16f, C.text, 1)
        profCol.addView(profileNameText)
        profileSubText = a.tv("", 12f, C.primary)
        profCol.addView(profileSubText)
        profileRow.addView(profCol, lp(0, WRAP, weight = 1f))
        profileRow.addView(a.tv("›", 22f, C.muted, 1))
        profileRow.setOnClickListener { pickProfile() }
        profileRow.pressFeedback()
        col.addView(profileRow, lp(MATCH, WRAP, t = a.dp(10)))

        // ---- presets ----
        col.addView(a.presetsCard { refreshChips(true); tick() })

        // ---- schedule card ----
        val scd = a.card(20)
        scd.addView(a.tv(L.t("Schedule", "সময়সূচি"), 22f, C.text, 1))
        val r1 = LinearLayout(a)
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.setPadding(0, a.dp(18), 0, a.dp(18))
        r1.addView(IconView(a, Ic.CAL, C.primary), lp(a.dp(28), a.dp(28), r = a.dp(14)))
        r1.addView(a.tv(L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 17f, C.text), lp(0, WRAP, weight = 1f))
        schedPill = a.tv("", 14f, C.primary, 1)
        schedPill.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        r1.addView(schedPill)
        r1.setOnClickListener { a.go(1) }
        scd.addView(r1)
        val dv = View(a)
        dv.setBackgroundColor(C.border)
        scd.addView(dv, lp(MATCH, a.dp(1)))
        val r2 = LinearLayout(a)
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.setPadding(0, a.dp(18), 0, a.dp(2))
        r2.addView(a.tv(L.t("Statistics", "পরিসংখ্যান"), 17f, C.muted), lp(0, WRAP, weight = 1f))
        r2.addView(a.tv(L.t("View details →", "বিস্তারিত →"), 16f, C.primary, 1))
        r2.setOnClickListener { a.go(2) }
        scd.addView(r2)
        col.addView(scd, lp(MATCH, WRAP, t = a.dp(22)))

        refreshChips()
        refreshModeChips()
        refreshProfileRow()
        lastPlanDay = Store.dayKey(0)
        updatePlan()
        tick()
        // home wallpaper: the picked photo sits behind the content, dimmed so
        // the text stays readable; no photo (or a broken file) = plain page
        val wall = Wallpaper.loadBitmap(a)
        if (wall == null) return sv
        val wrap = FrameLayout(a)
        val iv = ImageView(a)
        iv.setImageBitmap(wall)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        wrap.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(a)
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0))
        wrap.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        wrap.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return wrap
    }

    private fun refreshChips(animate: Boolean = false) {
        val active = Store.isActive(a)
        val pom = Pomodoro.state(a).active
        // location-only mode has no duration: the chips stay visible but dimmed
        val locOnly = unlockMode() == UNLOCK_LOC
        for ((i, c) in chips.withIndex()) {
            val sel = if (i < quick.size) quick[i] == a.selectedMin else !quick.contains(a.selectedMin)
            if (animate) a.animateChip(c, sel) else a.styleChip(c, sel)
            c.alpha = if (active || locOnly || pom) 0.4f else 1f
        }
        for (c in tplChips) c.alpha = if (active || pom) 0.4f else 1f
    }

    private fun pick(m: Int) {
        if (Store.isActive(a)) return
        if (Pomodoro.state(a).active) return
        if (unlockMode() == UNLOCK_LOC) return
        a.selectedMin = m
        refreshChips(true)
        tick()
    }

    /** A template is a time-only lock: set the duration + mode, then use the normal start flow. */
    private fun startTemplate(min: Int) {
        if (Store.isActive(a)) return
        if (Pomodoro.state(a).active) return
        if (!Perm.accessibilityOn(a)) {
            a.toast(L.t("Enable Accessibility first", "আগে Accessibility চালু করুন"))
            return
        }
        setUnlockMode(UNLOCK_TIME)
        a.selectedMin = min
        refreshChips(true)
        refreshModeChips()
        startTimeSession()
    }

    // ---------- unlock condition (time / location / either) ----------

    private fun unlockMode(): String = Store.settings(a).getString("unlock_mode", UNLOCK_TIME) ?: UNLOCK_TIME

    private fun setUnlockMode(m: String) {
        Store.settings(a).edit().putString("unlock_mode", m).apply()
    }

    /** The chosen unlock destination (settings), or null when none was picked yet. */
    private fun unlockDest(): UnlockTarget? {
        val raw = Store.settings(a).getString("unlock_dest", null) ?: return null
        return try {
            val o = JSONObject(raw)
            UnlockTarget(o.getDouble("la"), o.getDouble("lo"), o.getInt("r"), o.getString("n"))
        } catch (e: Exception) {
            Log.e(TAG, "unlock dest read failed", e)
            null
        }
    }

    private fun saveUnlockDest(t: UnlockTarget?) {
        val e = Store.settings(a).edit()
        if (t == null) {
            e.remove("unlock_dest").apply()
            return
        }
        val o = JSONObject()
        o.put("la", t.lat)
        o.put("lo", t.lng)
        o.put("r", t.radius)
        o.put("n", t.name)
        e.putString("unlock_dest", o.toString()).apply()
    }

    private fun refreshModeChips(animate: Boolean = false) {
        val active = Store.isActive(a)
        val mode = unlockMode()
        for ((i, c) in modeChips.withIndex()) {
            val sel = modes[i] == mode
            if (animate) a.animateChip(c, sel) else a.styleChip(c, sel)
            c.alpha = if (active) 0.4f else 1f
        }
        refreshDestRow()
    }

    private fun refreshDestRow() {
        val show = unlockMode() != UNLOCK_TIME
        destRow.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val d = unlockDest()
        destNameText.text = d?.name ?: L.t("Choose a destination", "গন্তব্য বাছুন")
        destRadiusText.text = if (d != null) {
            fmtDistM(d.radius.toFloat()) + " " + L.t("radius · tap to change", "ব্যাসার্ধ · বদলাতে চাপুন")
        } else {
            L.t("Tap to choose", "বাছতে চাপুন")
        }
        destRow.alpha = if (Store.isActive(a)) 0.4f else 1f
    }

    private fun pickMode(m: String) {
        if (Store.isActive(a)) return
        setUnlockMode(m)
        refreshModeChips(true)
        refreshChips()
        tick()
    }

    private fun pickDestination() {
        if (Store.isActive(a)) return
        val places = Extra.places(a)
        val items = ArrayList<String>()
        for (pl in places) items.add(pl.name + "  ·  " + fmtDistM(pl.radius.toFloat()))
        items.add(L.t("My current location", "আমার বর্তমান অবস্থান"))
        items.add(L.t("Open map to add a place", "ম্যাপ খুলে জায়গা যোগ করুন"))
        a.dlg()
            .setTitle(L.t("Unlock destination", "গন্তব্য বাছুন"))
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < places.size -> {
                        val pl = places[which]
                        saveUnlockDest(UnlockTarget(pl.lat, pl.lng, pl.radius, pl.name))
                        refreshDestRow()
                        tick()
                    }
                    which == places.size -> pickCurrentLocation()
                    else -> a.startActivity(Intent(a, MapActivity::class.java))
                }
            }
            .show()
    }

    private fun pickCurrentLocation() {
        if (!Perm.locationOn(a)) {
            a.requestLocationPerm()
            return
        }
        a.toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
        Extra.currentLocation(a) { loc ->
            if (loc == null) {
                a.toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
            } else {
                saveUnlockDest(UnlockTarget(loc.latitude, loc.longitude, 200, L.t("Current location", "বর্তমান অবস্থান")))
                refreshDestRow()
                tick()
            }
        }
    }

    private fun pickRadius() {
        if (Store.isActive(a)) return
        val d = unlockDest() ?: return
        val radii = intArrayOf(100, 200, 500, 1000, 5000, 10000)
        val labels = Array(radii.size) { fmtDistM(radii[it].toFloat()) }
        a.dlg()
            .setTitle(L.t("Radius", "ব্যাসার্ধ"))
            .setItems(labels) { _, which ->
                saveUnlockDest(d.copy(radius = radii[which]))
                refreshDestRow()
                tick()
            }
            .show()
    }

    private fun nextText(now: Long): String {
        val n = Scheduler.nextStart(a, now) ?: return ""
        val c = Calendar.getInstance()
        c.timeInMillis = n.second
        return L.t("Next: ", "পরবর্তী: ") + L.dayLong(dayIdx(c)) + " " + fmtMin(n.first.startMin) + " – " + fmtMin(n.first.endMin)
    }

    private var pillColor = 0
    private val pillBg = GradientDrawable()

    private fun showView(v: View, show: Boolean) {
        val want = if (show) View.VISIBLE else View.GONE
        if (v.visibility == want) return
        v.visibility = want
        if (show) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.EASE).start()
        }
    }

    private fun applyStatus(st: PStatus) {
        if (st == lastStatus) return
        val first = lastStatus == null
        lastStatus = st
        val color = when (st) {
            PStatus.ACTIVE -> C.primary
            PStatus.SCHEDULED -> Color.parseColor("#5AC8FA")
            PStatus.COMPLETED -> C.mint
            PStatus.READY -> C.muted
        }
        val label = when (st) {
            PStatus.ACTIVE -> L.t("PROTECTED", "সুরক্ষিত")
            PStatus.SCHEDULED -> L.t("SCHEDULED", "নির্ধারিত")
            PStatus.COMPLETED -> L.t("COMPLETED", "সম্পন্ন")
            PStatus.READY -> L.t("READY", "প্রস্তুত")
        }
        val from = if (pillColor == 0) color else pillColor
        pillColor = color
        if (first) {
            pillBg.cornerRadius = a.dp(20).toFloat()
            pillBg.setColor(tint(color, 40))
            pill.background = pillBg
            pill.text = label
            pill.setTextColor(color)
            return
        }
        pill.animate().cancel()
        pill.animate().alpha(0f).setDuration(Motion.FAST / 2).withEndAction {
            pill.text = label
            pill.animate().alpha(1f).setDuration(Motion.FAST).start()
        }.start()
        val ev = ArgbEvaluator()
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = Motion.MED
        an.addUpdateListener {
            val c = ev.evaluate(it.animatedFraction, from, color) as Int
            pill.setTextColor(c)
            pillBg.setColor(tint(c, 40))
        }
        an.start()
    }

    private fun updateSchedPill() {
        val on = Store.schedules(a).any { it.enabled }
        if (lastSched == on) return
        lastSched = on
        if (on) {
            schedPill.text = L.t("Enabled", "চালু")
            schedPill.setTextColor(C.primary)
            schedPill.background = rounded(tint(C.primary, 40), a.dp(20).toFloat())
        } else {
            schedPill.text = L.t("Off", "বন্ধ")
            schedPill.setTextColor(C.muted)
            schedPill.background = rounded(C.card2, a.dp(20).toFloat())
        }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        // backup arrival check (the service listener is the main path)
        if (Store.unlockTarget(a) != null) PlaceWatcher.checkUnlockLastKnown(a)
        Pomodoro.sync(a) // advance the Pomodoro set when a phase is over
        val active = Store.isActive(a)
        val st = statusOf(a, active, now)
        applyStatus(st)
        updateSchedPill()
        val p = Store.lock(a)
        val end = p.getLong("end", 0)
        var total = p.getLong("total", 1)
        if (total < 1) total = 1
        val strict = active && p.getBoolean("s_strict", false)
        val pom = Pomodoro.state(a)
        val pomBreak = pom.active && pom.phase != Pomodoro.PHASE_FOCUS
        if (pomBreak) {
            // Pomodoro break: no lock session, the ring counts the break down
            val left = pom.phaseEnd - now
            val phaseTotal = if (pom.phaseTotal > 0) pom.phaseTotal else 1L
            val long = pom.phase == Pomodoro.PHASE_LONG
            ring.active = true
            ring.icon = Ic.CLOCK
            ring.main = fmtHms(left)
            ring.mainColor = C.mint
            ring.sub = if (long) L.t("Long break", "লম্বা বিরতি") else L.t("Break", "বিরতি")
            var frac = left.toFloat() / phaseTotal.toFloat()
            if (frac < 0f) frac = 0f
            if (frac > 1f) frac = 1f
            ring.animateTo(frac)
            titleText.setTextAnimated(
                if (long) L.t("Long break", "লম্বা বিরতি চলছে") else L.t("Break time", "বিরতি চলছে")
            )
            subText.setTextAnimated(
                if (long) L.t("Pomodoro · the set ends after this break", "Pomodoro · এই বিরতির পর সেট শেষ")
                else L.t("Pomodoro · next: focus ${pom.round + 1}/4", "Pomodoro · পরের ফোকাস ${L.num((pom.round + 1).toString())}/৪")
            )
            previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") +
                L.t("Pomodoro break", "Pomodoro বিরতি")
        } else if (active) {
            val mode = Store.unlockMode(a)
            val target = Store.unlockTarget(a)
            if (mode == UNLOCK_LOC && target != null) {
                // no countdown: the lock ends when the user arrives
                ring.active = true
                ring.icon = Ic.PIN
                ring.main = L.t("On the way", "যাত্রায়")
                ring.mainColor = C.text
                ring.sub = if (target.name.length > 14) target.name.take(13) + "…" else target.name
                ring.animateTo(0f)
                titleText.setTextAnimated(L.t("Protection is Active", "প্রটেকশন সক্রিয়"))
                subText.setTextAnimated(L.t("Opens when you arrive at ${target.name}", "লক খুলবে: ${target.name} পৌঁছালে"))
                previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") +
                    L.t("opens at ${target.name}", "${target.name} পৌঁছালে খুলবে")
            } else {
                val left = end - now
                ring.active = true
                ring.icon = Ic.LOCK
                ring.main = fmtHms(left)
                ring.mainColor = C.text
                ring.sub = L.t("remaining", "বাকি")
                var frac = left.toFloat() / total.toFloat()
                if (frac < 0f) frac = 0f
                if (frac > 1f) frac = 1f
                ring.animateTo(frac)
                titleText.setTextAnimated(L.t("Protection is Active", "প্রটেকশন সক্রিয়"))
                val endAt = fmtClock(end)
                if (mode == UNLOCK_EITHER && target != null) {
                    subText.setTextAnimated(
                        if (strict) L.t("Strict mode · locked until $endAt or arrival at ${target.name}", "স্ট্রিক্ট মোড · $endAt পর্যন্ত অথবা ${target.name} পৌঁছালে")
                        else L.t("Ends at $endAt or at ${target.name}", "শেষ হবে $endAt অথবা ${target.name} পৌঁছালে")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total) +
                        L.t(" or at ${target.name}", " অথবা ${target.name} পৌঁছালে")
                } else {
                    subText.setTextAnimated(
                        if (strict) L.t("Strict mode · locked until $endAt", "স্ট্রিক্ট মোড · $endAt পর্যন্ত লক")
                        else L.t("Ends at $endAt", "শেষ হবে $endAt")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total)
                }
                if (pom.active && pom.phase == Pomodoro.PHASE_FOCUS) {
                    subText.setTextAnimated(
                        L.t("Pomodoro · Focus ${pom.round}/4", "Pomodoro · ফোকাস ${L.num(pom.round.toString())}/৪")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") +
                        L.t("Pomodoro focus ${pom.round}/4", "Pomodoro ফোকাস ${L.num(pom.round.toString())}/৪")
                }
            }
        } else {
            val sel = a.selectedMin * 60_000L
            ring.active = false
            ring.icon = Ic.SHIELD
            ring.main = if (st == PStatus.COMPLETED) L.t("DONE", "সম্পন্ন") else L.t("OFF", "বন্ধ")
            ring.mainColor = C.primary
            ring.sub = ""
            ring.animateTo(0f)
            titleText.setTextAnimated(
                if (st == PStatus.COMPLETED) L.t("Session Completed", "সেশন সম্পন্ন")
                else L.t("Protection is Off", "প্রটেকশন বন্ধ আছে")
            )
            subText.setTextAnimated(when (st) {
                PStatus.SCHEDULED -> nextText(now)
                PStatus.COMPLETED -> L.t("You can start a new session", "নতুন সেশন শুরু করতে পারেন")
                else -> L.t("Start protection to activate PowerLock", "পাওয়ার লক চালু করতে প্রটেকশন শুরু করুন")
            })
            val ends = fmtClock(now + sel)
            val mode = unlockMode()
            val dest = unlockDest()
            previewText.text = when {
                mode == UNLOCK_LOC && dest != null -> L.t("Opens when you arrive at ${dest.name}", "লক খুলবে: ${dest.name} পৌঁছালে")
                mode == UNLOCK_LOC -> L.t("Choose a destination below", "নিচে গন্তব্য বাছুন")
                mode == UNLOCK_EITHER && dest != null -> L.t("Duration ${fmtDur(sel)} · ends at $ends or at ${dest.name}", "সময় ${fmtDur(sel)} · শেষ হবে $ends অথবা ${dest.name} পৌঁছালে")
                mode == UNLOCK_EITHER -> L.t("Duration ${fmtDur(sel)} · ends at $ends · choose a destination", "সময় ${fmtDur(sel)} · শেষ হবে $ends · গন্তব্য বাছুন")
                else -> L.t("Duration ${fmtDur(sel)} · ends at $ends", "সময় ${fmtDur(sel)} · শেষ হবে $ends")
            }
        }
        ring.invalidate()
        showView(startBtn, !active && !pom.active)
        showView(stopBtn, active && !strict)
        // location-only sessions have no timer to extend; a Pomodoro phase must not be extended
        showView(extendBtn, active && Store.unlockMode(a) != UNLOCK_LOC && !pom.active)
        if (lastActive != active || lastPom != pom.active) {
            lastActive = active
            lastPom = pom.active
            refreshChips()
            refreshModeChips()
            refreshProfileRow()
        }
        updateWarnings()
        updateLastSummary()
        updateScreenTime()
        updateFocusScore()
        updateTree()
        updateDetox()
        updatePomodoro()
        updateHabits()
        // midnight rollover: the plan card shows the new day's tasks
        if (Store.dayKey(0) != lastPlanDay) {
            lastPlanDay = Store.dayKey(0)
            updatePlan()
        }
        maybeShowTreeOutcome()
        maybeShowSummary()
        maybeShowBadge()
    }

    /** Live screen time today: the stored day total (re-read at most every 10 s)
     * plus the app open right now. Screen-off time never counts. */
    private fun updateScreenTime() {
        val now = System.currentTimeMillis()
        val day = Store.dayKey(0)
        if (cachedUsageSec < 0 || cachedUsageDay != day || now - cachedUsageAt > 10_000) {
            cachedUsageSec = Extra.usageDayTotal(a, day)
            cachedUsageDay = day
            cachedUsageAt = now
        }
        var total = cachedUsageSec
        val (livePkg, liveSince, liveDay) = Extra.liveSlice(a)
        if (livePkg != null && liveDay == day) {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) {
                total += ((SystemClock.uptimeMillis() - liveSince) / 1000).coerceAtLeast(0L)
            }
        }
        screenTimeText.text = L.t("Screen time today: ", "আজকের স্ক্রিন টাইম: ") + fmtDur(total * 1000L)
    }

    /** Today's focus score (0–100), recomputed at most every 10 s. */
    private fun updateFocusScore() {
        val now = System.currentTimeMillis()
        if (cachedScore == null || now - cachedScoreAt > 10_000) {
            cachedScore = FocusScore.today(a)
            cachedScoreAt = now
            FocusScore.recordToday(a, cachedScore!!)
        }
        val b = cachedScore ?: return
        scoreText.text = L.t("Focus score today: ", "আজকের ফোকাস স্কোর: ") + L.num("${b.total}/100")
    }

    // ---------- lock profile (extra apps for this session) ----------

    /** The profile chosen for the next lock (-1 = none). A deleted profile reads as none. */
    private fun selectedProfileId(): Long {
        val id = Store.settings(a).getLong("lock_profile", -1L)
        if (id <= 0) return -1L
        return if (Extra.lockProfiles(a).any { it.id == id }) id else -1L
    }

    private fun refreshProfileRow() {
        val active = Store.isActive(a)
        val pom = Pomodoro.state(a).active
        val sessionName = Store.sessionProfileName(a)
        val chosen = if (selectedProfileId() > 0) Extra.lockProfiles(a).find { it.id == selectedProfileId() } else null
        val sig = "$active:$pom:$sessionName:${chosen?.id ?: -1}:${Extra.lockProfiles(a).size}"
        if (sig == profileSig) return
        profileSig = sig
        profileRow.alpha = if (active || pom) 0.4f else 1f
        when {
            active && sessionName != null -> {
                profileNameText.text = L.t("Profile: ", "প্রোফাইল: ") + sessionName
                profileSubText.text = L.t("This session", "এই সেশনে")
            }
            chosen != null -> {
                profileNameText.text = L.t("Profile: ", "প্রোফাইল: ") + chosen.name
                profileSubText.text = L.num(chosen.pkgs.size.toString()) + " " + L.t("extra apps · tap to change", "টি অতিরিক্ত অ্যাপ · বদলাতে চাপুন")
            }
            Extra.lockProfiles(a).isEmpty() -> {
                profileNameText.text = L.t("Profile: none", "প্রোফাইল: কোনোটা নয়")
                profileSubText.text = L.t("Create one in the Protection tab", "সুরক্ষা ট্যাবে প্রোফাইল বানান")
            }
            else -> {
                profileNameText.text = L.t("Profile: none", "প্রোফাইল: কোনোটা নয়")
                profileSubText.text = L.t("Tap to choose", "বাছতে চাপুন")
            }
        }
    }

    private fun pickProfile() {
        if (Store.isActive(a)) return
        if (Pomodoro.state(a).active) return
        val profiles = Extra.lockProfiles(a)
        if (profiles.isEmpty()) {
            a.toast(L.t("Create a profile in the Protection tab first", "আগে সুরক্ষা ট্যাবে একটা প্রোফাইল বানান"))
            a.go(4)
            return
        }
        val items = ArrayList<String>()
        items.add(L.t("No profile", "কোনো প্রোফাইল নয়"))
        for (p in profiles) items.add(p.name + "  ·  " + L.num(p.pkgs.size.toString()) + " " + L.t("apps", "টি অ্যাপ"))
        a.dlg()
            .setTitle(L.t("Lock profile", "লক প্রোফাইল"))
            .setItems(items.toTypedArray()) { _, which ->
                val id = if (which == 0) -1L else profiles[which - 1].id
                Store.settings(a).edit().putLong("lock_profile", id).apply()
                profileSig = ""
                refreshProfileRow()
                tick()
            }
            .show()
    }

    /** One line under the duration preview: what the last completed lock achieved. */
    private fun updateLastSummary() {
        val s = Store.lastSummary(a)
        val sig = if (s == null) "" else "${s.endMs}:${s.durMs}:${s.blocks}"
        if (sig == lastSummarySig) return
        lastSummarySig = sig
        if (s == null) {
            lastSummaryText.text = ""
            return
        }
        val blocksTxt = if (s.blocks > 0) {
            L.num(s.blocks.toString()) + " " + L.t("blocks", "বার ব্লক")
        } else {
            L.t("no blocks needed", "কোনো ব্লক লাগেনি")
        }
        lastSummaryText.text = L.t("Last session: ", "গত সেশন: ") + fmtDur(s.durMs) + " · " + blocksTxt
    }

    /** The focus tree: live while a lock runs, else the garden's newest tree. */
    private fun updateTree() {
        val active = Store.isActive(a)
        if (active && FocusTree.isPlanted(a)) {
            val frac = FocusTree.currentFraction(a)
            val stage = FocusTree.stageFor(frac)
            treeView.frac = frac
            treeView.alive = true
            treeView.invalidate()
            // label every tick: the % keeps counting inside a stage
            treeSig = "live:$stage"
            treeLabel.text = FocusTree.stageName(stage) + " · " +
                L.num((frac * 100).toInt().toString()) + "%"
            return
        }
        val last = FocusTree.garden(a).firstOrNull()
        if (last == null) {
            val sig = "empty"
            if (sig != treeSig) {
                treeSig = sig
                treeView.frac = 0f
                treeView.alive = true
                treeView.invalidate()
                treeLabel.text = L.t("Start a lock and a seed will be planted", "লক শুরু করলে বীজ রোপণ হবে")
            }
            return
        }
        val sig = "garden:${last.endMs}"
        if (sig != treeSig) {
            treeSig = sig
            treeView.frac = last.stage * 0.25f
            treeView.alive = last.alive
            treeView.invalidate()
            treeLabel.text = FocusTree.stageName(last.stage) + " · " + fmtDate(last.endMs) +
                if (last.alive) "" else " · " + L.t("withered", "শুকিয়ে গেছে")
        }
    }

    /** One-time tree message after a lock ends: withered = toast, survived = in the summary dialog. */
    private fun maybeShowTreeOutcome() {
        when (FocusTree.outcomePending(a)) {
            "dead" -> {
                FocusTree.clearOutcome(a)
                a.toast(L.t("The tree withered", "গাছটা মরে গেল"))
            }
            "alive" -> {
                // the summary dialog carries the survived message; if no summary
                // is pending (edge case), just drop the flag
                if (!Store.summaryPending(a)) FocusTree.clearOutcome(a)
            }
        }
    }

    /** Shows the session-summary dialog once, the first time Home ticks after a lock completes. */
    private fun maybeShowSummary() {
        if (summaryShown) return
        if (!Store.summaryPending(a)) return
        val s = Store.lastSummary(a) ?: run {
            Store.clearSummaryPending(a)
            return
        }
        summaryShown = true
        Store.clearSummaryPending(a)
        val treeLine = if (FocusTree.outcomePending(a) == "alive") {
            FocusTree.clearOutcome(a)
            "\n\n" + L.t("Your tree survived!", "তোমার গাছ বেঁচে গেল!")
        } else ""
        val blocksTxt = if (s.blocks > 0) {
            L.num(s.blocks.toString()) + " " + L.t("times an app was blocked", "বার অ্যাপ ব্লক হয়েছে")
        } else {
            L.t("No app needed blocking", "কোনো অ্যাপ ব্লক করতে হয়নি")
        }
        a.dlg()
            .setTitle(L.t("Session completed!", "সেশন সম্পন্ন!"))
            .setMessage(
                L.t("Focus time: ", "ফোকাস সময়: ") + fmtDur(s.durMs) + "\n" + blocksTxt +
                    treeLine + "\n\n" + Quotes.random()
            )
            .setPositiveButton(L.t("Great!", "ভালো হয়েছে!"), null)
            .show()
    }

    /** Detox challenge dots + label + button. Re-renders only when the state changes. */
    private fun updateDetox() {
        val st = Store.detoxState(a)
        val sig = st.mode + ":" + st.checked + ":" + st.startKey
        if (sig == detoxSig) return
        detoxSig = sig
        val today = Store.dayKey(0)
        for (i in detoxDots.indices) {
            val d = detoxDots[i]
            val done = st.dots.getOrNull(i) == true
            val dayKey = st.days.getOrNull(i) ?: ""
            when {
                done -> {
                    d.text = "●"
                    d.setTextColor(C.mint)
                }
                st.mode != "none" && dayKey.isNotEmpty() && dayKey < today -> {
                    d.text = "●"
                    d.setTextColor(C.danger)
                }
                else -> {
                    d.text = "○"
                    d.setTextColor(C.muted)
                }
            }
        }
        when (st.mode) {
            "done" -> {
                detoxLabel.text = L.t("Challenge complete — 7 out of 7 days!", "চ্যালেঞ্জ সম্পন্ন — ৭ দিনের ৭ দিনই!")
                detoxBtn.text = L.t("Start a new challenge", "নতুন চ্যালেঞ্জ শুরু করুন")
                detoxBtn.visibility = View.VISIBLE
                if (!Store.detoxCelebrated(a)) {
                    Store.markDetoxCelebrated(a)
                    a.dlg()
                        .setTitle(L.t("Challenge complete!", "চ্যালেঞ্জ সম্পন্ন!"))
                        .setMessage(L.t("You completed a lock every day for 7 days straight. Well done!", "টানা ৭ দিন প্রতিদিন লক সম্পন্ন করেছেন। অভিনন্দন!"))
                        .setPositiveButton(L.t("Thank you!", "ধন্যবাদ!"), null)
                        .show()
                }
            }
            "broken" -> {
                detoxLabel.text = L.t("Day missed — the challenge broke. Start again!", "এক দিন মিস হয়েছে — চ্যালেঞ্জ ভেঙে গেছে। আবার শুরু করুন!")
                detoxBtn.text = L.t("Restart challenge", "চ্যালেঞ্জ আবার শুরু করুন")
                detoxBtn.visibility = View.VISIBLE
            }
            "active" -> {
                detoxLabel.text = L.t("Complete a lock every day", "প্রতিদিন একটা লক সম্পন্ন করুন") +
                    " · " + L.num(st.checked.toString()) + "/7"
                detoxBtn.text = L.t("Restart challenge", "চ্যালেঞ্জ আবার শুরু করুন")
                detoxBtn.visibility = View.VISIBLE
            }
            else -> {
                detoxLabel.text = L.t("One completed lock a day, for 7 days in a row.", "টানা ৭ দিন, প্রতিদিন একটা লক সম্পন্ন করুন।")
                detoxBtn.text = L.t("Start challenge", "চ্যালেঞ্জ শুরু করুন")
                detoxBtn.visibility = View.VISIBLE
            }
        }
    }

    /** Pomodoro card: status label + start/stop buttons. Re-renders only when the phase changes. */
    private fun updatePomodoro() {
        val st = Pomodoro.state(a)
        val sig = "${st.active}:${st.phase}:${st.round}"
        if (sig == pomSig) return
        pomSig = sig
        when {
            !st.active -> {
                pomLabel.text = L.t(
                    "25 min focus + 5 min break, 4 rounds, then a 15 min long break.",
                    "২৫ মিনিট ফোকাস + ৫ মিনিট বিরতি, ৪ রাউন্ড, তারপর ১৫ মিনিটের লম্বা বিরতি।"
                )
                pomStartBtn.visibility = View.VISIBLE
                pomStopBtn.visibility = View.GONE
            }
            st.phase == Pomodoro.PHASE_FOCUS -> {
                pomLabel.text = L.t("Focus round ${st.round}/4 is running.", "ফোকাস রাউন্ড ${L.num(st.round.toString())}/৪ চলছে।")
                pomStartBtn.visibility = View.GONE
                pomStopBtn.visibility = View.GONE // use the main Stop button (with the code)
            }
            st.phase == Pomodoro.PHASE_LONG -> {
                pomLabel.text = L.t("Long break — the set ends after this.", "লম্বা বিরতি চলছে — এর পর সেট শেষ।")
                pomStartBtn.visibility = View.GONE
                pomStopBtn.visibility = View.VISIBLE
            }
            else -> {
                pomLabel.text = L.t(
                    "Break — next: focus round ${st.round + 1}/4.",
                    "বিরতি চলছে — পরের ফোকাস রাউন্ড ${L.num((st.round + 1).toString())}/৪।"
                )
                pomStartBtn.visibility = View.GONE
                pomStopBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun startPomodoro() {
        if (Store.isActive(a) || Pomodoro.state(a).active) return
        if (!Perm.accessibilityOn(a)) {
            a.toast(L.t("Enable Accessibility first", "আগে Accessibility চালু করুন"))
            return
        }
        a.dlg()
            .setTitle(L.t("Start Pomodoro?", "পোমোডোরো শুরু করবেন?"))
            .setMessage(
                L.t(
                    "4 focus rounds of 25 minutes with 5-minute breaks, then a 15-minute long break. Protection is on during focus rounds.",
                    "২৫ মিনিটের ৪টা ফোকাস রাউন্ড, মাঝে ৫ মিনিটের বিরতি, শেষে ১৫ মিনিটের লম্বা বিরতি। ফোকাসের সময় প্রটেকশন চালু থাকবে।"
                )
            )
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                if (Pomodoro.start(a)) {
                    a.toast(L.t("Pomodoro started — focus round 1", "পোমোডোরো শুরু — ফোকাস রাউন্ড ১"))
                    tick()
                }
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Habit checklist. Rebuilds only when the habits or today's checks change. */
    private fun updateHabits() {
        val habits = Store.habits(a)
        val checks = Store.habitChecks(a)
        val today = Store.dayKey(0)
        val sig = habits.joinToString("|") { "${it.id}:${it.name}" } + "#" +
            habits.joinToString("|") { if (today in (checks[it.id] ?: emptySet())) "1" else "0" }
        if (sig == habitSig) return
        habitSig = sig
        habitBox.removeAllViews()
        if (habits.isEmpty()) {
            habitBox.addView(
                a.tv(L.t("No habits yet. Add one below — e.g. drinking water, reading, exercise.", "এখনো কোনো অভ্যাস নেই। নিচে যোগ করুন — যেমন পানি খাওয়া, পড়া, ব্যায়াম।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(4))
            )
            return
        }
        for (h in habits) {
            val done = today in (checks[h.id] ?: emptySet())
            val streak = Store.habitStreak(a, h.id)
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(8))
            val box = a.tv(if (done) "☑" else "☐", 22f, if (done) C.mint else C.muted, 1)
            r.addView(box, lp(WRAP, WRAP, r = a.dp(12)))
            val name = a.tv(h.name, 15f, if (done) C.mint else C.text, 1)
            r.addView(name, lp(0, WRAP, weight = 1f))
            if (streak > 0) {
                r.addView(a.tv(L.num(streak.toString()) + " " + L.t("day streak", "দিনের স্ট্রিক"), 12f, C.muted))
            }
            r.pressFeedback()
            r.setOnClickListener {
                Store.toggleHabit(a, h.id)
                updateHabits()
            }
            r.setOnLongClickListener {
                habitOptionsDialog(h)
                true
            }
            habitBox.addView(r)
        }
    }

    private fun addHabitDialog() {
        val et = a.edit(L.t("Habit name (e.g. Drink water)", "অভ্যাসের নাম (যেমন পানি খাওয়া)"), false)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        box.addView(et)
        val d = a.dlg()
            .setTitle(L.t("Add a habit", "অভ্যাস যোগ করুন"))
            .setView(box)
            .setPositiveButton(L.t("Add", "যোগ করুন"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = et.text.toString().trim()
            if (name.isEmpty()) {
                et.error = L.t("Write a name", "নাম লিখুন")
            } else {
                Store.addHabit(a, name.take(40))
                d.dismiss()
                updateHabits()
            }
        }
    }

    private fun habitOptionsDialog(h: Habit) {
        a.dlg()
            .setTitle(h.name)
            .setItems(
                arrayOf(
                    L.t("Rename", "নাম বদলান"),
                    L.t("Delete", "মুছে ফেলুন")
                )
            ) { _, which ->
                if (which == 0) renameHabitDialog(h) else deleteHabit(h)
            }
            .show()
    }

    private fun renameHabitDialog(h: Habit) {
        val et = a.edit(L.t("Habit name", "অভ্যাসের নাম"), false)
        et.setText(h.name)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        box.addView(et)
        val d = a.dlg()
            .setTitle(L.t("Rename habit", "অভ্যাসের নাম বদলান"))
            .setView(box)
            .setPositiveButton(L.t("Save", "সেভ করুন"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = et.text.toString().trim()
            if (name.isEmpty()) {
                et.error = L.t("Write a name", "নাম লিখুন")
            } else {
                val list = Store.habits(a).map { if (it.id == h.id) it.copy(name = name.take(40)) else it }
                Store.saveHabits(a, list)
                d.dismiss()
                updateHabits()
            }
        }
    }

    private fun deleteHabit(h: Habit) {
        a.dlg()
            .setTitle(L.t("Delete this habit?", "এই অভ্যাস মুছে ফেলবেন?"))
            .setMessage(h.name)
            .setPositiveButton(L.t("Delete", "মুছে ফেলুন")) { _, _ ->
                Store.saveHabits(a, Store.habits(a).filter { it.id != h.id })
                updateHabits()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Today's plan rows: tap a row to check it off, ✕ to delete it. */
    private fun updatePlan() {
        planBox.removeAllViews()
        val tasks = Plan.tasksToday(a)
        if (tasks.isEmpty()) {
            planBox.addView(
                a.tv(L.t("Nothing planned yet today.", "আজ এখনো কিছু পরিকল্পনা করা হয়নি।"), 13f, C.muted)
            )
        }
        for (t in tasks) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(8))
            val box = a.tv(if (t.done) "☑" else "☐", 20f, if (t.done) C.mint else C.muted, 1)
            r.addView(box, lp(WRAP, WRAP, r = a.dp(10)))
            val col2 = LinearLayout(a)
            col2.orientation = LinearLayout.VERTICAL
            val title = a.tv(t.title, 15f, if (t.done) C.muted else C.text)
            if (t.done) title.paintFlags = title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            col2.addView(title)
            if (t.timeMin >= 0) col2.addView(a.tv(fmtMin(t.timeMin), 12f, C.primary))
            r.addView(col2, lp(0, WRAP, weight = 1f))
            val del = a.tv("✕", 16f, C.muted, 1)
            del.setPadding(a.dp(10), a.dp(4), a.dp(4), a.dp(4))
            del.setOnClickListener {
                Plan.deleteTask(a, t.id)
                updatePlan()
            }
            r.addView(del)
            r.pressFeedback()
            r.setOnClickListener {
                Plan.toggleTask(a, t.id)
                updatePlan()
            }
            planBox.addView(r)
        }
        planCarryBtn.visibility = if (Plan.yesterdayUndone(a).isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun addPlanDialog() {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val et = a.edit(L.t("What will you do today?", "আজ কী করবে?"), false)
        box.addView(et)
        var timeMin = -1
        val timeRow = a.tv(L.t("No reminder time — tap to set", "রিমাইন্ডারের সময় নেই — সেট করতে চাপুন"), 14f, C.primary)
        timeRow.setPadding(0, a.dp(14), 0, a.dp(4))
        timeRow.setOnClickListener {
            val c = Calendar.getInstance()
            TimePickerDialog(a, { _, h, m ->
                timeMin = h * 60 + m
                timeRow.text = fmtMin(timeMin)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }
        box.addView(timeRow)
        a.dlg()
            .setTitle(L.t("Add a task", "কাজ যোগ করুন"))
            .setView(box)
            .setPositiveButton(L.t("Add", "যোগ করুন")) { _, _ ->
                val title = et.text.toString().trim()
                if (title.isNotEmpty()) {
                    Plan.addTask(a, title, timeMin)
                    updatePlan()
                }
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Shows the "new badge" dialog once, the first time Home ticks after one is earned. */
    private fun maybeShowBadge() {
        if (badgeShown) return
        val b = Badges.pending(a) ?: return
        badgeShown = true
        Badges.clearPending(a)
        a.dlg()
            .setTitle(L.t("New badge!", "নতুন ব্যাজ!"))
            .setMessage(L.t(b.titleEn, b.titleBn) + "\n" + L.t(b.descEn, b.descBn))
            .setPositiveButton(L.t("Great!", "ভালো হয়েছে!"), null)
            .show()
    }

    private fun warnCard(icon: String, title: String, sub: String, onClick: () -> Unit): View {
        val c = a.card(12)
        c.background = rounded(Color.parseColor("#2A2415"), a.dp(18).toFloat(), C.warn, a.dp(1))
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        val ic = a.tv(icon, 22f, C.warn, 1)
        ic.gravity = Gravity.CENTER
        r.addView(ic, lp(a.dp(28), WRAP, r = a.dp(12)))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(a.tv(title, 14f, C.warn, 1))
        col.addView(a.tv(sub, 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        c.addView(r)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun updateWarnings() {
        val acc = Perm.accessibilityOn(a)
        val bat = Perm.batteryOk(a)
        val adminActive = DeviceAdmin.isActive(a)
        if (adminActive) DeviceAdmin.noteActive(a)
        // warn only while a lock runs: the admin was on, now it is off, so
        // the app could be uninstalled mid-lock
        val adminOff = Store.isActive(a) && DeviceAdmin.wasActive(a) && !adminActive
        val sig = "$acc|$bat|$adminOff|${L.bn}"
        if (sig == warnSig) return
        warnSig = sig
        warnBox.removeAllViews()
        if (!acc) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Accessibility is off", "Accessibility বন্ধ আছে"),
                    L.t("Protection cannot work. Tap to enable.", "প্রটেকশন কাজ করবে না। চালু করতে চাপুন।")
                ) { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (!bat) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Battery optimization is on", "ব্যাটারি অপটিমাইজেশন চালু"),
                    L.t("The system may stop PowerLock 2. Tap to allow.", "সিস্টেম PowerLock 2 বন্ধ করে দিতে পারে। অনুমতি দিতে চাপুন।")
                ) {
                    try {
                        a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: Exception) {
                        Log.e(TAG, "battery settings", e)
                    }
                },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (adminOff) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Device admin is off", "ডিভাইস অ্যাডমিন বন্ধ হয়ে গেছে"),
                    L.t("Uninstall protection is off — the app can now be uninstalled during this lock. Tap to turn it back on.", "আনইনস্টল সুরক্ষা বন্ধ — এই লক চলাকালীন অ্যাপ আনইনস্টল করা যাবে। আবার চালু করতে চাপুন।")
                ) {
                    try {
                        a.startActivity(DeviceAdmin.enableIntent(a))
                    } catch (e: Exception) {
                        Log.e(TAG, "admin enable", e)
                    }
                },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
    }

    private fun onStart() {
        if (Store.isActive(a)) return
        if (Pomodoro.state(a).active) return
        if (!Perm.accessibilityOn(a)) {
            a.toast(L.t("Enable Accessibility first", "আগে Accessibility চালু করুন"))
            return
        }
        val mode = unlockMode()
        if (mode == UNLOCK_TIME) {
            startTimeSession()
            return
        }
        // location modes need a destination + location permission
        val dest = unlockDest()
        if (dest == null) {
            a.toast(L.t("Choose a destination first", "আগে গন্তব্য বাছুন"))
            pickDestination()
            return
        }
        if (!Perm.locationOn(a)) {
            a.dlg()
                .setTitle(L.t("Location permission needed", "লোকেশন পারমিশন দরকার"))
                .setMessage(L.t("Location unlock needs location permission, otherwise the lock cannot open on arrival.", "লোকেশনে লক খুলতে লোকেশন পারমিশন লাগবে, না হলে পৌঁছালে লক খুলবে না।"))
                .setPositiveButton(L.t("Allow", "অনুমতি দিন")) { _, _ -> a.requestLocationPerm() }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
            return
        }
        // already at the destination? warn in the confirm dialog (the lock would open at once)
        Extra.currentLocation(a) { loc ->
            var alreadyThere = false
            if (loc != null) {
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, dest.lat, dest.lng, res)
                alreadyThere = res[0] <= dest.radius
            }
            startLocationSession(mode, dest, alreadyThere)
        }
    }

    private fun startTimeSession() {
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
        val profileId = selectedProfileId()
        val prof = if (profileId > 0) Extra.lockProfiles(a).find { it.id == profileId } else null
        val profTxt = if (prof != null) {
            L.t(
                "\n\nProfile: ${prof.name} — ${prof.pkgs.size} extra apps will be blocked.",
                "\n\nপ্রোফাইল: ${prof.name} — আরও ${L.num(prof.pkgs.size.toString())}টি অ্যাপ ব্লক হবে।"
            )
        } else ""
        val extra = if (strictOn) {
            L.t(
                "\n\nStrict mode is ON: you will NOT be able to stop early.",
                "\n\nস্ট্রিক্ট মোড চালু: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না।"
            )
        } else {
            L.t(
                "\n\nStopping early needs a confirmation code.",
                "\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।"
            )
        }
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(
                L.t(
                    "The power menu will be blocked for $d, until $ends.",
                    "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত।"
                ) + profTxt + extra
            )
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                Store.startSession(a, ms, "manual", null, UNLOCK_TIME, profileId)
                a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun startLocationSession(mode: String, dest: UnlockTarget, alreadyThere: Boolean) {
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
        val body = when (mode) {
            UNLOCK_LOC -> L.t(
                "The power menu will stay blocked until you arrive at ${dest.name} (within ${fmtDistM(dest.radius.toFloat())}).",
                "${dest.name} পৌঁছানো পর্যন্ত (ব্যাসার্ধ ${fmtDistM(dest.radius.toFloat())}) পাওয়ার মেনু ব্লক থাকবে।"
            )
            else -> L.t(
                "The power menu will be blocked for $d, until $ends — or it opens early when you arrive at ${dest.name}.",
                "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত — অথবা ${dest.name} পৌঁছালে আগেই খুলবে।"
            )
        }
        val extra = if (strictOn) {
            when (mode) {
                UNLOCK_LOC -> L.t("\n\nStrict mode is ON: only arriving at ${dest.name} can open it.", "\n\nস্ট্রিক্ট মোড চালু: ${dest.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না।")
                else -> L.t("\n\nStrict mode is ON: only the timer or arriving at ${dest.name} can open it.", "\n\nস্ট্রিক্ট মোড চালু: সময় শেষ হওয়া বা ${dest.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না।")
            }
        } else {
            L.t("\n\nStopping early needs a confirmation code.", "\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।")
        }
        val there = if (alreadyThere) L.t("\n\nYou are already at ${dest.name} — the lock will open right away.", "\n\nতুমি ইতিমধ্যে ${dest.name}-এ আছো — লক শুরু করলেই খুলে যাবে।") else ""
        val bgWarn = if (!Perm.backgroundLocationOn(a)) L.t("\n\n\"Allow all the time\" is off: arrival may not be detected with the screen off.", "\n\n\"সবসময় অনুমতি\" বন্ধ: স্ক্রিন বন্ধ থাকলে পৌঁছানো ধরা নাও পড়তে পারে।") else ""
        val profileId = selectedProfileId()
        val prof = if (profileId > 0) Extra.lockProfiles(a).find { it.id == profileId } else null
        val profTxt = if (prof != null) {
            L.t(
                "\n\nProfile: ${prof.name} — ${prof.pkgs.size} extra apps will be blocked.",
                "\n\nপ্রোফাইল: ${prof.name} — আরও ${prof.pkgs.size}টি অ্যাপ ব্লক হবে।"
            )
        } else ""
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(L.num(body + extra + there + bgWarn + profTxt))
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                // location-only: the 15-day cap is only a safety valve, arrival ends it
                val dur = if (mode == UNLOCK_LOC) MAX_MIN * 60_000L else ms
                Store.startSession(a, dur, "manual", dest, mode, profileId)
                PlaceWatcher.sync(a) // start watching for the arrival right away
                PlaceWatcher.checkUnlockNow(a)
                a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun onStop() {
        if (!Store.isActive(a)) return
        if (Store.isStrict(a)) {
            val mode = Store.unlockMode(a)
            val target = Store.unlockTarget(a)
            a.toast(
                when {
                    mode == UNLOCK_LOC && target != null -> L.t("Strict mode: only arriving at ${target.name} can open it", "স্ট্রিক্ট মোড: ${target.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না")
                    mode == UNLOCK_EITHER && target != null -> L.t("Strict mode: only the timer or arriving at ${target.name} can open it", "স্ট্রিক্ট মোড: সময় শেষ হওয়া বা ${target.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না")
                    else -> L.t("Strict mode: cannot stop before the timer ends", "স্ট্রিক্ট মোড: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না")
                }
            )
            return
        }
        val code = (100000..999999).random().toString()
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        box.addView(a.tv(L.t("Type this code to stop:", "বন্ধ করতে এই কোডটি লিখুন:"), 14f, C.muted))
        val codeView = a.tv(L.num(code), 28f, C.mint, 1)
        codeView.gravity = Gravity.CENTER
        codeView.letterSpacing = 0.2f
        box.addView(codeView, lp(MATCH, WRAP, t = a.dp(8)))
        val et = a.edit(L.t("Code", "কোড"), true)
        box.addView(et, lp(MATCH, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Stop protection?", "প্রটেকশন বন্ধ করবেন?"))
            .setView(box)
            .setPositiveButton(L.t("Stop", "বন্ধ করুন"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (et.text.toString().trim() == code) {
                Store.stopSession(a)
                d.dismiss()
                a.toast(L.t("Protection stopped", "প্রটেকশন বন্ধ হয়েছে"))
                tick()
            } else {
                et.error = L.t("Wrong code", "ভুল কোড")
            }
        }
    }

    private fun customDialog() {
        if (Store.isActive(a)) return
        if (Pomodoro.state(a).active) return
        if (unlockMode() == UNLOCK_LOC) return
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val r = LinearLayout(a)
        val dEt = a.edit(L.t("Days", "দিন"), true)
        val hEt = a.edit(L.t("Hours", "ঘণ্টা"), true)
        val mEt = a.edit(L.t("Minutes", "মিনিট"), true)
        r.addView(dEt, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(hEt, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(mEt, lp(0, WRAP, weight = 1f))
        box.addView(r)
        box.addView(a.tv(L.t("Maximum 15 days", "সর্বোচ্চ ১৫ দিন"), 12f, C.muted), lp(WRAP, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Custom duration", "কাস্টম সময়"))
            .setView(box)
            .setPositiveButton(L.t("Set", "ঠিক আছে"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                    (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                    (mEt.text.toString().toLongOrNull() ?: 0L)
            if (total < 1L || total > MAX_MIN.toLong()) {
                mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
            } else {
                a.selectedMin = total.toInt()
                d.dismiss()
                refreshChips(true)
                tick()
            }
        }
    }
}

// =====================================================================
// WEEKLY SCHEDULE
// =====================================================================
class ScheduleScreen(private val a: MainActivity) {
    private lateinit var list: LinearLayout

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 24f, C.text, 1))
        col.addView(
            a.tv(
                L.t("Protection starts automatically at the chosen times.", "নির্ধারিত সময়ে প্রটেকশন নিজে থেকে শুরু হবে।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(4), b = a.dp(16))
        )
        val add = a.primaryBtn("＋  " + L.t("Add schedule", "সময়সূচি যোগ করুন"))
        add.setOnClickListener { editor(null) }
        col.addView(add, lp(MATCH, a.dp(52)))
        list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, lp(MATCH, WRAP, t = a.dp(14)))
        refresh()
        return sv
    }

    private fun refresh() {
        list.removeAllViews()
        val items = Store.schedules(a)
        if (items.isEmpty()) {
            val c = a.card(24)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(IconView(a, Ic.CAL, C.primary), lp(a.dp(56), a.dp(56)))
            val t = a.tv(L.t("No schedules yet", "এখনো কোনো সময়সূচি নেই"), 15f, C.muted, 1)
            t.gravity = Gravity.CENTER
            c.addView(t, lp(MATCH, WRAP, t = a.dp(8)))
            list.addView(c)
            return
        }
        for (s in items.sortedBy { it.startMin }) {
            list.addView(scheduleCard(s), lp(MATCH, WRAP, b = a.dp(12)))
        }
    }

    private fun scheduleCard(s: Schedule): View {
        val c = a.card()
        val days = LinearLayout(a)
        for (i in 0..6) {
            val on = ((s.days shr i) and 1) == 1
            val d = a.tv(L.dayShort(i), 12f, if (on) C.onPrimary else C.muted, 1)
            d.gravity = Gravity.CENTER
            d.background = rounded(if (on) C.primary else C.card2, a.dp(14).toFloat())
            days.addView(d, lp(0, a.dp(32), r = if (i < 6) a.dp(6) else 0, weight = 1f))
        }
        c.addView(days)
        c.addView(a.tv(fmtMin(s.startMin) + "  →  " + fmtMin(s.endMin), 20f, C.text, 1), lp(MATCH, WRAP, t = a.dp(12)))
        if (s.endMin <= s.startMin) {
            c.addView(a.tv(L.t("Overnight · ends next day", "রাত পেরিয়ে · পরের দিন শেষ"), 12f, C.mint))
        }
        val bottom = LinearLayout(a)
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(
            a.tv(if (s.enabled) L.t("Enabled", "চালু") else L.t("Disabled", "বন্ধ"), 13f, C.muted),
            lp(0, WRAP, weight = 1f)
        )
        bottom.addView(a.toggle(s.enabled) { on ->
            val all = Store.schedules(a).map { if (it.id == s.id) it.copy(enabled = on) else it }
            Store.saveSchedules(a, all)
            Scheduler.onAlarm(a)
            list.post { refresh() }
        })
        val del = a.tv("🗑", 20f)
        del.setPadding(a.dp(16), a.dp(4), a.dp(4), a.dp(4))
        del.setOnClickListener {
            a.dlg()
                .setTitle(L.t("Delete schedule?", "সময়সূচি মুছবেন?"))
                .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
                    Store.saveSchedules(a, Store.schedules(a).filter { it.id != s.id })
                    Scheduler.onAlarm(a)
                    refresh()
                }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
        }
        bottom.addView(del)
        c.addView(bottom, lp(MATCH, WRAP, t = a.dp(8)))
        c.setOnClickListener { editor(s) }
        return c
    }

    private fun editor(existing: Schedule?) {
        var days = existing?.days ?: 31
        var sMin = existing?.startMin ?: (22 * 60)
        var eMin = existing?.endMin ?: (6 * 60)

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0)

        val dayRow = LinearLayout(a)
        for (i in 0..6) {
            val d = a.chip(L.dayShort(i), ((days shr i) and 1) == 1)
            d.setPadding(0, a.dp(10), 0, a.dp(10))
            d.setOnClickListener {
                days = days xor (1 shl i)
                a.animateChip(d, ((days shr i) and 1) == 1)
            }
            dayRow.addView(d, lp(0, WRAP, r = if (i < 6) a.dp(4) else 0, weight = 1f))
        }
        box.addView(dayRow)

        val startView = a.tv("", 18f, C.text, 1)
        val endView = a.tv("", 18f, C.text, 1)
        val note = a.tv("", 12f, C.mint)
        for (v in listOf(startView, endView)) {
            v.gravity = Gravity.CENTER
            v.background = withRipple(rounded(C.card2, a.dp(12).toFloat()))
            v.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(14))
        }
        fun render() {
            startView.text = L.t("Start  ", "শুরু  ") + fmtMin(sMin)
            endView.text = L.t("End  ", "শেষ  ") + fmtMin(eMin)
            note.text = if (eMin <= sMin) L.t("Overnight: ends the next day", "রাত পেরিয়ে পরের দিন শেষ হবে") else ""
        }
        startView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                sMin = h * 60 + m
                render()
            }, sMin / 60, sMin % 60, false).show()
        }
        endView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                eMin = h * 60 + m
                render()
            }, eMin / 60, eMin % 60, false).show()
        }
        val tr = LinearLayout(a)
        tr.addView(startView, lp(0, WRAP, r = a.dp(8), weight = 1f))
        tr.addView(endView, lp(0, WRAP, weight = 1f))
        box.addView(tr, lp(MATCH, WRAP, t = a.dp(14)))
        box.addView(note, lp(MATCH, WRAP, t = a.dp(8)))
        render()

        val d = a.dlg()
            .setTitle(if (existing == null) L.t("New schedule", "নতুন সময়সূচি") else L.t("Edit schedule", "সময়সূচি পরিবর্তন"))
            .setView(box)
            .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val all = Store.schedules(a)
            if (days == 0) {
                a.toast(L.t("Select at least one day", "কমপক্ষে একটি দিন বাছুন"))
            } else if (sMin == eMin) {
                a.toast(L.t("Start and end must be different", "শুরু ও শেষের সময় আলাদা হতে হবে"))
            } else if (all.any { it.id != existing?.id && it.days == days && it.startMin == sMin && it.endMin == eMin }) {
                a.toast(L.t("This schedule already exists", "এই সময়সূচি আগেই আছে"))
            } else {
                val updated: List<Schedule> = if (existing == null) {
                    all + Schedule(System.currentTimeMillis(), days, sMin, eMin, true)
                } else {
                    all.map { if (it.id == existing.id) Schedule(it.id, days, sMin, eMin, it.enabled) else it }
                }
                Store.saveSchedules(a, updated)
                Scheduler.onAlarm(a)
                d.dismiss()
                refresh()
            }
        }
    }
}

// =====================================================================
// STATISTICS
// =====================================================================
class StatsScreen(private val a: MainActivity) {
    private var calOffset = 0 // months back from the current month (0..-3)
    private lateinit var calGrid: LinearLayout
    private lateinit var calLabel: TextView
    private lateinit var calDetail: TextView

    fun build(): View {
        Store.finalizeIfExpired(a)
        val (sv, col) = a.page()
        val all = Store.sessions(a)
        val now = System.currentTimeMillis()
        val today = Store.dayStart(now, 0)
        val week = Store.dayStart(now, -6)
        val month = Store.monthStart(now)
        val end = Long.MAX_VALUE
        val completed = all.count { it.ok }
        var sum = 0L
        var longest = 0L
        for (s in all) {
            sum += s.dur
            if (s.dur > longest) longest = s.dur
        }
        val avg = if (all.isEmpty()) 0L else sum / all.size

        col.addView(a.tv(L.t("Statistics", "পরিসংখ্যান"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        // focus card: today's stats as a shareable image
        val shareBtn = a.outlineBtn(L.t("Share focus card", "ফোকাস কার্ড শেয়ার করুন"), C.primary)
        shareBtn.setOnClickListener { FocusCard.share(a) }
        col.addView(shareBtn, lp(MATCH, a.dp(48), b = a.dp(14)))

        fun statCard(icon: Ic, label: String, value: String): View {
            val c = a.card(14)
            c.addView(IconView(a, icon, C.primary), lp(a.dp(24), a.dp(24)))
            c.addView(a.tv(value, 19f, C.text, 1), lp(MATCH, WRAP, t = a.dp(6)))
            c.addView(a.tv(label, 12f, C.muted))
            return c
        }

        fun pair(x: View, y: View) {
            val r = LinearLayout(a)
            r.addView(x, lp(0, MATCH, r = a.dp(10), weight = 1f))
            r.addView(y, lp(0, MATCH, weight = 1f))
            col.addView(r, lp(MATCH, WRAP, b = a.dp(10)))
        }

        // streak + total focus (completed locks only)
        val fs = Store.focusStats(a)
        pair(
            statCard(Ic.CHECK, L.t("Day streak", "দিনের স্ট্রিক"), L.num(fs.streak.toString())),
            statCard(Ic.CLOCK, L.t("Total focus", "মোট ফোকাস"), fmtDur(fs.totalMin * 60000L))
        )

        pair(
            statCard(Ic.CLOCK, L.t("Today", "আজ"), fmtDur(Store.sumRange(all, today, end))),
            statCard(Ic.CAL, L.t("Last 7 days", "গত ৭ দিন"), fmtDur(Store.sumRange(all, week, end)))
        )
        pair(
            statCard(Ic.CAL, L.t("This month", "এই মাস"), fmtDur(Store.sumRange(all, month, end))),
            statCard(Ic.CHECK, L.t("Completed sessions", "সম্পন্ন সেশন"), L.num(completed.toString()))
        )
        pair(
            statCard(Ic.BARS, L.t("Average session", "গড় সেশন"), fmtDur(avg)),
            statCard(Ic.SHIELD, L.t("Longest session", "সবচেয়ে বড় সেশন"), fmtDur(longest))
        )

        // chart
        val chartCard = a.card()
        chartCard.addView(a.section(L.t("Last 7 days", "গত ৭ দিন")))
        val chart = BarChartView(a)
        val vals = ArrayList<Long>()
        val labs = ArrayList<String>()
        for (off in -6..0) {
            val from = Store.dayStart(now, off)
            val to = Store.dayStart(now, off + 1)
            vals.add(Store.sumRange(all, from, to))
            val c = Calendar.getInstance()
            c.timeInMillis = from
            labs.add(L.dayLong(dayIdx(c)))
        }
        chart.values = vals
        chart.labels = labs
        chartCard.addView(chart, lp(MATCH, a.dp(150), t = a.dp(12)))
        col.addView(chartCard, lp(MATCH, WRAP, t = a.dp(4), b = a.dp(14)))

        // focus minutes per day (completed locks only), last 7 days
        val focus7 = a.card()
        focus7.addView(a.section(L.t("Focus — last 7 days", "ফোকাস — গত ৭ দিন")))
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = if (i == 0) L.t("Today", "আজ") else L.dayLong(dayIdx(c))
            val min = fs.last7[6 - i]
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(if (min > 0) fmtDur(min * 60000L) else "—", 14f, if (min > 0) C.mint else C.muted, 1))
            focus7.addView(r)
        }
        col.addView(focus7, lp(MATCH, WRAP, b = a.dp(14)))

        // focus score: today's total with the visible breakdown + 7-day list
        val scoreWeek = FocusScore.week(a)
        val scoreToday = scoreWeek[6]
        FocusScore.recordToday(a, scoreToday)
        val scoreCard = a.card()
        scoreCard.addView(a.section(L.t("Focus score", "ফোকাস স্কোর")))
        val scoreBig = LinearLayout(a)
        scoreBig.gravity = Gravity.CENTER_VERTICAL
        scoreBig.addView(a.tv(L.num(scoreToday.total.toString()), 34f, C.mint, 1))
        scoreBig.addView(a.tv(L.t(" / 100 today", " / ১০০ আজ"), 16f, C.muted), lp(WRAP, WRAP, l = a.dp(4)))
        scoreCard.addView(scoreBig, lp(MATCH, WRAP, t = a.dp(8)))
        fun scoreRow(label: String, pts: Int, max: Int) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(L.num("$pts/$max"), 14f, C.mint, 1))
            scoreCard.addView(r)
        }
        scoreRow(
            L.t("Focus ${scoreToday.focusMin} min (goal ${FocusScore.GOAL_MIN})", "ফোকাস ${L.num(scoreToday.focusMin.toString())} মিনিট (লক্ষ্য ${L.num(FocusScore.GOAL_MIN.toString())})"),
            scoreToday.focusPts, 40
        )
        scoreRow(
            L.t("Completed locks: ${scoreToday.locks}", "সম্পন্ন লক: ${L.num(scoreToday.locks.toString())}টি"),
            scoreToday.locksPts, 20
        )
        scoreRow(
            L.t("Streak: ${scoreToday.streak} days", "স্ট্রিক: ${L.num(scoreToday.streak.toString())} দিন"),
            scoreToday.streakPts, 20
        )
        scoreRow(
            L.t("Temptation attempts: ${scoreToday.attempts}", "প্রলোভন: ${L.num(scoreToday.attempts.toString())} বার"),
            scoreToday.calmPts, 20
        )
        // 7-day scores: stored totals when recorded, else rebuilt from data
        scoreCard.addView(a.tv(L.t("Last 7 days", "গত ৭ দিন"), 13f, C.muted, 1), lp(MATCH, WRAP, t = a.dp(12)))
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = if (i == 0) L.t("Today", "আজ") else L.dayLong(dayIdx(c))
            val total = if (i == 0) scoreToday.total
                else FocusScore.storedTotal(a, i) ?: scoreWeek[6 - i].total
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(L.num(total.toString()), 14f, if (total > 0) C.mint else C.muted, 1))
            scoreCard.addView(r)
        }
        col.addView(scoreCard, lp(MATCH, WRAP, b = a.dp(14)))

        // this week vs last week: focus minutes + completed locks (focus_days, 90 days kept)
        val cmpCard = a.card()
        cmpCard.addView(a.section(L.t("This week vs last week", "এই সপ্তাহ বনাম গত সপ্তাহ")))
        val focusMap = Store.focusDayMap(a)
        val thisKeys = (0..6).map { Store.dayKey(it) }.toSet()
        val lastKeys = (7..13).map { Store.dayKey(it) }.toSet()
        val thisMin = thisKeys.sumOf { focusMap[it] ?: 0 }
        val lastMin = lastKeys.sumOf { focusMap[it] ?: 0 }
        val thisLocks = all.count { it.ok && Store.dayKeyFor(it.start) in thisKeys }
        val lastLocks = all.count { it.ok && Store.dayKeyFor(it.start) in lastKeys }
        fun cmpRow(label: String, thisV: String, lastV: String) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(thisV, 14f, C.mint, 1), lp(WRAP, WRAP, r = a.dp(12)))
            r.addView(a.tv(L.t("last week: ", "গত সপ্তাহে: ") + lastV, 13f, C.muted))
            cmpCard.addView(r)
        }
        cmpRow(L.t("Focus", "ফোকাস"), fmtDur(thisMin * 60000L), fmtDur(lastMin * 60000L))
        cmpRow(L.t("Locks completed", "সম্পন্ন লক"), L.num(thisLocks.toString()), L.num(lastLocks.toString()))
        val cmpText = when {
            lastMin == 0 && thisMin == 0 -> L.t("No focus in either week yet.", "দুই সপ্তাহেই এখনো ফোকাস হয়নি।")
            lastMin == 0 -> L.t("No focus last week — this week is a fresh start!", "গত সপ্তাহে ফোকাস হয়নি — এই সপ্তাহ নতুন শুরু!")
            else -> {
                val pct = (thisMin - lastMin) * 100 / lastMin
                when {
                    pct == 0 -> L.t("Same focus as last week.", "গত সপ্তাহের সমান ফোকাস।")
                    pct > 0 -> L.num(L.t("$pct% more focus than last week", "গত সপ্তাহের চেয়ে $pct% বেশি ফোকাস"))
                    else -> L.num(L.t("${-pct}% less focus than last week", "গত সপ্তাহের চেয়ে ${-pct}% কম ফোকাস"))
                }
            }
        }
        cmpCard.addView(
            a.tv(cmpText, 14f, if (thisMin >= lastMin) C.mint else C.warn, 1),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(cmpCard, lp(MATCH, WRAP, b = a.dp(14)))

        // milestone badges: earned in colour with the date, unearned greyed out
        val badgeCard = a.card()
        badgeCard.addView(a.section(L.t("Badges", "ব্যাজ")))
        val earned = Badges.earned(a)
        val badgeDefs = Badges.ALL
        for (rowIdx in badgeDefs.indices step 2) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until 2) {
                val def = badgeDefs.getOrNull(rowIdx + j)
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.background = rounded(C.card2, a.dp(14).toFloat())
                cell.setPadding(a.dp(10), a.dp(12), a.dp(10), a.dp(12))
                if (def != null) {
                    val whenMs = earned[def.id]
                    val got = whenMs != null
                    cell.alpha = if (got) 1f else 0.45f
                    cell.addView(IconView(a, def.icon, if (got) C.mint else C.muted), lp(a.dp(30), a.dp(30)))
                    val t = a.tv(L.t(def.titleEn, def.titleBn), 13f, if (got) C.text else C.muted, 1)
                    t.gravity = Gravity.CENTER
                    cell.addView(t, lp(MATCH, WRAP, t = a.dp(6)))
                    val d = a.tv(L.t(def.descEn, def.descBn), 11f, C.muted)
                    d.gravity = Gravity.CENTER
                    cell.addView(d, lp(MATCH, WRAP, t = a.dp(2)))
                    val s = a.tv(
                        if (got) fmtDate(whenMs ?: 0L) else L.t("Not earned yet", "এখনো পাননি"),
                        11f, if (got) C.primary else C.muted, 1
                    )
                    s.gravity = Gravity.CENTER
                    cell.addView(s, lp(MATCH, WRAP, t = a.dp(4)))
                }
                row.addView(cell, lp(0, MATCH, r = if (j == 0) a.dp(10) else 0, t = a.dp(10), weight = 1f))
            }
            badgeCard.addView(row)
        }
        col.addView(badgeCard, lp(MATCH, WRAP, b = a.dp(14)))

        // lock calendar: a dot on every day a lock was completed
        val calCard = a.card()
        calCard.addView(a.section(L.t("Lock calendar", "লক ক্যালেন্ডার")))
        val navRow = LinearLayout(a)
        navRow.gravity = Gravity.CENTER_VERTICAL
        val prevBtn = a.tv("‹", 28f, C.primary, 1)
        prevBtn.setPadding(a.dp(12), 0, a.dp(12), 0)
        prevBtn.setOnClickListener {
            if (calOffset > -3) {
                calOffset--
                buildCalendar()
            }
        }
        val nextBtn = a.tv("›", 28f, C.primary, 1)
        nextBtn.setPadding(a.dp(12), 0, a.dp(12), 0)
        nextBtn.setOnClickListener {
            if (calOffset < 0) {
                calOffset++
                buildCalendar()
            }
        }
        calLabel = a.tv("", 16f, C.text, 1)
        calLabel.gravity = Gravity.CENTER
        navRow.addView(prevBtn)
        navRow.addView(calLabel, lp(0, WRAP, weight = 1f))
        navRow.addView(nextBtn)
        calCard.addView(navRow, lp(MATCH, WRAP, t = a.dp(8)))
        // weekday header, Monday first
        val headRow = LinearLayout(a)
        for (i in 0 until 7) {
            val h = a.tv(L.dayShort(i), 12f, C.muted, 1)
            h.gravity = Gravity.CENTER
            headRow.addView(h, lp(0, WRAP, weight = 1f))
        }
        calCard.addView(headRow, lp(MATCH, WRAP, t = a.dp(10)))
        calGrid = LinearLayout(a)
        calGrid.orientation = LinearLayout.VERTICAL
        calCard.addView(calGrid, lp(MATCH, WRAP, t = a.dp(4)))
        calDetail = a.tv("", 13f, C.muted)
        calDetail.gravity = Gravity.CENTER
        calCard.addView(calDetail, lp(MATCH, WRAP, t = a.dp(10)))
        col.addView(calCard, lp(MATCH, WRAP, b = a.dp(14)))
        buildCalendar()

        // garden: every finished tree, newest first (dead ones withered and grey)
        val gardenCard = a.card()
        gardenCard.addView(a.section(L.t("Garden", "বাগান")))
        val trees = FocusTree.garden(a)
        if (trees.isEmpty()) {
            gardenCard.addView(
                a.tv(L.t("No trees yet — complete a lock and your first tree will grow here.", "এখনো কোনো গাছ নেই — একটা লক সম্পন্ন করলে প্রথম গাছ এখানে জন্মাবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
        } else {
            var i = 0
            while (i < trees.size) {
                val row = LinearLayout(a)
                for (j in 0 until 3) {
                    val t = trees.getOrNull(i + j)
                    val cell = LinearLayout(a)
                    cell.orientation = LinearLayout.VERTICAL
                    cell.gravity = Gravity.CENTER_HORIZONTAL
                    if (t != null) {
                        val tv = TreeView(a)
                        tv.frac = t.stage * 0.25f
                        tv.alive = t.alive
                        cell.addView(tv, lp(a.dp(96), a.dp(96)))
                        val dl = a.tv(fmtDate(t.endMs), 11f, C.muted, 1)
                        dl.gravity = Gravity.CENTER
                        cell.addView(dl, lp(MATCH, WRAP, t = a.dp(2)))
                        val ml = a.tv(fmtDur(t.durMin * 60000L), 11f, if (t.alive) C.primary else C.muted, 1)
                        ml.gravity = Gravity.CENTER
                        cell.addView(ml)
                        cell.pressFeedback()
                        cell.setOnClickListener {
                            a.dlg()
                                .setTitle(FocusTree.stageName(t.stage))
                                .setMessage(
                                    fmtDate(t.endMs) + "\n" +
                                        L.t("Lock time: ", "লকের সময়: ") + fmtDur(t.durMin * 60000L) + "\n" +
                                        if (t.alive) L.t("The tree survived", "গাছটা বেঁচে গেছে")
                                        else L.t("The tree withered — the lock was stopped early", "গাছটা শুকিয়ে গেছে — লক আগেই বন্ধ করা হয়েছিল")
                                )
                                .setPositiveButton(L.t("OK", "ঠিক আছে"), null)
                                .show()
                        }
                    }
                    row.addView(cell, lp(0, WRAP, weight = 1f))
                }
                gardenCard.addView(row, lp(MATCH, WRAP, t = a.dp(8)))
                i += 3
            }
        }
        col.addView(gardenCard, lp(MATCH, WRAP, b = a.dp(14)))

        // today's app usage (from the daily-limits tracker): most-used first.
        // Tapping a row opens its daily-limit setting.
        val usageCard = a.card()
        usageCard.addView(a.section(L.t("App usage today", "আজকের অ্যাপ ব্যবহার")))
        val usageToday = Extra.usageTodayAll(a)
        val usage7 = Extra.usage7DayTotal(a)
        if (usageToday.isEmpty()) {
            usageCard.addView(
                a.tv(L.t("No usage tracked yet today. Accessibility must be on to track app time.", "আজ এখনো কোনো ব্যবহার ট্র্যাক হয়নি। অ্যাপের সময় ট্র্যাক করতে Accessibility চালু থাকতে হবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
        } else {
            data class URow(val pkg: String, val label: String, val icon: Drawable?, val todaySec: Long, val weekSec: Long)
            val rows = ArrayList<URow>()
            val pm = a.packageManager
            for ((pkg, sec) in usageToday) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    rows.add(URow(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), sec, usage7[pkg] ?: 0L))
                } catch (e: Exception) {
                    // uninstalled: skip the row (its seconds still count in the totals)
                }
            }
            rows.sortByDescending { it.todaySec }
            val maxSec = rows.firstOrNull()?.todaySec ?: 1L
            for (row in rows.take(10)) {
                val r = LinearLayout(a)
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(0, a.dp(8), 0, a.dp(2))
                if (row.icon != null) {
                    val icon = ImageView(a)
                    icon.setImageDrawable(row.icon)
                    r.addView(icon, lp(a.dp(36), a.dp(36), r = a.dp(12)))
                } else {
                    r.addView(View(a), lp(a.dp(36), a.dp(36), r = a.dp(12)))
                }
                val labelCol = LinearLayout(a)
                labelCol.orientation = LinearLayout.VERTICAL
                labelCol.addView(a.tv(row.label, 15f, C.text))
                labelCol.addView(a.tv(L.t("7 days: ", "৭ দিনে: ") + fmtDur(row.weekSec * 1000L), 12f, C.muted))
                r.addView(labelCol, lp(0, WRAP, weight = 1f))
                r.addView(a.tv(fmtDur(row.todaySec * 1000L), 14f, C.text, 1))
                r.pressFeedback()
                r.setOnClickListener { a.limitChoiceDialog(row.pkg, row.label, Extra.dailyLimit(a, row.pkg)) { a.go(2) } }
                usageCard.addView(r, lp(MATCH, WRAP, t = a.dp(4)))
                // usage bar, relative to the most-used app
                val barWrap = LinearLayout(a)
                barWrap.orientation = LinearLayout.HORIZONTAL
                barWrap.background = rounded(C.card2, a.dp(3).toFloat())
                val frac = (row.todaySec.toFloat() / maxSec.toFloat()).coerceIn(0.02f, 1f)
                val fill = View(a)
                fill.background = rounded(C.primary, a.dp(3).toFloat())
                barWrap.addView(fill, lp(0, a.dp(6), weight = frac))
                barWrap.addView(View(a), lp(0, a.dp(6), weight = 1f - frac))
                usageCard.addView(barWrap, lp(MATCH, a.dp(6), t = a.dp(4)))
            }
            val yest = Extra.usageDayTotal(a, Store.dayKey(1))
            val weekTotal = usage7.values.sum()
            usageCard.addView(
                a.tv(
                    L.t("Yesterday total: ", "গতকাল মোট: ") + fmtDur(yest * 1000L) + " · " +
                        L.t("Last 7 days total: ", "গত ৭ দিন মোট: ") + fmtDur(weekTotal * 1000L),
                    12f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(10))
            )
        }
        col.addView(usageCard, lp(MATCH, WRAP, b = a.dp(14)))

        // temptation counter: blocked/limited apps the user tried to open
        col.addView(a.attemptsCard(), lp(MATCH, WRAP, b = a.dp(14)))

        // history
        val hist = a.card()
        hist.addView(a.section(L.t("Session history", "সেশন ইতিহাস")))
        if (all.isEmpty()) {
            val e = a.tv(L.t("No sessions yet", "এখনো কোনো সেশন নেই"), 14f, C.muted)
            e.gravity = Gravity.CENTER
            hist.addView(e, lp(MATCH, WRAP, t = a.dp(16), b = a.dp(8)))
        } else {
            for (s in all.reversed().take(30)) {
                val r = LinearLayout(a)
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(0, a.dp(10), 0, a.dp(10))
                val left = LinearLayout(a)
                left.orientation = LinearLayout.VERTICAL
                left.addView(a.tv(fmtDate(s.start) + " · " + fmtClock(s.start), 14f, C.text, 1))
                val src = when (s.src) {
                    "schedule" -> L.t("Scheduled", "সময়সূচি")
                    "pomodoro" -> "Pomodoro"
                    else -> L.t("Manual", "ম্যানুয়াল")
                }
                val state = if (s.ok) L.t("Completed", "সম্পন্ন") else L.t("Stopped early", "আগেই বন্ধ")
                left.addView(a.tv("$src · $state", 12f, C.muted))
                r.addView(left, lp(0, WRAP, weight = 1f))
                r.addView(a.tv(fmtDur(s.dur), 15f, if (s.ok) C.mint else C.warn, 1))
                hist.addView(r)
            }
        }
        col.addView(hist)
        return sv
    }

    /** Rebuilds the month grid for the current [calOffset]. */
    private fun buildCalendar() {
        val focus = Store.focusDayMap(a)
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, calOffset)
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        calLabel.text = L.month(month) + " " + L.num(year.toString())
        calGrid.removeAllViews()
        val first = Calendar.getInstance()
        first.set(year, month, 1)
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // blank cells before the 1st (Monday first)
        val todayKey = Store.dayKey(0)
        var day = 1
        while (day <= daysInMonth) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 7) {
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(0, a.dp(5), 0, a.dp(5))
                if ((day == 1 && col < lead) || day > daysInMonth) {
                    // blank cell
                } else {
                    val key = String.format(Locale.US, "%04d%02d%02d", year, month + 1, day)
                    val min = focus[key] ?: 0
                    val isToday = key == todayKey
                    if (isToday) cell.background = rounded(C.card2, a.dp(10).toFloat())
                    val num = a.tv(L.num(day.toString()), 14f, if (min > 0) C.mint else C.text, if (min > 0 || isToday) 1 else 0)
                    num.gravity = Gravity.CENTER
                    cell.addView(num, lp(MATCH, WRAP))
                    val dot = a.tv(if (min > 0) "●" else "", 8f, C.primary, 1)
                    dot.gravity = Gravity.CENTER
                    cell.addView(dot, lp(MATCH, WRAP))
                    val d = day
                    val m = min
                    cell.pressFeedback()
                    cell.setOnClickListener {
                        calDetail.text = if (m > 0) {
                            L.num(d.toString()) + " " + L.month(month) + ": " + fmtDur(m * 60000L) + " " + L.t("focus", "ফোকাস")
                        } else {
                            L.num(d.toString()) + " " + L.month(month) + ": " + L.t("no completed lock", "কোনো লক সম্পন্ন হয়নি")
                        }
                    }
                    day++
                }
                row.addView(cell, lp(0, WRAP, weight = 1f))
            }
            calGrid.addView(row)
        }
        calDetail.text = L.t("Tap a day to see its focus time", "ফোকাস সময় দেখতে দিনে চাপুন")
    }
}

// =====================================================================
// SETTINGS
// =====================================================================
class SettingsScreen(private val a: MainActivity) {

    /** One strict-protection option as its own glowing card. */
    private fun glowOption(title: String, sub: String, checked: Boolean, locked: Boolean, onChange: (Boolean) -> Unit): View {
        val glow = GlowBorderDrawable(a.dp(20).toFloat(), a.dp(8).toFloat(), a.dp(2).toFloat())
        glow.level = if (checked) 1f else 0f
        val box = LinearLayout(a)
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = glow
        box.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(20))
        val texts = LinearLayout(a)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(a.tv(title, 16f, C.text, 1))
        texts.addView(a.tv(sub, 12f, C.muted), lp(MATCH, WRAP, t = a.dp(4)))
        box.addView(texts, lp(0, WRAP, r = a.dp(12), weight = 1f))

        var pulser: ValueAnimator? = null
        fun stopPulse() {
            pulser?.cancel()
            pulser = null
            glow.pulse = 1f
        }
        fun startPulse() {
            pulser?.cancel()
            val p = ValueAnimator.ofFloat(0.55f, 1f)
            p.duration = 1100
            p.repeatMode = ValueAnimator.REVERSE
            p.repeatCount = ValueAnimator.INFINITE
            p.addUpdateListener { glow.pulse = it.animatedValue as Float }
            p.start()
            pulser = p
        }
        val sw = a.toggle(checked) { v ->
            onChange(v)
            val la = ValueAnimator.ofFloat(glow.level, if (v) 1f else 0f)
            la.duration = 300
            la.addUpdateListener { glow.level = it.animatedValue as Float }
            la.start()
            if (v) startPulse() else stopPulse()
        }
        if (locked) sw.isEnabled = false
        box.addView(sw)
        if (checked) startPulse()
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                pulser?.cancel()
            }
        })
        return box
    }

    private fun launch(intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "open settings failed", e)
        }
    }

    private fun blockMessageDialog() {
        val et = a.edit(L.t("Your message", "তোমার মেসেজ"), false)
        et.setText(Extra.blockMessage(a))
        et.setSelection(et.text.length)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(4))
        body.addView(et, lp(MATCH, WRAP))
        body.addView(
            a.tv(L.t("Shown on the block screen when an app is blocked. Empty means the default message.", "অ্যাপ ব্লক হলে এই মেসেজ ব্লক স্ক্রিনে দেখাবে। খালি রাখলে আগের মেসেজই দেখাবে।"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        a.dlg()
            .setTitle(L.t("Block screen message", "ব্লক স্ক্রিনের মেসেজ"))
            .setView(body)
            .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
                Extra.setBlockMessage(a, et.text.toString().trim())
                a.go(3)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Pick the focus sound. Choosing one while a lock runs starts it right away. */
    private fun focusSoundDialog() {
        val choices = listOf(FocusSound.OFF, FocusSound.WHITE, FocusSound.BROWN, FocusSound.RAIN)
        val labels = choices.map { FocusSound.label(it) }.toTypedArray()
        val current = choices.indexOf(FocusSound.choice(a)).coerceAtLeast(0)
        a.dlg()
            .setTitle(L.t("Focus sounds", "ফোকাস সাউন্ড"))
            .setSingleChoiceItems(labels, current) { dlg, which ->
                val picked = choices[which]
                FocusSound.setChoice(a, picked)
                if (picked == FocusSound.OFF) {
                    FocusSound.stop()
                } else if (Store.isActive(a)) {
                    // explicit choice: start even on silent/vibrate
                    FocusSound.startExplicit(a)
                }
                dlg.dismiss()
                a.go(3)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Home background photo: pick from the gallery, or remove the current one. */
    private fun wallpaperDialog() {
        val items = ArrayList<String>()
        items.add(L.t("Pick a photo", "ছবি বাছুন"))
        if (Wallpaper.has(a)) items.add(L.t("Remove the photo", "ছবি সরান"))
        a.dlg()
            .setTitle(L.t("Home background photo", "হোমের ছবি"))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                    a.startActivityForResult(i, REQ_WALLPAPER)
                } else {
                    Wallpaper.remove(a)
                    a.crossfade { }
                }
            }
            .show()
    }

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Settings", "সেটিংস"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        // language: card with two chips (বাংলা | English)
        val lang = a.card()
        lang.addView(a.section(L.t("Language", "ভাষা")))
        val lr = LinearLayout(a)
        val bnChip = a.chip("বাংলা", L.bn)
        val enChip = a.chip("English", !L.bn)
        fun pickLang(code: String) {
            if (Store.settings(a).getString("lang", "bn") == code) return
            a.animateChip(bnChip, code == "bn")
            a.animateChip(enChip, code == "en")
            Store.settings(a).edit().putString("lang", code).apply()
            lr.postDelayed({
                a.crossfade {
                    L.load(a)
                    Store.kickService(a)
                }
            }, 240)
        }
        bnChip.setOnClickListener { pickLang("bn") }
        enChip.setOnClickListener { pickLang("en") }
        lr.addView(bnChip, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        lr.addView(enChip, lp(0, WRAP, t = a.dp(10), weight = 1f))
        lang.addView(lr)
        col.addView(lang, lp(MATCH, WRAP, b = a.dp(12)))

        // theme: card with two chips (এমারেল্ড ডার্ক | অ্যামোলেড ব্ল্যাক)
        val theme = a.card()
        theme.addView(a.section(L.t("Theme", "থিম")))
        val tr = LinearLayout(a)
        val cur = Store.settings(a).getInt("theme", 0)
        val t0 = a.chip(L.t("Emerald Dark", "এমারেল্ড ডার্ক"), cur == 0)
        val t1 = a.chip(L.t("AMOLED Black", "অ্যামোলেড ব্ল্যাক"), cur == 1)
        fun pickTheme(i: Int) {
            if (Store.settings(a).getInt("theme", 0) == i) return
            a.animateChip(t0, i == 0)
            a.animateChip(t1, i == 1)
            Store.settings(a).edit().putInt("theme", i).apply()
            tr.postDelayed({ a.crossfade { C.load(a) } }, 240)
        }
        t0.setOnClickListener { pickTheme(0) }
        t1.setOnClickListener { pickTheme(1) }
        tr.addView(t0, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        tr.addView(t1, lp(0, WRAP, t = a.dp(10), weight = 1f))
        theme.addView(tr)
        // accent colour: 5 swatches — the chosen one colours the ring, chips and buttons
        val acLabel = a.tv(L.t("Accent colour", "অ্যাকসেন্ট রঙ"), 13f, C.muted, 1)
        theme.addView(acLabel, lp(MATCH, WRAP, t = a.dp(16)))
        val acRow = LinearLayout(a)
        acRow.gravity = Gravity.CENTER_VERTICAL
        val swatches = ArrayList<View>()
        fun styleSwatches() {
            val sel = Store.settings(a).getInt("accent", 0)
            for ((i, sw) in swatches.withIndex()) {
                val col = Color.parseColor(C.ACCENTS[i][0])
                sw.background = if (i == sel) rounded(col, a.dp(20).toFloat(), C.text, a.dp(2))
                else rounded(col, a.dp(20).toFloat())
            }
        }
        fun pickAccent(i: Int) {
            if (Store.settings(a).getInt("accent", 0) == i) return
            Store.settings(a).edit().putInt("accent", i).apply()
            styleSwatches()
            acRow.postDelayed({ a.crossfade { C.load(a) } }, 240)
        }
        for (i in C.ACCENTS.indices) {
            val sw = View(a)
            sw.setOnClickListener { pickAccent(i) }
            sw.pressFeedback()
            swatches.add(sw)
            acRow.addView(sw, lp(a.dp(40), a.dp(40), r = if (i < C.ACCENTS.size - 1) a.dp(12) else 0, t = a.dp(10)))
        }
        styleSwatches()
        theme.addView(acRow)
        col.addView(theme, lp(MATCH, WRAP, b = a.dp(12)))

        // morning reminder: a daily notification to start the first lock
        val morn = a.card()
        morn.addView(a.section(L.t("Morning reminder", "সকালের রিমাইন্ডার")))
        val mornSw = a.toggle(Reminders.morningOn(a)) { v ->
            Reminders.setMorning(a, v, Reminders.morningMin(a))
        }
        morn.addView(
            a.row(
                L.t("Remind me every morning", "প্রতি সকালে মনে করিয়ে দিন"),
                L.t("A notification to start today's first lock.", "আজকের প্রথম লক শুরু করার নোটিফিকেশন।"),
                mornSw
            )
        )
        val mornTimeText = a.tv(fmtMin(Reminders.morningMin(a)), 16f, C.primary, 1)
        morn.addView(a.row(L.t("Reminder time", "রিমাইন্ডারের সময়"), null, mornTimeText) {
            val cur = Reminders.morningMin(a)
            TimePickerDialog(a, { _, h, m ->
                val min = h * 60 + m
                Reminders.setMorning(a, Reminders.morningOn(a), min)
                mornTimeText.text = fmtMin(min)
            }, cur / 60, cur % 60, false).show()
        })
        col.addView(morn, lp(MATCH, WRAP, b = a.dp(12)))

        // strict protection
        val strictCard = a.card()
        strictCard.addView(a.section(L.t("Strict protection", "কঠোর সুরক্ষা")))
        val locked = Store.isActive(a)
        val prefs = Store.settings(a)
        strictCard.addView(
            glowOption(
                L.t("Strict mode", "স্ট্রিক্ট মোড"),
                L.t("No early stop. Locked until the timer ends.", "আগে বন্ধ করা যাবে না। সময় শেষ না হওয়া পর্যন্ত লক।"),
                prefs.getBoolean("strict", false),
                locked
            ) { v -> prefs.edit().putBoolean("strict", v).apply() },
            lp(MATCH, WRAP, t = a.dp(8))
        )
        strictCard.addView(
            glowOption(
                L.t("Protect app settings", "অ্যাপ সেটিংস সুরক্ষা"),
                L.t("Block App info and Accessibility pages while the timer runs.", "টাইমার চলাকালীন App info ও Accessibility পাতা খোলা যাবে না।"),
                prefs.getBoolean("guard", true),
                locked
            ) { v -> prefs.edit().putBoolean("guard", v).apply() },
            lp(MATCH, WRAP, t = a.dp(2))
        )
        val note = if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
        else L.t("Changes apply from the next session.", "পরিবর্তন পরের সেশন থেকে কার্যকর হবে।")
        strictCard.addView(a.tv(note, 12f, if (locked) C.warn else C.muted), lp(MATCH, WRAP, t = a.dp(8)))
        col.addView(strictCard, lp(MATCH, WRAP, b = a.dp(12)))

        // device admin: while active, Android blocks uninstalling the app
        if (DeviceAdmin.isActive(a)) DeviceAdmin.noteActive(a)
        val adminCard = a.card()
        adminCard.addView(a.section(L.t("Device admin", "ডিভাইস অ্যাডমিন")))
        val adminOn = DeviceAdmin.isActive(a)
        if (adminOn) {
            adminCard.addView(
                a.tv(L.t("✔ Active — PowerLock 2 cannot be uninstalled right now.", "✔ চালু আছে — এখন PowerLock 2 আনইনস্টল করা যাবে না।"), 14f, C.mint, 1),
                lp(MATCH, WRAP, t = a.dp(8))
            )
            adminCard.addView(
                a.tv(
                    L.t("To turn it off: system Settings → Security → Device admin apps. That deliberate step is the whole point — it stops impulsive uninstalls during a lock.", "বন্ধ করতে: সিস্টেম সেটিংস → নিরাপত্তা → ডিভাইস অ্যাডমিন অ্যাপ। এই ভেবেচিন্তে করা ধাপটাই সুরক্ষা — লক চলাকালীন হঠাৎ আনইনস্টল ঠেকায়।"),
                    13f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(8))
            )
            adminCard.addView(
                a.row(L.t("Open security settings", "নিরাপত্তা সেটিংস খুলুন"), null, a.tv("›", 22f, C.muted)) {
                    launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            )
        } else {
            adminCard.addView(
                a.tv(
                    L.t("While active, Android blocks uninstalling PowerLock 2 — you must first turn the admin off in system Settings. It stops impulsive uninstalls during a lock.", "চালু থাকলে Android PowerLock 2 আনইনস্টল আটকাবে — আগে সিস্টেম সেটিংসে অ্যাডমিন বন্ধ করতে হবে। লক চলাকালীন হঠাৎ আনইনস্টল ঠেকায়।"),
                    13f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(8))
            )
            val offAt = DeviceAdmin.deactivatedAt(a)
            if (offAt > 0) {
                adminCard.addView(
                    a.tv(
                        L.t("Turned off: ", "বন্ধ করা হয়েছিল: ") + fmtDate(offAt) + " " + fmtClock(offAt),
                        13f, C.warn
                    ),
                    lp(MATCH, WRAP, t = a.dp(8))
                )
            }
            val enableBtn = a.outlineBtn(L.t("Enable device admin", "ডিভাইস অ্যাডমিন চালু করুন"), C.primary)
            enableBtn.setOnClickListener {
                try {
                    a.startActivity(DeviceAdmin.enableIntent(a))
                } catch (e: Exception) {
                    Log.e(TAG, "admin enable failed", e)
                }
            }
            adminCard.addView(enableBtn, lp(MATCH, a.dp(48), t = a.dp(12)))
        }
        adminCard.addView(
            a.tv(
                L.t("Honest note: turning the admin off always allows uninstalling — this only makes it a deliberate two-step action, not a one-tap impulse.", "সত্যি কথা: অ্যাডমিন বন্ধ করলেই আনইনস্টল করা যাবে — এটা শুধু এক চাপের বদলে ভেবেচিন্তে দুই ধাপে করতে হয়।"),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(adminCard, lp(MATCH, WRAP, b = a.dp(12)))

        // location lock first, then focus mode (defined in Features.kt)
        col.addView(a.locationCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.historyCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.focusCard(), lp(MATCH, WRAP, b = a.dp(12)))

        // general
        val gen = a.card()
        gen.addView(a.section(L.t("General", "সাধারণ")))
        gen.addView(
            a.row(
                L.t("Sound and vibration", "সাউন্ড ও ভাইব্রেশন"),
                L.t("When a session ends", "সেশন শেষ হলে"),
                a.toggle(Store.soundVib(a)) { v -> Store.settings(a).edit().putBoolean("sound", v).apply() }
            )
        )
        // silence notifications during a lock (DND) — if the permission was
        // revoked, the toggle turns itself off with a toast, never a crash
        if (Dnd.isOn(a) && !Dnd.isGranted(a)) {
            Store.settings(a).edit().putBoolean("dnd_on", false).apply()
            a.toast(L.t("Do Not Disturb permission was revoked — silencing turned off", "Do Not Disturb পারমিশন তুলে নেওয়া হয়েছে — নোটিফিকেশন বন্ধের অপশন বন্ধ করা হলো"))
        }
        lateinit var dndSw: PremiumSwitch
        dndSw = a.toggle(Dnd.isOn(a)) { v ->
            if (v && !Dnd.isGranted(a)) {
                dndSw.setCheckedImmediate(false)
                a.toast(L.t("Allow Do Not Disturb access first, then turn this on again", "আগে Do Not Disturb অনুমতি দিন, তারপর আবার চালু করুন"))
                try {
                    a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "DND settings failed", e)
                }
                return@toggle
            }
            Store.settings(a).edit().putBoolean("dnd_on", v).apply()
        }
        gen.addView(
            a.row(
                L.t("Silence notifications during lock", "লক চলাকালীন নোটিফিকেশন বন্ধ"),
                L.t("Calls are silenced too — alarms still ring.", "কলও চুপ থাকবে — অ্যালার্ম বাজবে।"),
                dndSw
            )
        )
        // custom message on the block screen
        val msg = Extra.blockMessage(a)
        gen.addView(
            a.row(
                L.t("Block screen message", "ব্লক স্ক্রিনের মেসেজ"),
                if (msg.isBlank()) L.t("Tap to write your own motivational message", "নিজের মোটিভেশনাল মেসেজ লিখতে চাপুন") else msg,
                a.tv("›", 22f, C.muted)
            ) { blockMessageDialog() }
        )
        // focus sound during a lock (generated in code — no downloads)
        gen.addView(
            a.row(
                L.t("Focus sounds", "ফোকাস সাউন্ড"),
                FocusSound.label(FocusSound.choice(a)) + " · " + L.t("plays during a lock", "লক চলাকালীন বাজবে"),
                a.tv("›", 22f, C.muted)
            ) { focusSoundDialog() }
        )
        // home background photo: a gallery photo behind the Home screen, dimmed
        gen.addView(
            a.row(
                L.t("Home background photo", "হোমের ছবি"),
                if (Wallpaper.has(a)) L.t("Tap to change or remove", "বদলাতে বা সরাতে চাপুন")
                else L.t("Tap to pick a photo", "ছবি বাছতে চাপুন"),
                a.tv("›", 22f, C.muted)
            ) { wallpaperDialog() }
        )
        gen.addView(
            a.row(L.t("Notification settings", "নোটিফিকেশন সেটিংস"), null, a.tv("›", 22f, C.muted)) {
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
            }
        )
        gen.addView(a.row(L.t("Weekly schedule", "সাপ্তাহিক সময়সূচি"), null, a.tv("›", 22f, C.muted)) { a.go(1) })
        gen.addView(a.row(L.t("Statistics", "পরিসংখ্যান"), null, a.tv("›", 22f, C.muted)) { a.go(2) })
        col.addView(gen, lp(MATCH, WRAP, b = a.dp(12)))

        // permissions
        val perm = a.card()
        perm.addView(a.section(L.t("Permission status", "পারমিশনের অবস্থা")))
        fun permRow(title: String, ok: Boolean, fix: () -> Unit) {
            val mark = a.tv(if (ok) "✔" else "✘", 18f, if (ok) C.primary else C.danger, 1)
            val sub = if (ok) L.t("Granted", "অনুমোদিত") else L.t("Tap to fix", "ঠিক করতে চাপুন")
            perm.addView(a.row(title, sub, mark, if (ok) null else fix))
        }
        permRow("Accessibility", Perm.accessibilityOn(a)) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow(L.t("Notifications", "নোটিফিকেশন"), Perm.notificationsOn(a)) {
            if (Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            }
        }
        permRow(L.t("Battery optimization off", "ব্যাটারি অপটিমাইজেশন বন্ধ"), Perm.batteryOk(a)) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        permRow(L.t("Exact alarms (schedules)", "নির্দিষ্ট সময়ের অ্যালার্ম"), Perm.exactAlarmOk(a)) {
            if (Build.VERSION.SDK_INT >= 31) {
                launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName)))
            }
        }
        col.addView(perm, lp(MATCH, WRAP, b = a.dp(12)))

        // about
        val about = a.card()
        about.addView(a.section(L.t("About PowerLock 2", "PowerLock 2 সম্পর্কে")))
        about.addView(a.tv("PowerLock 2", 18f, C.text, 1), lp(MATCH, WRAP, t = a.dp(10)))
        about.addView(a.tv(L.t("Version ", "সংস্করণ ") + L.num(APP_VERSION), 13f, C.muted))
        about.addView(a.tv(L.t("Build ", "বিল্ড ") + L.num(Extra.BUILD_ID), 13f, C.muted))
        about.addView(a.tv(a.packageName, 12f, C.muted))
        about.addView(
            a.tv(
                L.t(
                    "Note: a normal Android app cannot block the hardware power button, force restart (Power + Volume Down), recovery mode or every system power menu. PowerLock only closes the supported power menu while protection is active.",
                    "দ্রষ্টব্য: সাধারণ অ্যাপ হার্ডওয়্যার পাওয়ার বাটন, ফোর্স রিস্টার্ট (Power + Volume Down), রিকভারি মোড বা সব সিস্টেম পাওয়ার মেনু আটকাতে পারে না। প্রটেকশন চলাকালীন PowerLock শুধু সমর্থিত পাওয়ার মেনুটি বন্ধ করে দেয়।"
                ),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(about)
        return sv
    }
}

// =====================================================================
// PROTECTION (app block: full block + reels block + search block)
// =====================================================================
class ProtectionScreen(private val a: MainActivity) {
    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Protection", "সুরক্ষা"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))
        col.addView(a.protectionIntroCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("full"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("full"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("reels"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("reels"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("msg"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("msg"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.dailyLimitsCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appGroupsCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.profilesCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.siteBlockCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.diagnosticCard(), lp(MATCH, WRAP, b = a.dp(12)))
        return sv
    }
}
