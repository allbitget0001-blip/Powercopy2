package com.example.powerlocks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PowerBlockService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            val end = getSharedPreferences("lock", MODE_PRIVATE).getLong("end", 0)
            if (System.currentTimeMillis() > end) return
            val pkg = e?.packageName?.toString() ?: return

            if (pkg.contains("systemui")) {
                if (hasPowerMenu(e.source) || windows.any { hasPowerMenu(it.root) }) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } else if (isGuarded(pkg)) {
                val root = rootInActiveWindow
                if (root != null &&
                    root.findAccessibilityNodeInfosByText("PowerLock").isNotEmpty()
                ) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        } catch (_: Exception) {
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
