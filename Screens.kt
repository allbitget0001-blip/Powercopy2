package com.example.powerlocks

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
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
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var statusBox: LinearLayout
    private lateinit var endsText: TextView
    private lateinit var previewText: TextView
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var warnBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private val quick = listOf(5, 10, 15, 30, 60)
    private var warnSig = ""
    private var pulse: ObjectAnimator? = null
    private var lastStatus: PStatus? = null
    private var lastActive: Boolean? = null

    private fun quickLabel(m: Int): String = L.num(L.t("$m min", "$m মিনিট"))

    fun build(): View {
        val (sv, col) = a.page()

        // header
        val header = LinearLayout(a)
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = a.tv("🔒", 22f)
        logo.gravity = Gravity.CENTER
        val lg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.primary, C.mint))
        lg.cornerRadius = a.dp(14).toFloat()
        logo.background = lg
        header.addView(logo, lp(a.dp(46), a.dp(46)))
        val names = LinearLayout(a)
        names.orientation = LinearLayout.VERTICAL
        names.addView(a.tv("PowerLock", 22f, C.text, 1))
        names.addView(a.tv(L.t("Premium Edition", "প্রিমিয়াম এডিশন"), 12f, C.muted))
        header.addView(names, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, l = a.dp(12), weight = 1f))
        statusBox = LinearLayout(a)
        statusBox.gravity = Gravity.CENTER_VERTICAL
        statusBox.setPadding(a.dp(12), a.dp(8), a.dp(14), a.dp(8))
        statusDot = View(a)
        statusBox.addView(statusDot, lp(a.dp(8), a.dp(8), r = a.dp(8)))
        statusText = a.tv("", 12f, C.text, 1)
        statusBox.addView(statusText)
        header.addView(statusBox)
        col.addView(header)

        warnBox = LinearLayout(a)
        warnBox.orientation = LinearLayout.VERTICAL
        col.addView(warnBox, lp(MATCH, WRAP, t = a.dp(14)))

        // ring
        ring = RingView(a)
        val rlp = lp(a.dp(250), a.dp(250), t = a.dp(18))
        rlp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(ring, rlp)
        endsText = a.tv("", 15f, C.mint, 1)
        endsText.gravity = Gravity.CENTER
        col.addView(endsText, lp(MATCH, WRAP, t = a.dp(14)))

        // quick timer
        val qc = a.card()
        qc.addView(a.section(L.t("Quick timer", "দ্রুত টাইমার")))
        val row1 = LinearLayout(a)
        val row2 = LinearLayout(a)
        for ((i, m) in quick.withIndex()) {
            val c = a.chip(quickLabel(m), false)
            c.setOnClickListener { pick(m) }
            chips.add(c)
            val rm = if (i == 2) 0 else a.dp(8)
            val target = if (i < 3) row1 else row2
            target.addView(c, lp(0, WRAP, r = rm, t = a.dp(10), weight = 1f))
        }
        val custom = a.chip(L.t("Custom", "কাস্টম"), false)
        custom.setOnClickListener { customDialog() }
        chips.add(custom)
        row2.addView(custom, lp(0, WRAP, t = a.dp(10), weight = 1f))
        qc.addView(row1)
        qc.addView(row2)
        previewText = a.tv("", 13f, C.muted)
        qc.addView(previewText, lp(MATCH, WRAP, t = a.dp(14)))
        col.addView(qc, lp(MATCH, WRAP, t = a.dp(16)))

        // buttons
        startBtn = a.primaryBtn(L.t("Start Protection", "প্রটেকশন শুরু করুন"))
        startBtn.setOnClickListener { onStart() }
        col.addView(startBtn, lp(MATCH, a.dp(56), t = a.dp(16)))
        stopBtn = a.outlineBtn(L.t("Stop Protection", "প্রটেকশন বন্ধ করুন"), C.danger)
        stopBtn.setOnClickListener { onStop() }
        col.addView(stopBtn, lp(MATCH, a.dp(52), t = a.dp(10)))

        // shortcuts
        val sc = LinearLayout(a)
        val items = listOf(
            Triple("🗓", L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 1),
            Triple("📊", L.t("Statistics", "পরিসংখ্যান"), 2),
            Triple("⚙️", L.t("Settings", "সেটিংস"), 3)
        )
        for ((i, trip) in items.withIndex()) {
            val c = a.card(12)
            c.gravity = Gravity.CENTER_HORIZONTAL
            val ic = a.tv(trip.first, 22f)
            ic.gravity = Gravity.CENTER
            c.addView(ic)
            val lb = a.tv(trip.second, 12f, C.muted, 1)
            lb.gravity = Gravity.CENTER
            c.addView(lb, lp(MATCH, WRAP, t = a.dp(6)))
            c.setOnClickListener { a.go(trip.third) }
            sc.addView(c, lp(0, MATCH, r = if (i < 2) a.dp(10) else 0, weight = 1f))
        }
        col.addView(sc, lp(MATCH, WRAP, t = a.dp(16)))

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
            PStatus.ACTIVE -> C.mint
            PStatus.SCHEDULED -> Color.parseColor("#5AC8FA")
            PStatus.COMPLETED -> C.primary
            PStatus.READY -> C.muted
        }
        statusText.text = when (st) {
            PStatus.ACTIVE -> L.t("ACTIVE", "সক্রিয়")
            PStatus.SCHEDULED -> L.t("SCHEDULED", "নির্ধারিত")
            PStatus.COMPLETED -> L.t("COMPLETED", "সম্পন্ন")
            PStatus.READY -> L.t("READY", "প্রস্তুত")
        }
        statusText.setTextColor(color)
        statusDot.background = rounded(color, a.dp(4).toFloat())
        statusBox.background = rounded(C.card, a.dp(20).toFloat(), color, a.dp(1))
        if (st == PStatus.ACTIVE) {
            if (pulse == null) {
                val p = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f)
                p.duration = 900
                p.repeatMode = ValueAnimator.REVERSE
                p.repeatCount = ValueAnimator.INFINITE
                p.start()
                pulse = p
            }
        } else {
            pulse?.cancel()
            pulse = null
            statusDot.alpha = 1f
        }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val active = Store.isActive(a)
        val st = statusOf(a, active, now)
        applyStatus(st)
        val p = Store.lock(a)
        val end = p.getLong("end", 0)
        var total = p.getLong("total", 1)
        if (total < 1) total = 1
        if (active) {
            val left = end - now
            ring.main = fmtHms(left)
            ring.sub = L.t("remaining", "বাকি")
            var frac = left.toFloat() / total.toFloat()
            if (frac < 0f) frac = 0f
            if (frac > 1f) frac = 1f
            ring.animateTo(frac)
            endsText.text = L.t("Ends at ", "শেষ হবে ") + fmtClock(end)
            previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total)
        } else {
            val sel = a.selectedMin * 60_000L
            ring.main = fmtHms(sel)
            ring.sub = L.t("selected", "নির্বাচিত")
            ring.animateTo(0f)
            endsText.text = when (st) {
                PStatus.COMPLETED -> L.t("Session completed ✓", "সেশন সম্পন্ন ✓")
                PStatus.SCHEDULED -> nextText(now)
                else -> L.t("Choose a duration to begin", "শুরু করতে সময় বাছুন")
            }
            val ends = fmtClock(now + sel)
            previewText.text = L.t("Duration ${fmtDur(sel)} · ends at $ends", "সময় ${fmtDur(sel)} · শেষ হবে $ends")
        }
        ring.invalidate()
        startBtn.isEnabled = !active
        startBtn.alpha = if (active) 0.35f else 1f
        stopBtn.isEnabled = active
        stopBtn.alpha = if (active) 1f else 0.35f
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
        r.addView(a.tv(icon, 22f), lp(WRAP, WRAP, r = a.dp(12)))
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
                    "⚠️",
                    L.t("Accessibility is off", "Accessibility বন্ধ আছে"),
                    L.t("Protection cannot work. Tap to enable.", "প্রটেকশন কাজ করবে না। চালু করতে চাপুন।")
                ) { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (!bat) {
            warnBox.addView(
                warnCard(
                    "🔋",
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
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(
                L.t(
                    "The power menu will be blocked for $d, until $ends.\n\nStopping early needs a confirmation code.",
                    "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত।\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।"
                )
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
            c.addView(a.tv("🗓", 34f))
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

        fun statCard(icon: String, label: String, value: String): View {
            val c = a.card(14)
            c.addView(a.tv(icon, 18f))
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
            statCard("☀️", L.t("Today", "আজ"), fmtDur(Store.sumRange(all, today, end))),
            statCard("📅", L.t("Last 7 days", "গত ৭ দিন"), fmtDur(Store.sumRange(all, week, end)))
        )
        pair(
            statCard("🗓", L.t("This month", "এই মাস"), fmtDur(Store.sumRange(all, month, end))),
            statCard("✅", L.t("Completed sessions", "সম্পন্ন সেশন"), L.num(completed.toString()))
        )
        pair(
            statCard("⏱", L.t("Average session", "গড় সেশন"), fmtDur(avg)),
            statCard("🏆", L.t("Longest session", "সবচেয়ে বড় সেশন"), fmtDur(longest))
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
