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
    private var tab = 0
    private var home: HomeScreen? = null

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
        window.navigationBarColor = C.card

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
        val v: View = when (i) {
            0 -> {
                val h = HomeScreen(this)
                home = h
                h.build()
            }
            1 -> ScheduleScreen(this).build()
            2 -> StatsScreen(this).build()
            else -> SettingsScreen(this).build()
        }
        v.alpha = 0f
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).setDuration(220).start()
        refreshNav()
    }

    private fun buildNav(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(C.card)
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))
        bar.elevation = dp(8).toFloat()
        val labels = listOf(
            L.t("Home", "হোম"),
            L.t("Schedule", "সময়সূচি"),
            L.t("Stats", "পরিসংখ্যান"),
            L.t("Settings", "সেটিংস")
        )
        val icons = listOf("🏠", "🗓", "📊", "⚙️")
        for (i in 0..3) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(0, dp(6), 0, dp(6))
            val ic = tv(icons[i], 20f)
            ic.gravity = Gravity.CENTER
            item.addView(ic)
            val lb = tv(labels[i], 11f, C.muted, 1)
            lb.gravity = Gravity.CENTER
            item.addView(lb)
            item.setOnClickListener { if (tab != i) go(i) }
            navItems.add(item)
            navLabels.add(lb)
            bar.addView(item, lp(0, WRAP, l = dp(2), r = dp(2), weight = 1f))
        }
        return bar
    }

    private fun refreshNav() {
        for (i in navItems.indices) {
            val sel = i == tab
            navItems[i].background = withRipple(rounded(if (sel) C.card2 else Color.TRANSPARENT, dp(16).toFloat()))
            navLabels[i].setTextColor(if (sel) C.primary else C.muted)
        }
    }
}
