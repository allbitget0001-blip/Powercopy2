package com.example.powerlocks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout

/**
 * Power menu protection engine (event driven, no polling).
 *
 * While a session is active:
 *  1. the power menu is recognised from the window event itself (class / text), with no extra IPC,
 *  2. if needed, the window content is scanned (throttled, only for system UI),
 *  3. Back is pressed immediately, then Back again and Home shortly after, so the user ends on the Home screen,
 *  4. a short "PowerLock active" shield blocks touches on the slider while the menu is closing.
 * The protection state is cached from SharedPreferences, so events do not read storage.
 * Settings / uninstall pages that show "PowerLock" are sent Home when "protect app settings" is on.
 */
class PowerBlockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var lastMenuScan = 0L
    private var lastGuardScan = 0L
    private var overlay: View? = null

    @Volatile
    private var active = false

    @Volatile
    private var endAt = 0L

    @Volatile
    private var guardOn = true

    private val triggers = listOf("Swipe to power off", "Swipe to restart", "Swipe for SOS")
    private val triggersLower = triggers.map { it.lowercase() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> readState() }
    private val hideTask = Runnable { hideOverlay() }
    private val backAgain = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private val goHome = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_HOME) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            L.load(this)
            readState()
            Store.lock(this).registerOnSharedPreferenceChangeListener(prefsListener)
            Store.kickService(this)
            Scheduler.onAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    private fun readState() {
        val p = Store.lock(this)
        active = p.getBoolean("active", false)
        endAt = p.getLong("end", 0)
        guardOn = p.getBoolean("s_guard", true)
    }

    private fun protecting(): Boolean = active && System.currentTimeMillis() < endAt

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !protecting()) return
        val pkg = e.packageName?.toString() ?: return
        try {
            if (pkg.contains("systemui")) {
                onSystemUi(e)
            } else if (guardOn && isGuarded(pkg)) {
                onGuarded(e)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", ex)
        }
    }

    // ---------- detection ----------

    private fun onSystemUi(e: AccessibilityEvent) {
        // 1) instant check from the event itself (no IPC)
        if (looksLikePowerMenu(e)) {
            block()
            return
        }
        // 2) content scan; content-change events are throttled
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMenuScan < 250) return
            lastMenuScan = now
        }
        if (hasPowerMenu(e.source) || anyWindowHasPowerMenu()) block()
    }

    private fun onGuarded(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastGuardScan < 250) return
            lastGuardScan = now
        }
        val root = rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("PowerLock").isNotEmpty() && now - lastAction >= 300) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun looksLikePowerMenu(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("globalaction") || cls.contains("powermenu") || cls.contains("poweroff") || cls.contains("shutdown")) {
                return true
            }
        }
        val sb = StringBuilder()
        for (t in e.text) sb.append(t).append(' ')
        val cd = e.contentDescription
        if (cd != null) sb.append(cd)
        if (sb.isEmpty()) return false
        val s = sb.toString().lowercase()
        for (t in triggersLower) {
            if (s.contains(t)) return true
        }
        return false
    }

    private fun hasPowerMenu(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        for (t in triggers) {
            if (n.findAccessibilityNodeInfosByText(t).isNotEmpty()) return true
        }
        return false
    }

    private fun anyWindowHasPowerMenu(): Boolean {
        return try {
            windows.any { hasPowerMenu(it.root) }
        } catch (ex: Exception) {
            false
        }
    }

    private fun isGuarded(p: String): Boolean =
        p.contains("settings") || p.contains("packageinstaller") ||
                p.contains("securitycenter") || p.contains("permissioncontroller")

    // ---------- blocking ----------

    private fun block() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction >= 150) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_BACK) // first, with no delay
            handler.removeCallbacks(backAgain)
            handler.removeCallbacks(goHome)
            handler.postDelayed(backAgain, 140)
            handler.postDelayed(goHome, 320) // end on the Home screen
        }
        showOverlay()
    }

    private fun buildOverlay(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(242, 8, 16, 12))
        root.isClickable = true
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv(L.t("PowerLock is active", "PowerLock সক্রিয়"), 24f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val at = fmtClock(endAt)
        val sub = tv(L.t("Power menu is blocked until $at", "$at পর্যন্ত পাওয়ার মেনু ব্লক করা আছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        return root
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1500)
        if (overlay != null) return // never more than one shield
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            val v = buildOverlay()
            wm.addView(v, params)
            overlay = v
        } catch (ex: Exception) {
            Log.e(TAG, "showOverlay failed", ex)
            overlay = null
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
        }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        try {
            Store.lock(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister failed", ex)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
