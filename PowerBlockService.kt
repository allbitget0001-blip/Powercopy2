package com.example.powerlocks

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Power menu protection engine (existing behaviour preserved):
 * while a session is active, a detected power menu is closed with Back and the user is sent Home.
 * Settings / uninstall pages that show "PowerLock" are also sent Home while protection is active.
 */
class PowerBlockService : AccessibilityService() {

    private var lastAction = 0L

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
            val p = Store.lock(this)
            if (!p.getBoolean("active", false) || System.currentTimeMillis() >= p.getLong("end", 0)) return
            val pkg = e?.packageName?.toString() ?: return

            if (pkg.contains("systemui")) {
                if (hasPowerMenu(e.source) || windows.any { hasPowerMenu(it.root) }) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastAction < 700) return // avoid duplicate actions
                    lastAction = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } else if (isGuarded(pkg)) {
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

    private fun isGuarded(p: String): Boolean =
        p.contains("settings") || p.contains("packageinstaller") ||
                p.contains("securitycenter") || p.contains("permissioncontroller")

    private fun hasPowerMenu(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        return n.findAccessibilityNodeInfosByText("Swipe to power off").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("Swipe to restart").isNotEmpty()
    }

    override fun onInterrupt() {}
}
