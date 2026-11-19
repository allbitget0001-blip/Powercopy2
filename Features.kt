package com.example.powerlocks

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// =====================================================================
// DATA
// =====================================================================

data class Preset(val id: Long, val name: String, val min: Int, val strict: Boolean)

const val PLACE_MODE_INSIDE = 0 // lock while inside the place
const val PLACE_MODE_OUTSIDE = 1 // lock while outside the place

data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Int,
    val min: Int,
    val enabled: Boolean,
    val mode: Int = PLACE_MODE_INSIDE
)

object Extra {

    // ---------- presets ----------

    fun presets(ctx: Context): List<Preset> {
        val p = Store.settings(ctx)
        if (!p.contains("presets")) {
            val d = listOf(
                Preset(1, L.t("Night", "রাত"), 480, true),
                Preset(2, L.t("Study", "পড়াশোনা"), 120, false),
                Preset(3, L.t("Travel", "ভ্রমণ"), 360, false)
            )
            savePresets(ctx, d)
            return d
        }
        val out = ArrayList<Preset>()
        try {
            val arr = JSONArray(p.getString("presets", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Preset(o.getLong("id"), o.getString("n"), o.getInt("m"), o.getBoolean("s")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "presets read failed", e)
        }
        return out
    }

    fun savePresets(ctx: Context, list: List<Preset>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("n", x.name)
            o.put("m", x.min)
            o.put("s", x.strict)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString("presets", arr.toString()).apply()
    }

    // ---------- extend time (never reduce) ----------

    /** 0 = extended, 1 = no running session, 2 = would pass the 15 day limit. */
    fun extend(ctx: Context, addMs: Long): Int {
        if (addMs <= 0L) return 1
        if (!Store.isActive(ctx)) return 1
        val p = Store.lock(ctx)
        val start = p.getLong("start", 0)
        val end = p.getLong("end", 0)
        val newEnd = end + addMs
        if (newEnd - start > MAX_MIN * 60_000L) return 2
        p.edit().putLong("end", newEnd).putLong("total", newEnd - start).apply()
        Store.kickService(ctx)
        return 0
    }

    // ---------- blocked attempts ----------

    private val lastLog = HashMap<String, Long>()

    /** kind: "power", "settings" or "focus". At most one entry per kind every 3 seconds. */
    @Synchronized
    fun logAttempt(ctx: Context, kind: String) {
        try {
            val now = System.currentTimeMillis()
            val last = lastLog[kind] ?: 0L
            if (now - last < 3000L) return
            lastLog[kind] = now
            val p = Store.lock(ctx)
            val old = try {
                JSONArray(p.getString("attempts", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            val out = JSONArray()
            val from = if (old.length() >= 1000) old.length() - 999 else 0
            for (i in from until old.length()) out.put(old.get(i))
            val o = JSONObject()
            o.put("t", now)
            o.put("k", kind)
            out.put(o)
            p.edit().putString("attempts", out.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "logAttempt failed", e)
        }
    }

    fun attempts(ctx: Context): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        try {
            val arr = JSONArray(Store.lock(ctx).getString("attempts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getLong("t"), o.getString("k")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "attempts read failed", e)
        }
        return out
    }

    // ---------- focus mode ----------

    fun focusApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("focus_apps", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setFocusApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("focus_apps", apps.joinToString(",")).apply()
    }

    // ---------- places ----------

    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Place(
                        o.getLong("id"), o.getString("n"), o.getDouble("la"), o.getDouble("lo"),
                        o.getInt("r"), o.getInt("m"), o.getBoolean("on"),
                        o.optInt("md", PLACE_MODE_INSIDE)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "places read failed", e)
        }
        return out
    }

    fun savePlaces(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("n", x.name)
            o.put("la", x.lat)
            o.put("lo", x.lng)
            o.put("r", x.radius)
            o.put("m", x.min)
            o.put("on", x.enabled)
            o.put("md", x.mode)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString("places", arr.toString()).apply()
    }

    /** Best recent location, or a fresh single fix (up to 20 s). Calls back on the main thread. */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context, cb: (Location?) -> Unit) {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = m.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            }
        } catch (e: Exception) {
            Log.e(TAG, "last location failed", e)
        }
        val last: Location? = best
        if (last != null && System.currentTimeMillis() - last.time < 120_000L) {
            cb(last)
            return
        }
        var done = false
        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                try {
                    m.removeUpdates(this)
                } catch (e: Exception) {
                    Log.e(TAG, "remove updates failed", e)
                }
                cb(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }
        }
        var asked = false
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(p)) {
                    m.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    asked = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "request location failed", e)
        }
        if (!asked) {
            cb(last)
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                try {
                    m.removeUpdates(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "remove updates failed", e)
                }
                cb(last)
            }
        }, 20_000L)
    }
}

