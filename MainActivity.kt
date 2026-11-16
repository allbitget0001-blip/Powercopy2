package com.example.powerlocks

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private val navItems = ArrayList<LinearLayout>()
    private val navLabels = ArrayList<TextView>()
    private val navIcons = ArrayList<IconView>()
    private var tab = 0
    private var home: HomeScreen? = null
    private var diag: DiagnosticsScreen? = null

    /** Selected quick-timer duration in minutes (persisted). */
    var selectedMin: Int
        get() = Store.settings(this).getInt("sel_min", 30)
        set(v) {
            Store.settings(this).edit().putInt("sel_min", v).apply()
        }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                Store.finalizeIfExpired(this@MainActivity)
                home?.tick()
                diag?.tick()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "tick failed", e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        C.load(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.bg)
        root.fitsSystemWindows = true
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, weight = 1f))
        root.addView(buildNav(), lp(MATCH, WRAP))
        setContentView(root)

        Scheduler.onAlarm(this)
        go(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        if (tab == 3) go(3) // refresh permission status after returning from system settings
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    fun go(i: Int) {
        tab = i
        content.removeAllViews()
        home = null
        diag = null
        val v: View = when (i) {
            0 -> {
                val h = HomeScreen(this)
                home = h
                h.build()
            }
            1 -> ScheduleScreen(this).build()
            2 -> StatsScreen(this).build()
            4 -> {
                val d = DiagnosticsScreen(this)
                diag = d
                d.build()
            }
            else -> SettingsScreen(this).build()
        }
        v.alpha = 0f
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).setDuration(220).start()
        refreshNav()
    }

    private fun buildNav(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(C.bg)
        val line = View(this)
        line.setBackgroundColor(C.card)
        wrap.addView(line, lp(MATCH, dp(1)))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(8), dp(10), dp(8), dp(10))
        val labels = listOf(
            L.t("Home", "হোম"),
            L.t("Schedule", "সময়সূচি"),
            L.t("Statistics", "পরিসংখ্যান"),
            L.t("Settings", "সেটিংস")
        )
        val kinds = listOf(Ic.HOME, Ic.CAL, Ic.BARS, Ic.GEAR)
        for (i in 0..3) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(0, dp(4), 0, dp(4))
            val ic = IconView(this, kinds[i], C.muted)
            item.addView(ic, lp(dp(26), dp(26)))
            val lb = tv(labels[i], 12f, C.muted, 1)
            lb.gravity = Gravity.CENTER
            item.addView(lb, lp(WRAP, WRAP, t = dp(4)))
            item.setOnClickListener { if (tab != i) go(i) }
            navItems.add(item)
            navLabels.add(lb)
            navIcons.add(ic)
            bar.addView(item, lp(0, WRAP, weight = 1f))
        }
        wrap.addView(bar, lp(MATCH, WRAP))
        return wrap
    }

    private fun refreshNav() {
        for (i in navItems.indices) {
            val sel = i == (if (tab == 4) 3 else tab)
            navLabels[i].setTextColor(if (sel) C.primary else C.muted)
            navIcons[i].color = if (sel) C.primary else C.muted
        }
    }
}
