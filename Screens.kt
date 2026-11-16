package com.example.powerlocks

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

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
    private lateinit var startBtn: LinearLayout
    private lateinit var stopBtn: LinearLayout
    private lateinit var warnBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private val quick = listOf(5, 10, 15, 30, 60)
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
        names.addView(a.tv("PowerLock", 24f, C.text, 1))
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
        col.addView(sc, lp(MATCH, WRAP, t = a.dp(16)))

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
        tick()
        return sv
    }

    private fun refreshChips() {
        val active = Store.isActive(a)
        for ((i, c) in chips.withIndex()) {
            val sel = if (i < quick.size) quick[i] == a.selectedMin else !quick.contains(a.selectedMin)
            a.styleChip(c, sel)
            c.alpha = if (active) 0.4f else 1f
        }
    }

    private fun pick(m: Int) {
        if (Store.isActive(a)) return
        a.selectedMin = m
        refreshChips()
        tick()
    }

    private fun nextText(now: Long): String {
        val n = Scheduler.nextStart(a, now) ?: return ""
        val c = Calendar.getInstance()
        c.timeInMillis = n.second
        return L.t("Next: ", "পরবর্তী: ") + L.dayLong(dayIdx(c)) + " " + fmtMin(n.first.startMin) + " – " + fmtMin(n.first.endMin)
    }

    private fun applyStatus(st: PStatus) {
        if (st == lastStatus) return
        lastStatus = st
        val color = when (st) {
            PStatus.ACTIVE -> C.primary
            PStatus.SCHEDULED -> Color.parseColor("#5AC8FA")
            PStatus.COMPLETED -> C.mint
            PStatus.READY -> C.muted
        }
        pill.text = when (st) {
            PStatus.ACTIVE -> L.t("PROTECTED", "সুরক্ষিত")
            PStatus.SCHEDULED -> L.t("SCHEDULED", "নির্ধারিত")
            PStatus.COMPLETED -> L.t("COMPLETED", "সম্পন্ন")
            PStatus.READY -> L.t("READY", "প্রস্তুত")
        }
        pill.setTextColor(color)
        pill.background = rounded(tint(color, 40), a.dp(20).toFloat())
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
        val active = Store.isActive(a)
        val st = statusOf(a, active, now)
        applyStatus(st)
        updateSchedPill()
        val p = Store.lock(a)
        val end = p.getLong("end", 0)
        var total = p.getLong("total", 1)
        if (total < 1) total = 1
        val strict = active && p.getBoolean("s_strict", false)
        if (active) {
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
            titleText.text = L.t("Protection is Active", "প্রটেকশন সক্রিয়")
            val endAt = fmtClock(end)
            subText.text = if (strict) L.t("Strict mode · locked until $endAt", "স্ট্রিক্ট মোড · $endAt পর্যন্ত লক")
            else L.t("Ends at $endAt", "শেষ হবে $endAt")
            previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total)
        } else {
            val sel = a.selectedMin * 60_000L
            ring.active = false
            ring.icon = Ic.SHIELD
            ring.main = if (st == PStatus.COMPLETED) L.t("DONE", "সম্পন্ন") else L.t("OFF", "বন্ধ")
            ring.mainColor = C.primary
            ring.sub = ""
            ring.animateTo(0f)
            titleText.text = if (st == PStatus.COMPLETED) L.t("Session Completed", "সেশন সম্পন্ন")
            else L.t("Protection is Off", "প্রটেকশন বন্ধ আছে")
            subText.text = when (st) {
                PStatus.SCHEDULED -> nextText(now)
                PStatus.COMPLETED -> L.t("You can start a new session", "নতুন সেশন শুরু করতে পারেন")
                else -> L.t("Start protection to activate PowerLock", "পাওয়ার লক চালু করতে প্রটেকশন শুরু করুন")
            }
            val ends = fmtClock(now + sel)
            previewText.text = L.t("Duration ${fmtDur(sel)} · ends at $ends", "সময় ${fmtDur(sel)} · শেষ হবে $ends")
        }
        ring.invalidate()
        startBtn.visibility = if (active) View.GONE else View.VISIBLE
        stopBtn.visibility = if (active && !strict) View.VISIBLE else View.GONE
        if (lastActive != active) {
            lastActive = active
            refreshChips()
        }
        updateWarnings()
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
        val sig = "$acc|$bat|${L.bn}"
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
                    L.t("The system may stop PowerLock. Tap to allow.", "সিস্টেম PowerLock বন্ধ করে দিতে পারে। অনুমতি দিতে চাপুন।")
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
    }

    private fun onStart() {
        if (Store.isActive(a)) return
        if (!Perm.accessibilityOn(a)) {
            a.toast(L.t("Enable Accessibility first", "আগে Accessibility চালু করুন"))
            return
        }
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
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
                ) + extra
            )
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                Store.startSession(a, ms, "manual")
                a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun onStop() {
        if (!Store.isActive(a)) return
        if (Store.isStrict(a)) {
            a.toast(L.t("Strict mode: cannot stop before the timer ends", "স্ট্রিক্ট মোড: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না"))
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
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val r = LinearLayout(a)
        val h = a.edit(L.t("Hours", "ঘণ্টা"), true)
        val m = a.edit(L.t("Minutes", "মিনিট"), true)
        r.addView(h, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(m, lp(0, WRAP, weight = 1f))
        box.addView(r)
        box.addView(a.tv(L.t("Maximum 24 hours", "সর্বোচ্চ ২৪ ঘণ্টা"), 12f, C.muted), lp(WRAP, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Custom duration", "কাস্টম সময়"))
            .setView(box)
            .setPositiveButton(L.t("Set", "ঠিক আছে"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val total = (h.text.toString().toIntOrNull() ?: 0) * 60 + (m.text.toString().toIntOrNull() ?: 0)
            if (total < 1 || total > 1440) {
                m.error = L.t("1 minute to 24 hours", "১ মিনিট থেকে ২৪ ঘণ্টা")
            } else {
                a.selectedMin = total
                d.dismiss()
                refreshChips()
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
                a.styleChip(d, ((days shr i) and 1) == 1)
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
                val src = if (s.src == "schedule") L.t("Scheduled", "সময়সূচি") else L.t("Manual", "ম্যানুয়াল")
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
}

// =====================================================================
// SETTINGS
// =====================================================================
class SettingsScreen(private val a: MainActivity) {

    private fun setLang(code: String) {
        Store.settings(a).edit().putString("lang", code).apply()
        L.load(a)
        Store.kickService(a)
        a.recreate()
    }

    private fun setTheme(i: Int) {
        Store.settings(a).edit().putInt("theme", i).apply()
        a.recreate()
    }

    private fun launch(intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "open settings failed", e)
        }
    }

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Settings", "সেটিংস"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        // language
        val lang = a.card()
        lang.addView(a.section(L.t("Language", "ভাষা")))
        val lr = LinearLayout(a)
        val bnChip = a.chip("বাংলা", L.bn)
        val enChip = a.chip("English", !L.bn)
        bnChip.setOnClickListener { if (!L.bn) setLang("bn") }
        enChip.setOnClickListener { if (L.bn) setLang("en") }
        lr.addView(bnChip, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        lr.addView(enChip, lp(0, WRAP, t = a.dp(10), weight = 1f))
        lang.addView(lr)
        col.addView(lang, lp(MATCH, WRAP, b = a.dp(12)))

        // theme
        val theme = a.card()
        theme.addView(a.section(L.t("Theme", "থিম")))
        val tr = LinearLayout(a)
        val cur = Store.settings(a).getInt("theme", 0)
        val t0 = a.chip(L.t("Emerald Dark", "এমারেল্ড ডার্ক"), cur == 0)
        val t1 = a.chip(L.t("AMOLED Black", "অ্যামোলেড ব্ল্যাক"), cur == 1)
        t0.setOnClickListener { if (cur != 0) setTheme(0) }
        t1.setOnClickListener { if (cur != 1) setTheme(1) }
        tr.addView(t0, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        tr.addView(t1, lp(0, WRAP, t = a.dp(10), weight = 1f))
        theme.addView(tr)
        col.addView(theme, lp(MATCH, WRAP, b = a.dp(12)))

        // strict protection
        val strictCard = a.card()
        strictCard.addView(a.section(L.t("Strict protection", "কঠোর সুরক্ষা")))
        val locked = Store.isActive(a)
        val prefs = Store.settings(a)
        val sw1 = a.toggle(prefs.getBoolean("strict", false)) { v -> prefs.edit().putBoolean("strict", v).apply() }
        val sw2 = a.toggle(prefs.getBoolean("guard", true)) { v -> prefs.edit().putBoolean("guard", v).apply() }
        if (locked) {
            sw1.isEnabled = false
            sw2.isEnabled = false
        }
        strictCard.addView(
            a.row(
                L.t("Strict mode", "স্ট্রিক্ট মোড"),
                L.t("No early stop. Locked until the timer ends.", "আগে বন্ধ করা যাবে না। সময় শেষ না হওয়া পর্যন্ত লক।"),
                sw1
            )
        )
        strictCard.addView(
            a.row(
                L.t("Protect app settings", "অ্যাপ সেটিংস সুরক্ষা"),
                L.t("Block App info and Accessibility pages while the timer runs.", "টাইমার চলাকালীন App info ও Accessibility পাতা খোলা যাবে না।"),
                sw2
            )
        )
        val note = if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
        else L.t("Changes apply from the next session.", "পরিবর্তন পরের সেশন থেকে কার্যকর হবে।")
        strictCard.addView(a.tv(note, 12f, if (locked) C.warn else C.muted), lp(MATCH, WRAP, t = a.dp(6)))
        col.addView(strictCard, lp(MATCH, WRAP, b = a.dp(12)))

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

        // diagnostics (opens its own screen)
        val diagCard = a.card()
        diagCard.addView(a.section(L.t("Diagnostics", "ডায়াগনস্টিক")))
        diagCard.addView(
            a.row(
                L.t("Check if protection works", "প্রটেকশন ঠিকমতো কাজ করছে কিনা দেখুন"),
                L.t("Includes a lock screen test", "লক স্ক্রিন টেস্টসহ"),
                a.tv("›", 22f, C.muted)
            ) { a.go(4) }
        )
        col.addView(diagCard, lp(MATCH, WRAP, b = a.dp(12)))

        // about
        val about = a.card()
        about.addView(a.section(L.t("About PowerLock", "PowerLock সম্পর্কে")))
        about.addView(a.tv("PowerLock", 18f, C.text, 1), lp(MATCH, WRAP, t = a.dp(10)))
        about.addView(a.tv(L.t("Version ", "সংস্করণ ") + L.num(APP_VERSION), 13f, C.muted))
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
// DIAGNOSTICS (separate screen, opened from Settings)
// =====================================================================
class DiagnosticsScreen(private val a: MainActivity) {
    private lateinit var topBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private var showAll = false
    private var lastSig = ""

    private fun sig(): String {
        val e = Diag.entries(a)
        val last = e.lastOrNull()
        return "${e.size}|${last?.time}|${last?.kind}|${Perm.accessibilityOn(a)}|${Perm.batteryOk(a)}|${Perm.notificationsOn(a)}|$showAll|${L.bn}"
    }

    private fun launch(i: Intent) {
        try {
            a.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
        }
    }

    fun tick() {
        if (sig() != lastSig) refresh()
    }

    fun build(): View {
        val (sv, col) = a.page()

        val bar = LinearLayout(a)
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = a.tv("←", 28f, C.primary, 1)
        back.setPadding(0, 0, a.dp(16), 0)
        back.setOnClickListener { a.go(3) }
        bar.addView(back)
        bar.addView(a.tv(L.t("Diagnostics", "ডায়াগনস্টিক"), 24f, C.text, 1))
        col.addView(bar)
        col.addView(
            a.tv(
                L.t("A simple check to see if PowerLock is working.", "PowerLock ঠিকমতো কাজ করছে কিনা দেখার সহজ উপায়।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(4), b = a.dp(16))
        )

        topBox = LinearLayout(a)
        topBox.orientation = LinearLayout.VERTICAL
        col.addView(topBox)

        // how to test
        val how = a.card()
        how.addView(a.section(L.t("Test the lock screen", "লক স্ক্রিন টেস্ট করুন")))
        val steps = listOf(
            L.t("Start a 2 minute lock on Home.", "হোম থেকে ২ মিনিটের লক চালু করুন।"),
            L.t("Press the power button once to lock the phone.", "পাওয়ার বাটন একবার চেপে ফোন লক করুন।"),
            L.t("Wake the screen and long-press the power button.", "স্ক্রিন জ্বালিয়ে পাওয়ার বাটন ধরে রাখুন।"),
            L.t("Unlock the phone and come back here.", "ফোন আনলক করে এখানে ফিরে আসুন।")
        )
        for ((i, st) in steps.withIndex()) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(10), 0, 0)
            val n = a.tv(L.num((i + 1).toString()), 13f, C.onPrimary, 1)
            n.gravity = Gravity.CENTER
            n.background = rounded(C.primary, a.dp(12).toFloat())
            r.addView(n, lp(a.dp(24), a.dp(24), r = a.dp(12)))
            r.addView(a.tv(st, 14f, C.textSoft), lp(0, WRAP, weight = 1f))
            how.addView(r)
        }
        col.addView(how, lp(MATCH, WRAP, t = a.dp(14)))

        listBox = LinearLayout(a)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox, lp(MATCH, WRAP, t = a.dp(14)))

        val br = LinearLayout(a)
        val refreshBtn = a.outlineBtn(L.t("Refresh", "রিফ্রেশ"), C.primary)
        refreshBtn.setOnClickListener { refresh() }
        val clearBtn = a.outlineBtn(L.t("Clear log", "লগ মুছুন"), C.muted)
        clearBtn.setOnClickListener {
            Diag.clear(a)
            refresh()
        }
        br.addView(refreshBtn, lp(0, a.dp(48), r = a.dp(10), weight = 1f))
        br.addView(clearBtn, lp(0, a.dp(48), weight = 1f))
        col.addView(br, lp(MATCH, WRAP, t = a.dp(14)))
        val copyBtn = a.primaryBtn(L.t("Copy report to share", "রিপোর্ট কপি করুন"))
        copyBtn.setOnClickListener { copyReport() }
        col.addView(copyBtn, lp(MATCH, a.dp(52), t = a.dp(10)))

        refresh()
        return sv
    }

    private fun refresh() {
        lastSig = sig()
        val entries = Diag.entries(a)
        val blocks = entries.filter { it.kind == "BLOCK" }
        val blockedLock = blocks.any { it.locked }
        val blockedOpen = blocks.any { !it.locked }
        val menuLock = entries.any { it.kind == "WINDOW" && isMenuEntry(it) && it.locked }
        val acc = Perm.accessibilityOn(a)
        val bat = Perm.batteryOk(a)
        val notif = Perm.notificationsOn(a)

        // ---- summary ----
        topBox.removeAllViews()
        val sumTitle: String
        val sumSub: String
        val sumColor: Int
        if (!acc) {
            sumTitle = L.t("Setup needed", "সেটআপ দরকার")
            sumSub = L.t("Turn on Accessibility for PowerLock.", "PowerLock-এর জন্য Accessibility চালু করুন।")
            sumColor = C.danger
        } else if (blockedLock) {
            sumTitle = L.t("All good", "সব ঠিক আছে")
            sumSub = L.t("Works on the lock screen too.", "লক স্ক্রিনেও কাজ করছে।")
            sumColor = C.primary
        } else if (blockedOpen) {
            sumTitle = L.t("Almost there", "প্রায় ঠিক")
            sumSub = L.t("Works when unlocked. Lock screen is not tested yet.", "আনলক অবস্থায় কাজ করছে। লক স্ক্রিন এখনো টেস্ট হয়নি।")
            sumColor = C.warn
        } else {
            sumTitle = L.t("Not tested yet", "এখনো টেস্ট হয়নি")
            sumSub = L.t("Start a lock and open the power menu to test.", "লক চালু করে পাওয়ার মেনু খুলে টেস্ট করুন।")
            sumColor = C.muted
        }
        val sum = a.card(18)
        sum.background = rounded(tint(sumColor, 30), a.dp(20).toFloat(), sumColor, a.dp(1))
        sum.addView(a.tv(sumTitle, 22f, sumColor, 1))
        sum.addView(a.tv(sumSub, 14f, C.textSoft), lp(MATCH, WRAP, t = a.dp(4)))
        topBox.addView(sum, lp(MATCH, WRAP, b = a.dp(12)))

        // ---- checklist ----
        val cc = a.card()
        cc.addView(a.section(L.t("Checklist", "চেকলিস্ট")))
        fun checkRow(title: String, state: Int, sub: String, onClick: (() -> Unit)?) {
            val mark = when (state) {
                1 -> a.tv("✔", 18f, C.primary, 1)
                0 -> a.tv("✘", 18f, C.danger, 1)
                else -> a.tv("?", 18f, C.warn, 1)
            }
            cc.addView(a.row(title, sub, mark, onClick))
        }
        val fixAcc: () -> Unit = { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val fixBat: () -> Unit = { launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val fixNotif: () -> Unit = {
            if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        checkRow(
            "Accessibility", if (acc) 1 else 0,
            if (acc) L.t("Turned on", "চালু আছে") else L.t("Turned off · tap to fix", "বন্ধ · ঠিক করতে চাপুন"),
            if (acc) null else fixAcc
        )
        checkRow(
            L.t("Battery", "ব্যাটারি"), if (bat) 1 else 0,
            if (bat) L.t("Not restricted", "সীমাবদ্ধ নয়") else L.t("Restricted · tap to fix", "সীমাবদ্ধ · ঠিক করতে চাপুন"),
            if (bat) null else fixBat
        )
        checkRow(
            L.t("Notifications", "নোটিফিকেশন"), if (notif) 1 else 0,
            if (notif) L.t("Allowed", "অনুমোদিত") else L.t("Blocked · tap to fix", "বন্ধ · ঠিক করতে চাপুন"),
            if (notif) null else fixNotif
        )
        checkRow(
            L.t("Power menu blocked (unlocked)", "পাওয়ার মেনু ব্লক (আনলক)"), if (blockedOpen) 1 else 2,
            if (blockedOpen) L.t("Verified", "যাচাই হয়েছে") else L.t("Not tested yet", "এখনো টেস্ট হয়নি"),
            null
        )
        val lockState = if (blockedLock) 1 else if (menuLock) 0 else 2
        val lockSub = if (blockedLock) L.t("Verified", "যাচাই হয়েছে")
        else if (menuLock) L.t("Menu opened but was not blocked (is a timer running?)", "মেনু খুলেছে কিন্তু ব্লক হয়নি (টাইমার চালু ছিল?)")
        else L.t("Not tested yet", "এখনো টেস্ট হয়নি")
        checkRow(L.t("Power menu blocked (lock screen)", "পাওয়ার মেনু ব্লক (লক স্ক্রিন)"), lockState, lockSub, null)
        topBox.addView(cc)

        // ---- recent activity ----
        listBox.removeAllViews()
        val lc = a.card()
        lc.addView(a.section(L.t("Recent activity", "সাম্প্রতিক ঘটনা")))
        val sw = a.toggle(showAll) { v ->
            showAll = v
            listBox.post { refresh() }
        }
        lc.addView(a.row(L.t("Show all system events", "সব সিস্টেম ঘটনা দেখান"), null, sw))
        val shown = entries.reversed().filter { showAll || it.kind == "BLOCK" || isMenuEntry(it) }.take(15)
        if (shown.isEmpty()) {
            val e = a.tv(L.t("Nothing recorded yet", "এখনো কিছু রেকর্ড হয়নি"), 14f, C.muted)
            e.gravity = Gravity.CENTER
            lc.addView(e, lp(MATCH, WRAP, t = a.dp(10), b = a.dp(6)))
        } else {
            for (en in shown) lc.addView(entryRow(en))
        }
        listBox.addView(lc)
    }

    private fun entryRow(en: DiagEntry): View {
        val isBlock = en.kind == "BLOCK"
        val isMenu = !isBlock && isMenuEntry(en)
        val title = if (isBlock) L.t("Power menu blocked", "পাওয়ার মেনু ব্লক করা হয়েছে")
        else if (isMenu) L.t("Power menu opened", "পাওয়ার মেনু খুলেছে")
        else L.t("System window", "সিস্টেম উইন্ডো")
        val color = if (isBlock) C.primary else if (isMenu) C.warn else C.muted

        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, a.dp(10), 0, a.dp(10))
        val dot = View(a)
        dot.background = rounded(color, a.dp(5).toFloat())
        r.addView(dot, lp(a.dp(10), a.dp(10), r = a.dp(12)))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(a.tv(title, 14f, if (isBlock || isMenu) C.text else C.muted, 1))
        val sub = L.num(en.time) + (if (en.cls.isNotEmpty()) " · " + en.cls else "")
        col.addView(a.tv(sub, 11f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        val badge = a.tv(
            if (en.locked) L.t("LOCK SCREEN", "লক স্ক্রিন") else L.t("UNLOCKED", "আনলক"),
            10f, if (en.locked) C.mint else C.muted, 1
        )
        badge.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5))
        badge.background = rounded(tint(if (en.locked) C.primary else C.muted, 35), a.dp(10).toFloat())
        r.addView(badge)
        return r
    }

    private fun copyReport() {
        val e = Diag.entries(a)
        val sb = StringBuilder()
        sb.append("PowerLock diagnostics\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        sb.append("App: $APP_VERSION\n")
        sb.append("Accessibility: ${Perm.accessibilityOn(a)}\n")
        sb.append("Battery ok: ${Perm.batteryOk(a)}\n")
        sb.append("Blocked when unlocked: ${e.any { it.kind == "BLOCK" && !it.locked }}\n")
        sb.append("Blocked on lock screen: ${e.any { it.kind == "BLOCK" && it.locked }}\n")
        sb.append("--- events (newest first) ---\n")
        for (x in e.reversed().take(30)) {
            sb.append("${x.time} ${x.kind} ${x.cls} [${x.text}] lock=${x.locked}\n")
        }
        try {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PowerLock report", sb.toString()))
            a.toast(L.t("Report copied", "রিপোর্ট কপি হয়েছে"))
        } catch (ex: Exception) {
            Log.e(TAG, "copy failed", ex)
        }
    }
}
