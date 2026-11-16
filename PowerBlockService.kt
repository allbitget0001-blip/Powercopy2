package com.example.powerlocks

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Power menu protection engine.
 * While a session is active:
 *  - the power menu is detected from the window event (class name / text) and from the window content,
 *    so it also works on the lock screen,
 *  - a full-screen "PowerLock active" shield is shown for a moment (blocks touches on the slider),
 *  - Back is pressed (repeated), then Home.
 * Settings / uninstall pages that show "PowerLock" are sent Home when "protect app settings" is on.
 */
class PowerBlockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var overlay: View? = null
    private val hideTask = Runnable { hideOverlay() }
    private val triggers = listOf("Swipe to power off", "Swipe to restart", "Swipe for SOS", "Slide to power off")

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            L.load(this)
            Store.kickService(this)
            Scheduler.onAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            if (e == null) return
            val pkg = e.packageName?.toString() ?: return
            val isSysUi = pkg.contains("systemui")
            if (isSysUi && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) logEvent(e, pkg)

            val p = Store.lock(this)
            if (!p.getBoolean("active", false) || System.currentTimeMillis() >= p.getLong("end", 0)) return

            if (isSysUi) {
                if (looksLikePowerMenu(e) || hasPowerMenu(e.source) || anyWindowHasPowerMenu()) {
                    block()
                }
            } else if (p.getBoolean("s_guard", true) && isGuarded(pkg)) {
                val root = rootInActiveWindow
                if (root != null && root.findAccessibilityNodeInfosByText("PowerLock").isNotEmpty()) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastAction < 700) return
                    lastAction = now
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", ex)
        }
    }

    // ---------- detection ----------

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
        val s = sb.toString().lowercase()
        for (t in triggers) {
            if (s.contains(t.lowercase())) return true
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

    private fun isProtecting(): Boolean {
        val p = Store.lock(this)
        return p.getBoolean("active", false) && System.currentTimeMillis() < p.getLong("end", 0)
    }

    // ---------- blocking ----------

    private fun block() {
        showOverlay()
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < 500) return // avoid duplicate actions while the shield stays up
        lastAction = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ if (isProtecting()) performGlobalAction(GLOBAL_ACTION_BACK) }, 250)
        handler.postDelayed({ if (isProtecting()) performGlobalAction(GLOBAL_ACTION_HOME) }, 600)
        Diag.add(this, "BLOCK", "", "", isLocked())
    }

    private fun buildOverlay(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(242, 10, 18, 14))
        root.isClickable = true
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv(L.t("PowerLock is active", "PowerLock সক্রিয়"), 24f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val end = Store.lock(this).getLong("end", 0)
        val at = fmtClock(end)
        val sub = tv(L.t("Power menu is blocked until $at", "$at পর্যন্ত পাওয়ার মেনু ব্লক করা আছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        return root
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1800)
        if (overlay != null) return // no duplicate overlays
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
            wm.removeView(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
        }
    }

    // ---------- diagnostics ----------

    private fun isLocked(): Boolean =
        try {
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (ex: Exception) {
            false
        }

    private fun logEvent(e: AccessibilityEvent, pkg: String) {
        try {
            val cls = (e.className?.toString() ?: "-").substringAfterLast('.')
            val sb = StringBuilder()
            for (t in e.text) sb.append(t).append(' ')
            var txt = sb.toString().trim()
            if (txt.length > 60) txt = txt.substring(0, 60)
            Diag.add(this, "WINDOW", cls, txt, isLocked())
        } catch (ex: Exception) {
            Log.e(TAG, "logEvent failed", ex)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        return super.onUnbind(intent)
    }
}