// =====================================================================
// LOCATION LOCK ENGINE (runs inside the always-on foreground service)
// =====================================================================

object PlaceWatcher {
    private var appCtx: Context? = null
    private var lm: LocationManager? = null
    private var on = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val c = appCtx ?: return
            handle(c, location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    /** Starts or stops location updates to match the settings and permissions. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val want = Store.settings(app).getBoolean("loc_on", false) &&
                Extra.places(app).any { it.enabled } &&
                Perm.locationOn(app)
        if (want && !on) start(app) else if (!want && on) stop()
    }

    @SuppressLint("MissingPermission")
    private fun start(app: Context) {
        try {
            val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 30f, listener, Looper.getMainLooper())
            }
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 120_000L, 40f, listener, Looper.getMainLooper())
            }
            on = true
        } catch (e: Exception) {
            Log.e(TAG, "place watcher start failed", e)
            on = false
        }
    }

    fun stop() {
        try {
            lm?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.e(TAG, "place watcher stop failed", e)
        }
        on = false
    }

    private fun handle(ctx: Context, loc: Location) {
        try {
            if (loc.hasAccuracy() && loc.accuracy > 200f) return // too vague to trust
            val prefs = Store.settings(ctx)
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                val key = "inside_" + pl.id
                val was = prefs.getBoolean(key, false)
                val inside = d <= pl.radius
                val outside = d > pl.radius * 1.3f
                if (pl.mode == PLACE_MODE_OUTSIDE) {
                    // Lock while away: leaving starts the lock, coming back stops it.
                    if (outside) {
                        prefs.edit().putBoolean(key, false).apply()
                        if (!Store.isActive(ctx)) Store.startSession(ctx, pl.min * 60_000L, "location")
                    } else if (inside) {
                        prefs.edit().putBoolean(key, true).apply()
                        if (Store.isLocationSession(ctx)) Store.stopSession(ctx, force = true)
                    }
                } else {
                    // Lock while here: arriving starts the lock, leaving stops it.
                    if (inside && !was) {
                        prefs.edit().putBoolean(key, true).apply()
                        if (!Store.isActive(ctx)) Store.startSession(ctx, pl.min * 60_000L, "location")
                    } else if (outside && was) {
                        prefs.edit().putBoolean(key, false).apply()
                        if (Store.isLocationSession(ctx)) Store.stopSession(ctx, force = true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "place check failed", e)
        }
    }
}

// =====================================================================
// UI: extend time
// =====================================================================

private fun MainActivity.addLabel(min: Int): String = "+ " + fmtDur(min * 60_000L)

fun MainActivity.extendFlow(done: () -> Unit) {
    if (!Store.isActive(this)) return
    val mins = intArrayOf(10, 30, 60, 180, 1440, -1)
    val labels = Array(mins.size) { i ->
        if (mins[i] > 0) addLabel(mins[i]) else L.t("Custom…", "কাস্টম…")
    }
    dlg()
        .setTitle(L.t("Add time", "সময় বাড়ান"))
        .setItems(labels) { _, which ->
            if (mins[which] > 0) confirmExtend(mins[which], done) else customExtend(done)
        }
        .show()
}

private fun MainActivity.confirmExtend(min: Int, done: () -> Unit) {
    val newEnd = Store.lock(this).getLong("end", 0) + min * 60_000L
    val at = fmtClock(newEnd)
    dlg()
        .setTitle(addLabel(min))
        .setMessage(
            L.t(
                "The lock will end at $at. Time can only be added, never reduced.",
                "লক চলবে $at পর্যন্ত। সময় শুধু বাড়ানো যায়, কমানো যায় না।"
            )
        )
        .setPositiveButton(L.t("Add", "বাড়ান")) { _, _ ->
            when (Extra.extend(this, min * 60_000L)) {
                0 -> toast(L.t("Time added", "সময় বাড়ানো হয়েছে"))
                2 -> toast(L.t("Maximum 15 days", "সর্বোচ্চ ১৫ দিন"))
                else -> toast(L.t("No running session", "কোনো সেশন চলছে না"))
            }
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.customExtend(done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val r = LinearLayout(this)
    val dEt = edit(L.t("Days", "দিন"), true)
    val hEt = edit(L.t("Hours", "ঘণ্টা"), true)
    val mEt = edit(L.t("Minutes", "মিনিট"), true)
    r.addView(dEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(hEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(mEt, lp(0, WRAP, weight = 1f))
    box.addView(r)
    val d = dlg()
        .setTitle(L.t("Add custom time", "নিজের সময় যোগ"))
        .setView(box)
        .setPositiveButton(L.t("Next", "পরবর্তী"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                (mEt.text.toString().toLongOrNull() ?: 0L)
        if (total < 1L || total > MAX_MIN.toLong()) {
            mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
        } else {
            d.dismiss()
            confirmExtend(total.toInt(), done)
        }
    }
}

// =====================================================================
// UI: presets
// =====================================================================

fun MainActivity.presetsCard(onApplied: () -> Unit): View {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.addView(heading(L.t("Presets", "প্রিসেট")), lp(MATCH, WRAP, t = dp(26), b = dp(2)))
    val scroll = HorizontalScrollView(this)
    scroll.isHorizontalScrollBarEnabled = false
    val row = LinearLayout(this)
    scroll.addView(row)
    val strictNow = Store.settings(this).getBoolean("strict", false)
    for (p in Extra.presets(this)) {
        val sel = p.min == selectedMin && p.strict == strictNow
        var label = p.name + " · " + fmtDur(p.min * 60_000L)
        if (p.strict) label += " · " + L.t("Strict", "স্ট্রিক্ট")
        val c = chip(label, sel)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.setOnClickListener { applyPreset(p, onApplied) }
        c.setOnLongClickListener {
            presetDialog(p, onApplied)
            true
        }
        row.addView(c, lp(WRAP, dp(52), r = dp(10), t = dp(10)))
    }
    val add = chip("＋ " + L.t("New", "নতুন"), false)
    add.setPadding(dp(16), dp(14), dp(16), dp(14))
    add.setOnClickListener { presetDialog(null, onApplied) }
    row.addView(add, lp(WRAP, dp(52), t = dp(10)))
    box.addView(scroll)
    box.addView(
        tv(L.t("Tap to apply · long-press to edit", "ট্যাপে প্রয়োগ · চেপে ধরলে সম্পাদনা"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(8))
    )
    return box
}

private fun MainActivity.applyPreset(p: Preset, done: () -> Unit) {
    if (Store.isActive(this)) {
        toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        return
    }
    selectedMin = p.min
    Store.settings(this).edit().putBoolean("strict", p.strict).apply()
    toast(L.t("Preset applied: ", "প্রিসেট প্রয়োগ: ") + p.name)
    done()
}

private fun MainActivity.presetDialog(existing: Preset?, done: () -> Unit) {
    if (Store.isActive(this)) {
        toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        return
    }
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val nameEt = edit(L.t("Name", "নাম"), false)
    nameEt.setText(existing?.name ?: "")
    box.addView(nameEt)
    val m0 = existing?.min ?: 60
    val r = LinearLayout(this)
    val dEt = edit(L.t("Days", "দিন"), true)
    val hEt = edit(L.t("Hours", "ঘণ্টা"), true)
    val mEt = edit(L.t("Minutes", "মিনিট"), true)
    dEt.setText((m0 / 1440).toString())
    hEt.setText(((m0 % 1440) / 60).toString())
    mEt.setText((m0 % 60).toString())
    r.addView(dEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(hEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(mEt, lp(0, WRAP, weight = 1f))
    box.addView(r, lp(MATCH, WRAP, t = dp(10)))
    var strict = existing?.strict ?: false
    val sw = toggle(strict) { v -> strict = v }
    box.addView(
        row(L.t("Strict mode", "স্ট্রিক্ট মোড"), L.t("No early stop", "আগে বন্ধ করা যাবে না"), sw),
        lp(MATCH, WRAP, t = dp(8))
    )
    val b = dlg()
        .setTitle(if (existing == null) L.t("New preset", "নতুন প্রিসেট") else L.t("Edit preset", "প্রিসেট সম্পাদনা"))
        .setView(box)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
    if (existing != null) b.setNeutralButton(L.t("Delete", "মুছুন"), null)
    val d = b.create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                (mEt.text.toString().toLongOrNull() ?: 0L)
        if (name.isEmpty()) {
            nameEt.error = L.t("Enter a name", "নাম লিখুন")
        } else if (total < 1L || total > MAX_MIN.toLong()) {
            mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
        } else {
            val all = Extra.presets(this)
            val updated: List<Preset> = if (existing == null) {
                all + Preset(System.currentTimeMillis(), name, total.toInt(), strict)
            } else {
                all.map { if (it.id == existing.id) Preset(it.id, name, total.toInt(), strict) else it }
            }
            Extra.savePresets(this, updated)
            d.dismiss()
            done()
        }
    }
    if (existing != null) {
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            Extra.savePresets(this, Extra.presets(this).filter { it.id != existing.id })
            d.dismiss()
            done()
        }
    }
}

// =====================================================================
// UI: focus mode (Settings)
// =====================================================================

fun MainActivity.focusCard(): View {
    val c = card()
    c.addView(section(L.t("Focus mode", "ফোকাস মোড")))
    val st = Store.settings(this)
    val locked = Store.isActive(this)
    val sw = toggle(st.getBoolean("focus_on", false)) { v -> st.edit().putBoolean("focus_on", v).apply() }
    if (locked) sw.isEnabled = false
    c.addView(
        row(
            L.t("Block selected apps", "নির্বাচিত অ্যাপ ব্লক"),
            L.t("While the lock runs, these apps jump straight back to Home.", "লক চলাকালীন এই অ্যাপ খুললেই সঙ্গে সঙ্গে হোমে ফিরে যাবে।"),
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val count = Extra.focusApps(this).size
    val pick = outlineBtn(L.t("Choose apps ($count selected)", "অ্যাপ বাছুন ($count টি নির্বাচিত)"), C.primary)
    pick.setOnClickListener {
        if (Store.isActive(this)) {
            toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        } else {
            appPicker { go(3) }
        }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    c.addView(
        tv(
            if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
            else L.t("Phone, dialer and emergency apps are never listed. Changes apply from the next session.", "ফোন, ডায়ালার ও জরুরি অ্যাপ তালিকায় আসে না। পরিবর্তন পরের সেশন থেকে কার্যকর।"),
            12f, if (locked) C.warn else C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

private fun MainActivity.appPicker(done: () -> Unit) {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    val skip = listOf("dialer", "incallui", "emergency", "telecom", "contacts")
    val apps = ArrayList<Triple<String, String, Drawable>>()
    val seen = HashSet<String>()
    try {
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            if (skip.any { pkg.contains(it) }) continue
            apps.add(Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
    } catch (e: Exception) {
        Log.e(TAG, "app list failed", e)
    }
    apps.sortBy { it.second.lowercase() }
    if (apps.isEmpty()) {
        toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
        return
    }
    val chosen = HashSet<String>(Extra.focusApps(this))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    list.setPadding(dp(20), dp(4), dp(20), dp(4))
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val pkg = info.first
        r.addView(toggle(chosen.contains(pkg)) { v ->
            if (v) chosen.add(pkg) else chosen.remove(pkg)
        })
        list.addView(r)
    }
    val sv = ScrollView(this)
    sv.addView(list)
    dlg()
        .setTitle(L.t("Choose apps to block", "ব্লক করার অ্যাপ বাছুন"))
        .setView(sv)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
            Extra.setFocusApps(this, chosen)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: location lock (Settings)
// =====================================================================

private fun MainActivity.requestLocationPerm() {
    if (!Perm.locationOn(this)) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
    } else if (Build.VERSION.SDK_INT >= 29 && !Perm.backgroundLocationOn(this)) {
        requestPermissions(arrayOf("android.permission.ACCESS_BACKGROUND_LOCATION"), 12)
    }
}

fun MainActivity.locationCard(): View {
    val c = card()
    c.addView(section(L.t("Location lock", "লোকেশন লক")))
    val st = Store.settings(this)
    val sw = toggle(st.getBoolean("loc_on", false)) { v ->
        st.edit().putBoolean("loc_on", v).apply()
        PlaceWatcher.sync(this)
        if (v && !Perm.locationOn(this)) requestLocationPerm()
    }
    c.addView(
        row(
            L.t("Start the lock at saved places", "সেভ করা জায়গায় পৌঁছালে লক চালু"),
            L.t("Works without internet. The lock follows the place: it ends when you leave, or come back.", "নেট ছাড়াই কাজ করে। লক জায়গা অনুযায়ী চলে: ছেড়ে গেলে বা ফিরে এলে খুলে যায়।"),
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )

    val places = Extra.places(this)
    for (pl in places) {
        val rowSw = toggle(pl.enabled) { v ->
            Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = v) else it })
            PlaceWatcher.sync(this)
        }
        val r = row(
            pl.name,
            L.num("${pl.radius} " + L.t("m radius", "মি. ব্যাসার্ধ")) + " · " +
                    if (pl.mode == PLACE_MODE_OUTSIDE) L.t("Lock when away", "বাইরে গেলে লক") else L.t("Lock when here", "এখানে থাকলে লক"),
            rowSw
        )
        r.setOnLongClickListener {
            dlg()
                .setTitle(L.t("Delete this place?", "এই জায়গা মুছবেন?"))
                .setMessage(pl.name)
                .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
                    Extra.savePlaces(this, Extra.places(this).filter { it.id != pl.id })
                    PlaceWatcher.sync(this)
                    go(3)
                }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
            true
        }
        c.addView(r)
    }
    if (places.isNotEmpty()) {
        c.addView(tv(L.t("Long-press a place to delete it.", "জায়গা মুছতে চেপে ধরুন।"), 12f, C.muted), lp(MATCH, WRAP, t = dp(2)))
    }

    val add = outlineBtn("＋ " + L.t("Save my current place", "আমার এই জায়গা সেভ করুন"), C.primary)
    add.setOnClickListener { addPlaceFlow { go(3) } }
    c.addView(add, lp(MATCH, dp(48), t = dp(12)))

    val locOk = Perm.locationOn(this)
    val bgOk = Perm.backgroundLocationOn(this)
    c.addView(
        row(
            L.t("Location permission", "লোকেশন পারমিশন"),
            if (locOk) L.t("Granted", "অনুমোদিত") else L.t("Tap to allow", "অনুমতি দিতে চাপুন"),
            tv(if (locOk) "✔" else "✘", 18f, if (locOk) C.primary else C.danger, 1)
        ) { if (!locOk) requestLocationPerm() },
        lp(MATCH, WRAP, t = dp(6))
    )
    c.addView(
        row(
            L.t("Allow all the time", "সবসময় অনুমতি"),
            if (bgOk) L.t("Granted", "অনুমোদিত") else L.t("Needed so it works with the screen off", "স্ক্রিন বন্ধ থাকলেও কাজ করার জন্য দরকার"),
            tv(if (bgOk) "✔" else "✘", 18f, if (bgOk) C.primary else C.danger, 1)
        ) { if (!bgOk && locOk) requestLocationPerm() }
    )
    c.addView(
        tv(
            L.t(
                "Uses a little extra battery. Indoors the position can be off by 10-50 m, so use a radius of 100 m or more.",
                "ব্যাটারি একটু বেশি খরচ হয়। ঘরের ভেতরে অবস্থান ১০-৫০ মিটার ভুল হতে পারে, তাই ব্যাসার্ধ ১০০ মিটার বা বেশি রাখুন।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t =
