package com.example.powerlocks

import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.widget.TextView
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
 * App block (Protection section) also runs here while a session is active: fully
 * blocked apps are covered instantly by a protection screen (with a Home button),
 * and Reels-blocked apps get Back (then Home on repeat) when a Reels / Shorts
 * screen is detected.
 */
class PowerBlockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var lastMenuScan = 0L
    private var lastGuardScan = 0L

    @Volatile
    private var provisionalCheckRunning = false // a location check is running for the power menu

    private var overlay: View? = null
    private var overlaySub: TextView? = null
    private var overlayAttached = false

    @Volatile
    private var active = false

    @Volatile
    private var endAt = 0L

    @Volatile
    private var locProtecting = false // location lock: blocked while at a saved place, no session

    @Volatile
    private var guardOn = true

    @Volatile
    private var focusOn = false

    @Volatile
    private var focusApps: Set<String> = emptySet()

    private var lastFocusAction = 0L

    // App block (Protection section): full block + reels block.
    @Volatile
    private var abOn = true

    @Volatile
    private var abFull: Set<String> = emptySet()

    @Volatile
    private var abReels: Set<String> = emptySet()

    private var lastAppBlockAction = 0L
    private var lastReelsScan = 0L
    private var lastReelsBack = 0L

    // Full-block protection screen: covers a blocked app the instant it opens.
    private var blockOverlay: View? = null
    private var blockOverlayTitle: TextView? = null
    private var blockOverlayAttached = false
    private var blockedPkg: String? = null
    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L

    private var lastKeepAliveCheck = 0L // throttles the revive check (once per 30s)

    // English + Bengali: the phone's system language decides the menu text.
    private val triggers = listOf(
        "Swipe to power off", "Swipe to restart", "Swipe for SOS",
        "Power off", "Restart",
        "পাওয়ার বন্ধ", "পাওয়ার অফ", "রিস্টার্ট", "বন্ধ করতে সোয়াইপ"
    )
    private val triggersLower = triggers.map { it.lowercase() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> readState() }
    private val hideTask = Runnable { hideOverlay() }
    private val backAgain = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private val backAgain2 = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private var lastWinScan = 0L
    private var receiverOn = false

    // The system announces "globalactions" the instant the power button is long-pressed,
    // before the menu is even drawn - the earliest possible trigger.
    private val closeDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                if (i?.getStringExtra("reason") == "globalactions" && protecting()) block()
            } catch (ex: Exception) {
                Log.e(TAG, "close-dialogs receiver failed", ex)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            L.load(this)
            readState()
            Store.lock(this).registerOnSharedPreferenceChangeListener(prefsListener)
            Store.settings(this).registerOnSharedPreferenceChangeListener(prefsListener)
            ensureOverlay() // pre-build the shield so it can appear instantly
            registerCloseDialogs()
            Store.kickService(this)
            Scheduler.onAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerCloseDialogs() {
        try {
            val f = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(closeDialogsReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(closeDialogsReceiver, f)
            }
            receiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register close-dialogs failed", ex)
        }
    }

    private fun readState() {
        val p = Store.lock(this)
        active = p.getBoolean("active", false)
        endAt = p.getLong("end", 0)
        locProtecting = p.getBoolean("loc_protecting", false)
        // the guard follows the session while one runs (locked at its start); otherwise it
        // follows the live "App settings protection" switch in Settings
        val sessionOn = active && System.currentTimeMillis() < endAt
        guardOn = if (sessionOn) p.getBoolean("s_guard", true)
        else Store.settings(this).getBoolean("guard", true)
        val st = Store.settings(this)
        focusOn = st.getBoolean("focus_on", false)
        focusApps = (st.getString("focus_apps", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        abOn = st.getBoolean("ab_on", true)
        abFull = Extra.appBlockFull(this)
        abReels = Extra.appBlockReels(this)
    }

    private fun protecting(): Boolean = (active && System.currentTimeMillis() < endAt) || locProtecting

    /**
     * The system re-binds this service after the process dies, so it is the one
     * watchdog the system itself brings back: if the keep-alive service has not
     * written a heartbeat for 90s while protection should be on, bring it back.
     */
    private fun maybeReviveKeepAlive() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeepAliveCheck < 30_000) return
            lastKeepAliveCheck = now
            if (!protecting()) return
            val hb = Store.lock(this).getLong("kas_heartbeat", 0)
            if (System.currentTimeMillis() - hb < 90_000) return
            Store.kickService(this)
            KeepAliveService.restartSoon(this, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "revive keep-alive failed", e)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        try {
            maybeReviveKeepAlive() // the system re-binds this service, so it revives the keep-alive service
            // the block screen follows the foreground app: hide it once protection ends,
            // or once a real window change shows another app in front. Our own package
            // is excluded: taps on the overlay itself must not dismiss it.
            if (blockOverlayAttached && !protecting()) {
                hideBlockOverlay()
            } else if (blockOverlayAttached && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.contains("systemui") && pkg != packageName && pkg != blockedPkg
            ) {
                hideBlockOverlay()
            }
            if (pkg.contains("systemui")) {
                // the power menu: even when the flag is not set yet, the location lock may apply
                onSystemUi(e)
            } else if (!protecting()) {
                return
            } else if (abOn && abFull.contains(pkg)) {
                onFullBlockedApp(e)
            } else if (abOn && abReels.contains(pkg)) {
                onReelsApp(e)
            } else if (focusOn && focusApps.contains(pkg)) {
                onFocusApp(e)
            } else if (guardOn && isGuarded(pkg)) {
                onGuarded(e)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", ex)
        }
    }

    // ---------- detection ----------

    private fun onSystemUi(e: AccessibilityEvent) {
        // 1) instant check from the event itself (no IPC at all)
        val menu = looksLikePowerMenu(e) || menuFromNode(e)
        if (!menu) return
        if (protecting()) {
            block()
            return
        }
        // location lock on but the flag has not caught up yet (updates are slow): shield now,
        // verify presence, then keep blocking or release the menu
        if (!Store.settings(this).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(this)) return
        if (provisionalCheckRunning) {
            showOverlay() // keep the shield up while the check runs
            return
        }
        showOverlay()
        provisionalCheckRunning = true
        PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
            provisionalCheckRunning = false
            if (active == true) {
                Store.setLocProtecting(this, true)
                block()
            } else {
                hideOverlay()
            }
        }
    }

    /** Node / window scan for the power menu (throttled like before). */
    private fun menuFromNode(e: AccessibilityEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastMenuScan < 60) return false
            lastMenuScan = now
            if (hasPowerMenu(e.source)) return true
            if (now - lastWinScan < 150) return false
            lastWinScan = now
            return scanWindows(false)
        }
        return hasPowerMenu(e.source) || scanWindows(true)
    }

    private fun onGuarded(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastGuardScan < 120) return
            lastGuardScan = now
        }
        val root = rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("PowerLock").isNotEmpty() && now - lastAction >= 300) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun onFocusApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusAction < 300) return
        lastFocusAction = now
        Extra.logAttempt(this, "focus")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Full app block: a protection screen covers the app the instant it opens.
     * No throttle on first sight of an app — the screen must appear before the
     * user can see or touch anything. The burst of events that follows a single
     * open is ignored while the screen is already up for that app.
     */
    private fun onFullBlockedApp(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1000 && blockOverlayAttached) return
        lastBlockPkg = pkg
        lastBlockAt = now
        Extra.logAttempt(this, "appblock")
        showBlockOverlay(pkg)
    }

    private fun appLabel(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun ensureBlockOverlay(): View {
        val existing = blockOverlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(250, 8, 16, 12))
        root.isClickable = true // touches must not reach the app underneath
        root.setPadding(dp(32), dp(32), dp(32), dp(32))
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv("", 22f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv(L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        val btn = primaryBtn(L.t("Back to Home", "হোমে ফিরুন"))
        btn.setOnClickListener {
            hideBlockOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        root.addView(btn, lp(MATCH, dp(52), t = dp(28)))
        blockOverlay = root
        blockOverlayTitle = title
        return root
    }

    private fun showBlockOverlay(pkg: String) {
        hideOverlay() // never stack the power-menu shield under the block screen
        blockedPkg = pkg
        if (blockOverlayAttached) {
            blockOverlayTitle?.text = appLabel(pkg)
            return
        }
        try {
            val v = ensureBlockOverlay()
            blockOverlayTitle?.text = appLabel(pkg)
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
            wm.addView(v, params)
            blockOverlayAttached = true
        } catch (ex: Exception) {
            Log.e(TAG, "showBlockOverlay failed", ex)
            blockOverlayAttached = false
            // fall back to the old behaviour: leave the app at once
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun hideBlockOverlay() {
        if (!blockOverlayAttached) {
            blockedPkg = null
            return
        }
        blockOverlayAttached = false
        blockedPkg = null
        try {
            val v = blockOverlay ?: return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideBlockOverlay failed", ex)
        }
    }

    /**
     * Reels block: the app stays usable, only the Reels / Shorts feed is closed.
     * First detection presses Back (out of the feed); if a Reels screen is still
     * showing shortly after, Home is pressed instead.
     * Detection uses the event itself (window class + text), not a full node
     * scan: the "Reels" tab label sits in the node tree even on the home feed,
     * so a tree search would close the app while just browsing.
     */
    private fun onReelsApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastReelsScan < 800) return
            lastReelsScan = now
        }
        if (!looksLikeReels(e)) return
        if (now - lastAppBlockAction < 500) return
        lastAppBlockAction = now
        Extra.logAttempt(this, "reels")
        if (now - lastReelsBack < 2500) {
            lastReelsBack = 0L
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            lastReelsBack = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun looksLikeReels(e: AccessibilityEvent): Boolean {
        val cls = (e.className?.toString() ?: "").lowercase()
        if (cls.contains("reel") || cls.contains("shorts") || cls.contains("clips")) return true
        val sb = StringBuilder()
        for (t in e.text) sb.append(t).append(' ')
        if (e.contentDescription != null) sb.append(e.contentDescription)
        val s = sb.toString().lowercase()
        return s.contains("reels") || s.contains("shorts") ||
                s.contains("রিলস") || s.contains("শর্টস")
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

    /** The power menu always has the "power off" slider (English or Bengali). */
    private fun hasPowerMenu(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        return n.findAccessibilityNodeInfosByText("to power off").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("পাওয়ার বন্ধ").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("রিস্টার্ট").isNotEmpty()
    }

    /** System windows first (where the power menu lives); other windows only when [all] is true. */
    private fun scanWindows(all: Boolean): Boolean {
        return try {
            val list = windows
            var found = false
            for (w in list) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && hasPowerMenu(w.root)) {
                    found = true
                    break
                }
            }
            if (!found && all) {
                for (w in list) {
                    if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM && hasPowerMenu(w.root)) {
                        found = true
                        break
                    }
                }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    private fun isGuarded(p: String): Boolean =
        p.contains("settings") || p.contains("packageinstaller") ||
                p.contains("securitycenter") || p.contains("permissioncontroller")

    // ---------- blocking ----------

    private fun block() {
        showOverlay() // shield first: from this moment the slider cannot be touched
        val now = SystemClock.uptimeMillis()
        if (now - lastAction >= 100) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.removeCallbacks(backAgain)
            handler.removeCallbacks(backAgain2)
            handler.postDelayed(backAgain, 60)
            handler.postDelayed(backAgain2, 150)
            // no HOME: the app the user is in must stay open
        }
    }

    private fun ensureOverlay(): View {
        val existing = overlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(245, 8, 16, 12))
        root.isClickable = true
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv(L.t("PowerLock is active", "PowerLock সক্রিয়"), 24f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv("", 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        overlay = root
        overlaySub = sub
        return root
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1400)
        if (overlayAttached) return // never more than one shield
        try {
            val v = ensureOverlay()
            val sessionOn = active && System.currentTimeMillis() < endAt
            overlaySub?.text = if (locProtecting && !sessionOn) {
                L.t("Location lock is active", "লোকেশন লক সক্রিয়")
            } else {
                val at = fmtClock(endAt)
                L.t("Power menu is blocked until $at", "$at পর্যন্ত পাওয়ার মেনু ব্লক করা আছে")
            }
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
            wm.addView(v, params)
            overlayAttached = true
        } catch (ex: Exception) {
            Log.e(TAG, "showOverlay failed", ex)
            overlayAttached = false
        }
    }

    private fun hideOverlay() {
        if (!overlayAttached) return
        overlayAttached = false
        try {
            val v = overlay ?: return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
        }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        hideBlockOverlay()
        if (receiverOn) {
            receiverOn = false
            try {
                unregisterReceiver(closeDialogsReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister close-dialogs failed", ex)
            }
        }
        try {
            Store.lock(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister failed", ex)
        }
        try {
            Store.settings(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister settings failed", ex)
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
