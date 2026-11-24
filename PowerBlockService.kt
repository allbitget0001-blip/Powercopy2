package com.powerlock.app

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
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * Reels-blocked apps get Back (repeated while the feed is still showing)
 * when a Reels / Shorts screen is detected, and apps with search block get
 * Back (with the typed query cleared) as soon as the user starts typing in
 * search — the keyboard still appears, and searching inside a chat or
 * channel is not blocked.
 * Home is never used for Reels: it sends YouTube Shorts into picture-in-picture
 * instead of closing it.
 * Site block also runs here: when the DNS VPN sees a query for a blocked
 * domain it broadcasts, and this service covers the screen with the block
 * screen; and when URL detection is on, the browser address bar is watched
 * directly, so a blocked site is covered even when the DNS option is off.
 */
class PowerBlockService : AccessibilityService() {

    companion object {
        /** Diagnostic log: what the service sees on reels screens. Read by the
         * Protection screen's Diagnostic card. Capped at 120 lines. */
        val debugLog = mutableListOf<String>()

        /** Plain-language summary of the same scans (2 lines per scan), shown
         * in the Diagnostic card so it reads simply. Capped at 60 lines. */
        val debugSummary = mutableListOf<String>()

    }

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

    // App block (Protection section): full block + reels block + search block.
    @Volatile
    private var abOn = true

    @Volatile
    private var abFull: Set<String> = emptySet()

    @Volatile
    private var abReels: Set<String> = emptySet()

    @Volatile
    private var abMsg: Set<String> = emptySet()

    // Daily limits: foreground time per app, counted without any lock running.
    @Volatile
    private var limitMap: Map<String, Int> = emptyMap()
    private var usagePkg: String? = null
    private var usageSince = 0L
    private var usageDay = ""
    private val usageToday = HashMap<String, Long>()
    private var imePkg: String? = null
    private var imeCheckedAt = 0L
    private var limitBlockActive = false // the overlay is up for a daily limit (no lock needed)
    private var blockMsg = "" // the user's own message on the block screen


    private var lastAppBlockAction = 0L
    private var lastReelsScan = 0L
    private var lastDebugScan = 0L
    private var lastReelsBack = 0L // cooldown after a reels Back (the close animation)
    private var lastMsgScan = 0L
    private var lastMsgAction = 0L

    // Full-block protection screen: covers a blocked app the instant it opens.
    private var blockOverlay: View? = null
    private var blockOverlayTitle: TextView? = null
    private var blockOverlaySub: TextView? = null
    private var blockOverlayMsg: TextView? = null
    private var blockOverlayIcon: IconView? = null
    private var blockOverlayAttached = false
    private var blockedPkg: String? = null
    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L
    private var lastHomeFallbackAt = 0L // throttles the HOME fallback on add failure
    private val appLabelCache = HashMap<String, String>()

    // Site block (URL detection + DNS-block overlay): the accessibility side
    // of the site block. Works during a lock even when the DNS/VPN option is
    // off — it reads the browser address bar, not the network.
    @Volatile
    private var siteUrlOn = false
    private var lastForegroundPkg: String? = null
    private var lastBrowserScanAt = 0L
    private var lastUrlCheckAt = 0L
    private var lastCheckedUrl = ""
    private var lastSiteOverlayAt = 0L
    private var lastSiteOverlayDomain = ""
    @Volatile
    private var urlCheckRunning = false
    private var siteReceiverOn = false

    /** Browsers whose address bar is watched for URL detection. */
    private val urlDetectBrowsers = setOf(
        "com.android.chrome", "org.mozilla.firefox",
        "com.sec.android.app.sbrowser", "com.microsoft.emmx"
    )

    /** Browsers (for the DNS-block overlay guard): the overlay only pops up
     * when one of these is in front, so a background prefetch cannot cover
     * whatever the user is doing. */
    private val browserPkgs = urlDetectBrowsers + setOf(
        "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser"
    )

    /** Address-bar view IDs (full "package:id/name" form). Browser updates
     * can rename these — the DNS option stays the reliable backstop, and a
     * missed ID only means no URL detection, never a crash. */
    private val addressBarIds = setOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.firefox:id/url_bar_title",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.sec.android.app.sbrowser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.microsoft.emmx:id/search_box"
    )

    /** The VPN saw a DNS query for a blocked domain: cover the screen. */
    private val siteBlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                val domain = i?.getStringExtra(SiteBlockService.EXTRA_BLOCKED_DOMAIN) ?: return
                onSiteBlocked(domain, lastForegroundPkg)
            } catch (ex: Exception) {
                Log.e(TAG, "site-blocked receiver failed", ex)
            }
        }
    }


    // Tab labels that mean a Reels / Shorts / Status feed is showing when selected.
    private val reelsTabMarkers = listOf(
        "Reels", "Shorts", "রিলস", "শর্টস", "Spotlight", "For You", "Following", "Updates"
    )
    private val reelsMarkersLower = listOf(
        "reels", "shorts", "রিলস", "শর্টস", "spotlight", "for you", "following", "updates"
    )

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
            loadUsage()
            Store.lock(this).registerOnSharedPreferenceChangeListener(prefsListener)
            Store.settings(this).registerOnSharedPreferenceChangeListener(prefsListener)
            ensureOverlay() // pre-build the shield so it can appear instantly
            ensureBlockOverlay() // pre-build the block screen too: it must appear instantly
            registerCloseDialogs()
            registerSiteBlocked()
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

    /** The VPN's blocked-site broadcast is ours only (setPackage), so it is not exported. */
    private fun registerSiteBlocked() {
        try {
            val f = IntentFilter(SiteBlockService.ACTION_SITE_BLOCKED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(siteBlockedReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(siteBlockedReceiver, f)
            }
            siteReceiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register site-blocked failed", ex)
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
        abMsg = Extra.appBlockMsg(this)
        limitMap = Extra.dailyLimits(this)
        blockMsg = Extra.blockMessage(this)
        siteUrlOn = st.getBoolean("site_url_on", false)
        if (siteUrlOn) SiteBlockService.preloadBlocklist(this)
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
            // daily limits work without a lock: track foreground time on every app switch
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) trackUsage(pkg)
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
                lastForegroundPkg = pkg
            }
            // URL detection (site block, second option): the browser address
            // bar changed, or a browser window just opened.
            if (siteUrlOn && protecting() && urlDetectBrowsers.contains(pkg)) {
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) onBrowserTextChanged(e)
                else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onBrowserWindowChanged(pkg)
            }
            // the block screen follows the foreground app: hide it once protection ends,
            // or once a real window change shows another app in front. Our own package
            // is excluded: taps on the overlay itself must not dismiss it. A daily-limit
            // block stays up without a lock — only leaving the app dismisses it.
            if (isBlockOverlayAttached() && !protecting() && !limitBlockActive) {
                hideBlockOverlay()
            } else if (isBlockOverlayAttached() && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.contains("systemui") && pkg != packageName && pkg != blockedPkg
            ) {
                hideBlockOverlay()
            }
            if (pkg.contains("systemui")) {
                // the power menu: even when the flag is not set yet, the location lock may apply
                onSystemUi(e)
            } else if (limitReached(pkg)) {
                onLimitBlockedApp(e)
            } else if (!protecting()) {
                return
            } else if (abOn && abFull.contains(pkg)) {
                onFullBlockedApp(e)
            } else if (abOn && (abReels.contains(pkg) || abMsg.contains(pkg))) {
                // reels and search blocks are independent: an app can have both
                if (abReels.contains(pkg)) onReelsApp(e)
                if (abMsg.contains(pkg)) onMsgSearchApp(e)
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
            Extra.logAttempt(this, "settings")
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
        limitBlockActive = false // a lock block, not a limit block
        showBlockOverlay(pkg) // screen first: it must cover the app before anything else runs
        Extra.logAttempt(this, "appblock")
    }

    // ---------- daily limits ----------

    /** Today's usage, loaded once at start; trackUsage keeps it fresh in memory. */
    private fun loadUsage() {
        try {
            usageDay = Store.dayKey(0)
            usageToday.clear()
            usageToday.putAll(Extra.usageTodayAll(this))
        } catch (e: Exception) {
            Log.e(TAG, "loadUsage failed", e)
        }
    }

    /** The current keyboard package (cached 60 s): typing must not count as leaving the app. */
    private fun currentImePackage(): String? {
        val now = SystemClock.uptimeMillis()
        if (now - imeCheckedAt < 60_000) return imePkg
        imeCheckedAt = now
        imePkg = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore("/")
        } catch (e: Exception) {
            null
        }
        return imePkg
    }

    /**
     * Called on every window-state change: closes the previous app's time slice
     * (persisted for every app, so the Stats screen shows full screen time)
     * and opens one for the new app. System UI, the permission screen, our own
     * package and the keyboard never switch the slice. At midnight the counters
     * reset. Daily limits still read from the same counters — only limited apps
     * are ever blocked, but every app's time is now recorded.
     */
    private fun trackUsage(pkg: String) {
        try {
            if (pkg.contains("systemui") || pkg == packageName || pkg.contains("permissioncontroller")) return
            if (pkg == currentImePackage()) return
            val now = SystemClock.uptimeMillis()
            val day = Store.dayKey(0)
            if (day != usageDay) {
                usageDay = day
                usageToday.clear()
                usageToday.putAll(Extra.usageTodayAll(this))
            }
            val prev = usagePkg
            if (prev != null && prev != pkg) {
                val secs = (now - usageSince) / 1000
                if (secs > 0) {
                    Extra.addUsage(this, prev, secs)
                    usageToday[prev] = (usageToday[prev] ?: 0L) + secs
                }
            }
            if (prev != pkg) {
                usagePkg = pkg
                usageSince = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "trackUsage failed", e)
        }
    }

    /** True when the app has a daily limit and today's time (including the live slice) is up. */
    private fun limitReached(pkg: String): Boolean {
        val lim = limitMap[pkg] ?: return false
        val used = usageToday[pkg] ?: 0L
        val live = if (pkg == usagePkg) (SystemClock.uptimeMillis() - usageSince) / 1000 else 0L
        return used + live >= lim * 60L
    }

    /** Daily-limit block: the same protection screen as a full block, until midnight. */
    private fun onLimitBlockedApp(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1000 && blockOverlayAttached) return
        lastBlockPkg = pkg
        lastBlockAt = now
        limitBlockActive = true
        showBlockOverlay(pkg, L.t("Daily limit reached — opens again tomorrow", "দৈনিক লিমিট শেষ — আগামীকাল আবার খুলবে"))
        Extra.logAttempt(this, "limit")
    }


    private fun appLabel(pkg: String): String {
        appLabelCache[pkg]?.let { return it }
        val label = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        appLabelCache[pkg] = label
        return label
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
        val icon = IconView(this, Ic.SHIELD, C.primary)
        root.addView(icon, lp(dp(84), dp(84)))
        blockOverlayIcon = icon
        val title = tv("", 22f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv(L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        blockOverlaySub = sub
        val msg = tv("", 15f, C.mint, 1)
        msg.gravity = Gravity.CENTER
        root.addView(msg, lp(WRAP, WRAP, t = dp(10)))
        blockOverlayMsg = msg
        val btn = primaryBtn(L.t("Back to Home", "হোমে ফিরুন"))
        btn.setOnClickListener {
            hideBlockOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        root.addView(btn, lp(MATCH, dp(52), t = dp(12)))
        blockOverlay = root
        blockOverlayTitle = title
        return root
    }

    /**
     * The block screen is attached when the flag says so OR the view itself
     * reports attached. The flag alone can lie: if a removal fails, the flag
     * would say "detached" while a stale view still covers the screen, and
     * re-adding that view throws — the old code then pressed HOME on every
     * event (kick-out) while the stale view stayed up (black screen).
     */
    private fun isBlockOverlayAttached(): Boolean =
        blockOverlayAttached || (blockOverlay?.isAttachedToWindow == true)

    /** Same reconciliation for the power-menu shield. */
    private fun isShieldAttached(): Boolean =
        overlayAttached || (overlay?.isAttachedToWindow == true)

    private fun showBlockOverlay(pkg: String, sub: String? = null, titleOverride: String? = null) {
        hideOverlay() // never stack the power-menu shield under the block screen
        blockedPkg = pkg
        val subText = sub ?: L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে")
        val titleText = titleOverride ?: appLabel(pkg)
        if (isBlockOverlayAttached()) {
            // already up: refresh the text instead of re-adding (re-adding an
            // attached view throws IllegalStateException)
            blockOverlayTitle?.text = titleText
            blockOverlaySub?.text = subText
            blockOverlayMsg?.text = blockMsg
            blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
            return
        }
        try {
            val v = ensureBlockOverlay()
            // attach first (label + animation after): the cover must be instant
            v.alpha = 0f
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
            blockOverlayTitle?.text = titleText
            blockOverlaySub?.text = subText
            blockOverlayMsg?.text = blockMsg
            blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
            // entrance: the screen fades in and the shield pops
            v.animate().alpha(1f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            val ic = blockOverlayIcon
            if (ic != null) {
                ic.scaleX = 0.6f
                ic.scaleY = 0.6f
                ic.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
        } catch (ex: Exception) {
            Log.e(TAG, "showBlockOverlay failed", ex)
            blockOverlayAttached = false
            // fall back to the old behaviour — leave the app at once — but at
            // most once per 3 s, or a stuck overlay failure kicks the user
            // out on every single accessibility event
            val now = SystemClock.uptimeMillis()
            if (now - lastHomeFallbackAt >= 3000) {
                lastHomeFallbackAt = now
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun hideBlockOverlay() {
        val v = blockOverlay
        if (v == null || !isBlockOverlayAttached()) {
            // nothing is really up: clear the state without touching WindowManager
            blockOverlayAttached = false
            blockedPkg = null
            limitBlockActive = false
            return
        }
        try {
            // stop the entrance animation and reset the view so the next show
            // starts from a clean state
            v.animate().cancel()
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            blockOverlayIcon?.animate()?.cancel()
            blockOverlayIcon?.let {
                it.scaleX = 1f
                it.scaleY = 1f
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
            // only now is the screen really gone: clear the state
            blockOverlayAttached = false
            blockedPkg = null
            limitBlockActive = false
        } catch (ex: Exception) {
            Log.e(TAG, "hideBlockOverlay failed", ex)
            // removal failed: the view may still be up, so keep the block
            // state — isBlockOverlayAttached() reconciles the flag with the
            // view on the next check instead of trusting a stale flag
        }
    }

    // ---------- site block: shared "block this site" path ----------

    /**
     * One shared path for both site-block triggers: a DNS query seen by the
     * VPN, and an address-bar URL seen here. Shows the block screen with the
     * domain as its title and logs the attempt (kind "site"). The overlay
     * only appears while a lock is running and a browser is in front — a
     * background prefetch must not cover whatever the user is doing.
     * Throttled: the same domain at most once per 2 s; a new domain always
     * refreshes the screen at once.
     */
    private fun onSiteBlocked(domain: String, pkg: String?) {
        try {
            if (!protecting()) return
            val fg = pkg ?: lastForegroundPkg
            if (fg == null || !browserPkgs.contains(fg)) return
            val now = SystemClock.uptimeMillis()
            if (domain == lastSiteOverlayDomain && now - lastSiteOverlayAt < 2000) return
            lastSiteOverlayAt = now
            lastSiteOverlayDomain = domain
            limitBlockActive = false // a lock block, not a limit block
            showBlockOverlay(
                fg,
                L.t("This site is blocked while the lock runs", "লক চলাকালীন এই সাইট ব্লক করা আছে"),
                titleOverride = domain
            )
            Extra.logAttempt(this, "site")
        } catch (ex: Exception) {
            Log.e(TAG, "site block overlay failed", ex)
        }
    }

    // ---------- site block: URL detection (accessibility) ----------

    /**
     * The address-bar text changed in a browser: the event carries the new
     * text, so no tree scan is needed. Only the known address-bar view IDs
     * are trusted — typing anywhere else in the browser is ignored.
     */
    private fun onBrowserTextChanged(e: AccessibilityEvent) {
        try {
            val src = e.source ?: return
            val vid = try { src.viewIdResourceName } catch (_: Exception) { null } ?: ""
            if (!addressBarIds.contains(vid)) return
            val text = e.text?.joinToString("") ?: return
            maybeCheckUrl(e.packageName?.toString() ?: return, text)
        } catch (ex: Exception) {
            Log.e(TAG, "browser text check failed", ex)
        }
    }

    /**
     * A browser window just opened (new tab, app switch, navigation): scan
     * the tree once for the address bar and check its text. Throttled to
     * once per 2 s — window changes fire often while a page loads.
     */
    private fun onBrowserWindowChanged(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBrowserScanAt < 2000) return
        lastBrowserScanAt = now
        try {
            val root = rootInActiveWindow ?: return
            try {
                for (id in addressBarIds) {
                    val nodes = try {
                        root.findAccessibilityNodeInfosByViewId(id)
                    } catch (ex: Exception) {
                        continue
                    }
                    try {
                        for (n in nodes) {
                            val text = n.text?.toString() ?: ""
                            if (text.isNotEmpty()) {
                                maybeCheckUrl(pkg, text)
                                return
                            }
                        }
                    } finally {
                        for (n in nodes) {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "browser window scan failed", ex)
        }
    }

    /**
     * The URL in a browser address bar may have changed: extract the host
     * and, if it is new, check it on a worker thread (the blocklist load may
     * block on first use). At most one check per 2 s, and only one running
     * at a time — the next URL change retries anything skipped.
     */
    private fun maybeCheckUrl(pkg: String, rawText: String) {
        val host = hostFromUrl(rawText) ?: return
        val key = "$pkg|$host"
        if (key == lastCheckedUrl) return
        val now = SystemClock.uptimeMillis()
        if (now - lastUrlCheckAt < 2000) return
        lastUrlCheckAt = now
        lastCheckedUrl = key
        if (urlCheckRunning) return
        urlCheckRunning = true
        val appCtx = applicationContext
        val t = Thread({
            try {
                // the user's own list first (fast), then the 100k blocklist
                var blocked = false
                for (d in Extra.siteDomains(appCtx)) {
                    if (host == d || host.endsWith(".$d")) { blocked = true; break }
                }
                if (!blocked) blocked = SiteBlockService.isBlockedByBuiltInList(appCtx, host)
                if (blocked) {
                    handler.post {
                        try {
                            if (protecting()) onSiteBlocked(host, pkg)
                        } catch (ex: Exception) {
                            Log.e(TAG, "site overlay post failed", ex)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "url check failed", ex)
            } finally {
                urlCheckRunning = false
            }
        }, "siteblock-url-check")
        t.isDaemon = true
        t.start()
    }

    /**
     * Pulls a host out of address-bar text ("https://www.example.com/x" or
     * just "example.com"). Returns null for search queries and anything
     * that is not a plain domain name.
     */
    private fun hostFromUrl(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.contains(' ') || s.contains('\n')) return null
        if (s.startsWith("http://")) s = s.substring(7)
        else if (s.startsWith("https://")) s = s.substring(8)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        if (s.startsWith("www.")) s = s.substring(4)
        s = s.trimEnd('.')
        if (s.isEmpty() || s.length > 253 || !s.contains('.')) return null
        for (part in s.split('.')) {
            if (part.isEmpty() || part.length > 63) return null
            if (!part.all { it.isLetterOrDigit() || it == '-' }) return null
            if (part.startsWith('-') || part.endsWith('-')) return null
        }
        return s
    }

    /**
     * Reels block: the app stays usable, only the Reels / Shorts feed is closed.
     * A detection presses Back (out of the feed/player). Home is never used:
     * pressing Home while a Short plays sends YouTube into picture-in-picture,
     * where the video keeps playing in a small floating window. After a Back
     * there is a short cooldown: the close animation keeps the reel in the
     * tree for a moment, and Backing again would leave the app instead of
     * just the reel. Only the reel is closed — never the app itself.
     */
    private fun onReelsApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        // window state changes always scan (a new screen may be the feed);
        // other events are throttled so scrolling does not scan constantly
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastReelsScan < 400) return
            lastReelsScan = now
        }
        val detected = looksLikeReels(e)
        // diagnostic: every 3s, record what the service actually sees on this
        // screen, so a missed block can be traced instead of guessed
        if (now - lastDebugScan >= 3000) {
            lastDebugScan = now
            debugScanReels(e.packageName?.toString() ?: "?", e, detected)
        }
        if (!detected) return
        if (now - lastAppBlockAction < 500) return
        if (now - lastReelsBack < 1500) return
        lastAppBlockAction = now
        lastReelsBack = now
        Extra.logAttempt(this, "reels")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Diagnostic: records what the service actually sees on a reels app's
     * screen — which signals are present (marker text, counts, action labels,
     * viewer view IDs) and how many nodes were scanned. Written to
     * [debugLog], shown in the Protection screen's Diagnostic card. The log
     * is capped so it never grows without bound.
     */
    private fun debugScanReels(pkg: String, e: AccessibilityEvent, detected: Boolean) {
        val lines = mutableListOf<String>()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add("[$time] $pkg detected=$detected event=${e.eventType} cls=${e.className}")
        val countRe = Regex("^[0-9,]+(\\.[0-9]+)?[km]?$")
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val wins = windows ?: emptyList()
            for (w in wins) {
                try { w.root?.let { roots.add(it) } } catch (_: Exception) { }
            }
            lines.add("  windows=${roots.size}")
            var total = 0
            val markers = mutableListOf<String>()
            val counts = mutableListOf<String>()
            val actions = mutableListOf<String>()
            val viewIds = mutableListOf<String>()
            for (root in roots.asReversed()) {
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                stack.add(root)
                var used = 0
                while (stack.isNotEmpty() && used < 800) {
                    val n = stack.removeFirst()
                    used++
                    total++
                    try {
                        val lt = (n.text?.toString() ?: "").lowercase()
                        val lc = (n.contentDescription?.toString() ?: "").lowercase()
                        val cls = n.className?.toString() ?: ""
                        if (reelsMarkersLower.any { lt.contains(it) || lc.contains(it) }) {
                            if (markers.size < 5) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                markers.add("marker cls=$cls t='$lt' cd='$lc' sel=${n.isSelected} chk=${n.isChecked} b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        if (countRe.matches(lt)) {
                            if (counts.size < 8) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                counts.add("count '$lt' b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        val label = lt.trim().ifEmpty { lc.trim() }
                        if (label.startsWith("like") || label.startsWith("comment") || label.startsWith("share")) {
                            if (actions.size < 8) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                actions.add("action '$label' b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        val vid = n.viewIdResourceName ?: ""
                        if (vid.isNotEmpty() && (vid.contains("viewer") || vid.contains("player")) && reelsMarkersLower.any { vid.contains(it) }) {
                            if (viewIds.size < 5) viewIds.add("viewId $vid")
                        }
                        for (i in 0 until n.childCount) {
                            try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (n !== root) {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                }
                while (stack.isNotEmpty()) {
                    val x = stack.removeFirst()
                    if (x !== root) {
                        try { x.recycle() } catch (_: Exception) { }
                    }
                }
            }
            lines.add("  nodes=$total markers=${markers.size} counts=${counts.size} actions=${actions.size} viewIds=${viewIds.size}")
            markers.forEach { lines.add("  $it") }
            counts.forEach { lines.add("  $it") }
            actions.forEach { lines.add("  $it") }
            viewIds.forEach { lines.add("  $it") }
            // plain-language summary (2 lines per scan) for the Diagnostic card
            val label = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
            val status = if (detected) L.t("Reels detected ✓", "রিলস ধরা পড়েছে ✓") else L.t("Reels not detected ✗", "রিলস ধরা পড়েনি ✗")
            debugSummary.add("[${time.take(5)}] $label — $status")
            debugSummary.add(
                "  " + L.t("Reels text", "Reels লেখা") + ": ${markers.size} • " +
                    L.t("counts", "সংখ্যা") + ": ${counts.size} • " +
                    L.t("buttons", "বোতাম") + ": ${actions.size} • " +
                    L.t("viewer", "ভিউয়ার") + ": " + if (viewIds.isNotEmpty()) L.t("yes", "আছে") else L.t("no", "নেই")
            )
            while (debugSummary.size > 60) debugSummary.removeAt(0)
        } catch (ex: Exception) {
            lines.add("  debug error: ${ex.message}")
        } finally {
            for (r in roots) {
                try { r.recycle() } catch (_: Exception) { }
            }
        }
        debugLog.addAll(lines)
        while (debugLog.size > 120) debugLog.removeAt(0)
    }

    /**
     * Search block: the app stays usable and search opens normally (the
     * keyboard appears), but as soon as the user starts typing, the query
     * is cleared and Back closes search — the user lands back in the app,
     * never Home, and the next search starts empty. Searching inside a
     * chat or channel (Telegram, WhatsApp…) is NOT blocked: it shows a
     * "1 of 5" match counter, and only the main search bar is blocked.
     */
    private fun onMsgSearchApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastMsgScan < 400) return
            lastMsgScan = now
        }
        if (!looksLikeSearch(e)) return
        // the keyboard must appear first: only act once the user types
        if (!focusedSearchFieldHasText()) return
        // in-chat / in-channel search shows a "1 of 5" match counter — allowed
        if (isInChatSearch()) return
        if (now - lastMsgAction < 500) return
        lastMsgAction = now
        clearFocusedSearchField() // erase the query so the next search starts empty
        Extra.logAttempt(this, "msgsearch")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Runs [block] with the focused search field (if any); the node is recycled after. */
    private fun withFocusedSearchField(block: (AccessibilityNodeInfo) -> Unit) {
        try {
            val root = rootInActiveWindow ?: return
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                try {
                    val isEdit = (focused.className?.toString() ?: "").contains("EditText")
                    val hint = focused.hintText?.toString() ?: ""
                    if (!isEdit || !hint.lowercase().contains("search")) return
                    block(focused)
                } finally {
                    try { focused.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "focused search field failed", ex)
        }
    }

    /** True if the focused search field already has typed text in it. */
    private fun focusedSearchFieldHasText(): Boolean {
        var hasText = false
        withFocusedSearchField { hasText = !it.text.isNullOrEmpty() }
        return hasText
    }

    /** Clears the focused search field (if any) via ACTION_SET_TEXT with "". */
    private fun clearFocusedSearchField() {
        withFocusedSearchField {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    /** True if the current search is inside a chat/channel: it shows a "1 of 5" match counter. */
    private fun isInChatSearch(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val nodes = root.findAccessibilityNodeInfosByText(" of ")
                try {
                    val re = Regex("^\\d+ of \\d+$")
                    for (n in nodes) {
                        val t = n.text?.toString()?.trim() ?: ""
                        if (re.matches(t)) return true
                    }
                    false
                } finally {
                    for (n in nodes) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Is a search screen open right now? Two precise signals only:
     * 1) a new window whose class contains "search" (a dedicated search screen),
     * 2) the focused input is a search field (its hint contains "search").
     * The chat list's own search bar is never focused, and a message input's
     * hint is "Message", so neither false-positives. No plain text check:
     * "Search" text sits on the chat list too.
     */
    private fun looksLikeSearch(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("search")) return true
        }
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                try {
                    val isEdit = (focused.className?.toString() ?: "").contains("EditText")
                    val hint = focused.hintText?.toString() ?: ""
                    isEdit && hint.lowercase().contains("search")
                } finally {
                    try { focused.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * A chat/messages screen (a "Messages" or "Chats" header at the top) is
     * never a Reels feed. This guards the Reels block against false-positives
     * inside messaging tabs (e.g. Facebook Lite's Messages tab with its notes
     * row), where a Back press would kick the user out of their chats.
     */
    private fun isChatScreen(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val screenH = resources.displayMetrics.heightPixels
            var found = false
            try {
                for (marker in listOf("Messages", "Chats")) {
                    val nodes = root.findAccessibilityNodeInfosByText(marker)
                    for (n in nodes) {
                        try {
                            val t = n.text?.toString()?.trim() ?: ""
                            if (t.equals(marker, ignoreCase = true)) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                if (r.top >= 0 && r.top < screenH * 0.20) {
                                    found = true
                                    break
                                }
                            }
                        } finally {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                    if (found) break
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Signals, cheapest first:
     * 1) the window class on a window-state change (reel / shorts / story
     *    viewer) — on a content change the class is the changed node's class,
     *    and a story cell in the feed would false-positive,
     * 2) the app's navigation: a Reels / Shorts / Status tab that is selected
     *    or checked (bottom-nav items are Checkable, so isChecked is the state
     *    that actually changes — checking only isSelected missed them),
     * 3) a bounded tree scan: icon-only tabs (label in contentDescription),
     *    the story/status reply field, the YouTube Shorts "Remix" button, and
     *    the viewer's action/count rail at the right edge (no header check:
     *    the feed's Reels carousel has the same header).
     * A plain text search would false-positive (the "Reels" tab label is in
     * the tree even on the home feed, and the feed's Reels carousel carries
     * the same text), so selection/checked state — or a viewer-only
     * fingerprint — is what counts. Chat/messages screens are excluded up
     * front: they are never a Reels feed.
     */
    private fun looksLikeReels(e: AccessibilityEvent): Boolean {
        if (isChatScreen()) return false
        val pkg = e.packageName?.toString() ?: ""
        val cls = (e.className?.toString() ?: "").lowercase()
        // a messaging screen is never a Reels feed (the event's own class —
        // a message/chat/conversation screen must not be Backed out of)
        if (cls.contains("message") || cls.contains("chat") || cls.contains("conversation")) return false
        // the window class only identifies a screen on a window-state change:
        // on a content change it is the changed node's class (a story cell
        // in the feed would false-positive and Back the user out of the app)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (cls.contains("reel") || cls.contains("shorts") || cls.contains("clips") ||
                cls.contains("spotlight") || cls.contains("story")
            ) return true
            if ((pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") && cls.contains("status")) return true
        }
        if (reelsTabSelected()) return true
        return reelsUiScan(pkg)
    }

    /** A marker tab is selected or checked (node, parent or grandparent). */
    private fun reelsTabSelected(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            for (marker in reelsTabMarkers) {
                val nodes = root.findAccessibilityNodeInfosByText(marker)
                for (n in nodes) {
                    // the label itself may not carry the state — its tab container does
                    var found = n.isSelected || n.isChecked
                    var p1: AccessibilityNodeInfo? = null
                    var p2: AccessibilityNodeInfo? = null
                    if (!found) {
                        p1 = n.parent
                        found = p1?.let { it.isSelected || it.isChecked } == true
                    }
                    if (!found && p1 != null) {
                        p2 = p1.parent
                        found = p2?.let { it.isSelected || it.isChecked } == true
                    }
                    try { p2?.recycle() } catch (_: Exception) { }
                    try { p1?.recycle() } catch (_: Exception) { }
                    try { n.recycle() } catch (_: Exception) { }
                    if (found) return true
                }
            }
            false
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Thorough tree scan (bounded: 500 nodes over all of the app's windows),
     * run only when the cheap signals miss. Catches what the text search cannot:
     * - icon-only tabs whose label is a contentDescription,
     * - the story/status viewer: its reply field's hint starts with "Reply"
     *   (Instagram / Facebook / WhatsApp / Telegram story viewers), or the
     *   "You can't reply to this story" notice at the bottom of the screen
     *   (Telegram stories with replies off have no reply field at all),
     * - the YouTube Shorts player: the "Remix" button exists only there
     *   (the bottom nav is hidden in the player, so the tab check can't see it),
     * - (no header check: the feed's Reels carousel carries the same "Reels"
     *   header at the top when scrolled, and blocking on it would Back the
     *   user out of the feed — the rail is the viewer-only fingerprint),
     * - viewer/player view IDs containing reel / short / story,
     * - the viewer's action rail: Like / Comment / Share stacked vertically
     *   on the right edge (the feed shows them in a horizontal row instead),
     * - the viewer's count rail: bare count numbers (14K, 562, 58) stacked
     *   at the right edge below the icons (their text is what FB Lite exposes).
     * All of the app's windows are scanned (topmost first, each with its own
     * budget): story viewers can live in their own window, not the active one.
     */
    private fun reelsUiScan(pkg: String): Boolean {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            try {
                for (w in windows) {
                    try {
                        val r = w.root ?: continue
                        if (r.packageName?.toString() == pkg) roots.add(r)
                        else try { r.recycle() } catch (_: Exception) { }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            if (roots.isEmpty()) {
                try {
                    rootInActiveWindow?.let { roots.add(it) }
                } catch (_: Exception) { }
            }
            // topmost window first: the viewer may sit in its own window above
            // the feed, and each window gets its own budget so a big feed tree
            // can no longer starve the viewer window
            for (r in roots.asReversed()) {
                val used = intArrayOf(0)
                if (scanReelsTree(r, pkg, screenH, screenW, 500, used)) return true
            }
            return false
        } finally {
            for (r in roots) {
                try { r.recycle() } catch (_: Exception) { }
            }
        }
    }

    /** Bounded DFS of one window's tree. Does not recycle [root]. */
    private fun scanReelsTree(
        root: AccessibilityNodeInfo, pkg: String, screenH: Int, screenW: Int, budget: Int, used: IntArray
    ): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        // right-edge action rail: kinds + y positions of Like / Comment / Share buttons
        val railKinds = HashSet<String>()
        val railTops = ArrayList<Int>()
        // viewer count rail: y positions of bare count numbers at the right edge
        val railCounts = ArrayList<Int>()
        // a bare count: 562, 58, 14K, 1.2M (lowercase text, so [km] not [KM])
        val countRe = Regex("^[0-9,]+(\\.[0-9]+)?[km]?$")
        try {
            while (stack.isNotEmpty() && used[0] < budget) {
                val n = stack.removeFirst()
                used[0]++
                try {
                    val text = n.text?.toString() ?: ""
                    val cd = n.contentDescription?.toString() ?: ""
                    val lt = text.lowercase()
                    val lc = cd.lowercase()
                    // viewer action rail: Like / Comment / Share stacked vertically
                    // on the right edge. At least two DIFFERENT kinds must be there:
                    // a feed post has only its Share button at the right edge, and
                    // two posts on screen would otherwise false-positive.
                    val label = lt.trim().ifEmpty { lc.trim() }
                    val kind = when {
                        label.startsWith("like") -> "like"
                        label.startsWith("comment") -> "comment"
                        label.startsWith("share") -> "share"
                        else -> null
                    }
                    if (kind != null) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        // centerX, not left: the icon may sit in a wider touch target
                        if (r.centerX() > screenW * 0.80 && r.top >= 0) {
                            railKinds.add(kind)
                            railTops.add(r.top)
                            val top = railTops.maxOrNull() ?: 0
                            val bottom = railTops.minOrNull() ?: 0
                            if (railKinds.size >= 2 && railTops.size >= 2 && top - bottom > screenH * 0.10) return true
                        }
                    }
                    // viewer / player view IDs (e.g. reels_viewer, story_viewer)
                    val vid = n.viewIdResourceName?.lowercase() ?: ""
                    if ((vid.contains("viewer") || vid.contains("player")) &&
                        (vid.contains("reel") || vid.contains("short") || vid.contains("story"))
                    ) return true
                    // YouTube Shorts player: Remix exists only in the Shorts player
                    if (pkg == "com.google.android.youtube" &&
                        (lt.contains("remix") || lc.contains("remix"))
                    ) return true
                    // Story / Status viewer: the reply field's hint starts with "Reply"
                    val clsN = n.className?.toString() ?: ""
                    if (clsN.contains("EditText")) {
                        val hint = n.hintText?.toString() ?: ""
                        if (hint.lowercase().startsWith("reply")) return true
                    }
                    // Viewer count rail: the Like / Comment / Share icons carry no
                    // text in FB Lite, but their counts do (14K, 562, 58) — short
                    // bare numbers stacked vertically at the right edge, just below
                    // each icon. The feed has no bare numbers at the right edge (its
                    // "10 comments" text is not a bare number), and the top-bar
                    // notification badge is excluded by the y > 15% rule.
                    if (countRe.matches(lt)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        // right edge, not left: the count TextView may be wide with
                        // right-aligned text (its left edge would be far from the rail)
                        if (r.right > screenW * 0.80 && r.top > screenH * 0.15 && r.top >= 0) {
                            railCounts.add(r.top)
                            val top = railCounts.maxOrNull() ?: 0
                            val bottom = railCounts.minOrNull() ?: 0
                            if (railCounts.size >= 2 && top - bottom > screenH * 0.10) return true
                        }
                    }
                    // Story viewer with replies off: "You can't reply to this story"
                    // sits at the bottom of the screen (Telegram)
                    if (lt.contains("this story")) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.top > screenH * 0.75) return true
                    }
                    if (reelsMarkersLower.any { lt.contains(it) || lc.contains(it) }) {
                        // marker tab, selected or checked (itself or an ancestor)
                        if (n.isSelected || n.isChecked) return true
                        var p1: AccessibilityNodeInfo? = null
                        var p2: AccessibilityNodeInfo? = null
                        var found = false
                        try {
                            p1 = n.parent
                            found = p1?.let { it.isSelected || it.isChecked } == true
                            if (!found && p1 != null) {
                                p2 = p1.parent
                                found = p2?.let { it.isSelected || it.isChecked } == true
                            }
                        } catch (_: Exception) {
                        }
                        try { p2?.recycle() } catch (_: Exception) { }
                        try { p1?.recycle() } catch (_: Exception) { }
                        if (found) return true
                        // NOTE: no header check here on purpose — the feed's Reels
                        // carousel has the same "Reels" header at the top when
                        // scrolled, and blocking on it would Back the user out of
                        // the feed. The viewer is caught by its rail instead.
                    }
                    for (i in 0 until n.childCount) {
                        try {
                            n.getChild(i)?.let { stack.add(it) }
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    if (n !== root) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            }
            return false
        } catch (ex: Exception) {
            return false
        } finally {
            while (stack.isNotEmpty()) {
                val x = stack.removeFirst()
                if (x !== root) {
                    try { x.recycle() } catch (_: Exception) { }
                }
            }
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
        Extra.logAttempt(this, "power")
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
        val title = tv(L.t("PowerLock 2 is active", "PowerLock 2 সক্রিয়"), 24f, C.text, 1)
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
        if (isShieldAttached()) return // never more than one shield
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
        val v = overlay
        if (v == null || !isShieldAttached()) {
            // nothing is really up: clear the flag without touching WindowManager
            overlayAttached = false
            return
        }
        try {
            v.animate().cancel()
            v.alpha = 1f
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
            // only now is the shield really gone
            overlayAttached = false
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
            // removal failed: the view may still be up — isShieldAttached()
            // reconciles the flag with the view on the next check
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
        if (siteReceiverOn) {
            siteReceiverOn = false
            try {
                unregisterReceiver(siteBlockedReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister site-blocked failed", ex)
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
