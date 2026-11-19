package com.example.powerlocks

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.SeekBar
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
        // not watching means not protected: master off, no enabled place, or no permission
        if (!want && Store.isLocProtecting(app)) Store.setLocProtecting(app, false)
    }

    /** One-shot check right now (used when the master switch or a place is turned on). */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!Store.settings(app).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(app)) return
        Extra.currentLocation(app) { loc ->
            if (loc != null) handle(app, loc)
        }
    }

    /** True when any enabled place is currently demanding protection. Null when no location is available. */
    fun isAnyPlaceActiveNow(ctx: Context, cb: (Boolean?) -> Unit) {
        Extra.currentLocation(ctx.applicationContext) { loc ->
            if (loc == null || (loc.hasAccuracy() && loc.accuracy > 200f)) { cb(null); return@currentLocation }
            var protect = false
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                if (pl.mode == PLACE_MODE_OUTSIDE) { if (d > pl.radius * 1.3f) protect = true }
                else if (d <= pl.radius) protect = true
            }
            cb(protect)
        }
    }

    /** True when this place is currently demanding protection. Null when no location is available. */
    fun isPlaceActiveNow(ctx: Context, pl: Place, cb: (Boolean?) -> Unit) {
        Extra.currentLocation(ctx.applicationContext) { loc ->
            if (loc == null || (loc.hasAccuracy() && loc.accuracy > 200f)) { cb(null); return@currentLocation }
            val res = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
            val d = res[0]
            cb(if (pl.mode == PLACE_MODE_OUTSIDE) d > pl.radius * 1.3f else d <= pl.radius)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(app: Context) {
        try {
            val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 20f, listener, Looper.getMainLooper())
            }
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 30f, listener, Looper.getMainLooper())
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
            // Presence-based protection: at a saved place the power menu is blocked, away it is not.
            // No Home session is involved at all — the single location switch is the only setting.
            var protect = false
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                val inside = d <= pl.radius
                val outside = d > pl.radius * 1.3f
                if (pl.mode == PLACE_MODE_OUTSIDE) {
                    // Lock while away: outside the place = blocked, back inside = unblocked.
                    if (outside) protect = true
                } else {
                    // Lock while here: inside the place = blocked, left = unblocked.
                    if (inside) protect = true
                }
            }
            if (Store.isLocProtecting(ctx) != protect) Store.setLocProtecting(ctx, protect)
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

/** Runs [block] only when this place is not locking right now; otherwise explains why. */
private fun MainActivity.ifPlaceNotLocking(pl: Place, block: () -> Unit) {
    if (!Store.isLocProtecting(this)) { block(); return }
    PlaceWatcher.isPlaceActiveNow(this, pl) { active ->
        when (active) {
            true -> toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
            false -> block()
            null -> toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
        }
    }
}

fun MainActivity.locationCard(): View {
    val c = card()
    val st = Store.settings(this)
    // header first: title on the left, master on/off on the right
    lateinit var sw: PremiumSwitch
    sw = toggle(st.getBoolean("loc_on", false)) { v ->
        if (!v && Store.isLocProtecting(this)) {
            // locked right now: only leaving the place turns it off
            PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
                if (active == true) {
                    toast(L.t("You are at a saved place — the lock turns off when you leave.", "সেভ করা জায়গায় আছেন — জায়গা ছেড়ে গেলে লক নিজে নিজে বন্ধ হবে।"))
                    sw.setCheckedImmediate(true)
                } else if (active == false) {
                    st.edit().putBoolean("loc_on", false).apply()
                    PlaceWatcher.sync(this)
                    PlaceWatcher.checkNow(this)
                } else {
                    toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
                    sw.setCheckedImmediate(true)
                }
            }
            return@toggle
        }
        st.edit().putBoolean("loc_on", v).apply()
        PlaceWatcher.sync(this)
        if (v) {
            if (!Perm.locationOn(this)) requestLocationPerm()
            PlaceWatcher.checkNow(this)
        }
    }
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(section(L.t("Location lock", "লোকেশন লক")), lp(0, WRAP, weight = 1f))
    head.addView(sw)
    c.addView(head)
    c.addView(
        tv(
            L.t(
                "Only this switch. While you are at a saved place the power menu is blocked; when you leave, it opens again. The Home lock does not need to be on.",
                "শুধু এই একটাই সুইচ। সেভ করা জায়গায় থাকলেই পাওয়ার মেনু ব্লক হবে; বাইরে গেলে আবার খুলে যাবে। হোমের লক অন করতে হবে না।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )

    val places = Extra.places(this)
    for (pl in places) {
        lateinit var rowSw: PremiumSwitch
        rowSw = toggle(pl.enabled) { v ->
            if (!v && Store.isLocProtecting(this)) {
                // this place may be locking right now: only leaving turns it off
                PlaceWatcher.isPlaceActiveNow(this, pl) { active ->
                    if (active == true) {
                        toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
                        rowSw.setCheckedImmediate(true)
                    } else if (active == false) {
                        Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = false) else it })
                        PlaceWatcher.sync(this)
                        PlaceWatcher.checkNow(this)
                    } else {
                        toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
                        rowSw.setCheckedImmediate(true)
                    }
                }
                return@toggle
            }
            Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = v) else it })
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
        }
        val modeTxt = if (pl.mode == PLACE_MODE_OUTSIDE) L.t("Lock when away", "বাইরে গেলে লক") else L.t("Lock when here", "এখানে থাকলে লক")
        val sub = L.num("${pl.radius} " + L.t("m radius", "মি. ব্যাসার্ধ")) + " · " + modeTxt
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(pl.name, 15f, C.text, 1))
        col.addView(tv(sub, 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        // visible delete button (long-press still works too)
        val del = tv("✕", 16f, C.danger, 1)
        del.gravity = Gravity.CENTER
        del.background = withRipple(rounded(C.card2, dp(18).toFloat()))
        del.pressFeedback()
        del.setOnClickListener { ifPlaceNotLocking(pl) { deletePlaceDialog(pl) { go(3) } } }
        r.addView(del, lp(dp(36), dp(36), r = dp(10)))
        r.addView(rowSw)
        r.setOnLongClickListener {
            ifPlaceNotLocking(pl) { deletePlaceDialog(pl) { go(3) } }
            true
        }
        c.addView(r)
    }

    val mapBtn = outlineBtn("🗺 " + L.t("Open map", "ম্যাপ খুলুন"), C.primary)
    mapBtn.setOnClickListener { startActivity(Intent(this, MapActivity::class.java)) }
    c.addView(mapBtn, lp(MATCH, dp(48), t = dp(12)))

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
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

private fun MainActivity.deletePlaceDialog(pl: Place, done: () -> Unit) {
    dlg()
        .setTitle(L.t("Delete this place?", "এই জায়গা মুছবেন?"))
        .setMessage(pl.name)
        .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
            Extra.savePlaces(this, Extra.places(this).filter { it.id != pl.id })
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.addPlaceFlow(done: () -> Unit) {
    if (!Perm.locationOn(this)) {
        requestLocationPerm()
        return
    }
    toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
    Extra.currentLocation(this) { loc ->
        if (loc == null) {
            toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
        } else {
            placeDialog(loc.latitude, loc.longitude, done)
        }
    }
}

private fun MainActivity.placeDialog(lat: Double, lng: Double, done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val nameEt = edit(L.t("Place name (e.g. Office)", "জায়গার নাম (যেমন অফিস)"), false)
    box.addView(nameEt)

    var radius = 200
    val radii = intArrayOf(100, 200, 500)
    val chips = ArrayList<TextView>()
    val seek = SeekBar(this)
    seek.max = 95 // (1000 - 50) / 10: 50 m to 1000 m in 10 m steps
    // header: label on the left, live value on the right
    val rHead = LinearLayout(this)
    rHead.orientation = LinearLayout.HORIZONTAL
    rHead.gravity = Gravity.CENTER_VERTICAL
    rHead.addView(tv(L.t("Radius", "ব্যাসার্ধ"), 13f, C.muted), lp(0, WRAP, weight = 1f))
    val radiusVal = tv(L.num("$radius " + L.t("m", "মি.")), 16f, C.primary, 1)
    rHead.addView(radiusVal)
    box.addView(rHead, lp(MATCH, WRAP, t = dp(14)))
    // quick chips (100 / 200 / 500)
    val rr = LinearLayout(this)
    for ((i, rv) in radii.withIndex()) {
        val ch = chip(L.num("$rv " + L.t("m", "মি.")), rv == radius)
        ch.setOnClickListener { seek.progress = (rv - 50) / 10 }
        chips.add(ch)
        rr.addView(ch, lp(0, WRAP, r = if (i < radii.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
    }
    box.addView(rr)
    // smooth slider: drag to grow or shrink the area
    seek.progressTintList = ColorStateList.valueOf(C.primary)
    seek.progressBackgroundTintList = ColorStateList.valueOf(C.card2)
    seek.thumbTintList = ColorStateList.valueOf(C.mint)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            radius = 50 + p * 10
            radiusVal.setTextAnimated(L.num("$radius " + L.t("m", "মি.")))
            for (j in chips.indices) styleChip(chips[j], radii[j] == radius)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    seek.progress = (radius - 50) / 10
    box.addView(seek, lp(MATCH, WRAP, t = dp(10)))
    val range = LinearLayout(this)
    range.orientation = LinearLayout.HORIZONTAL
    range.addView(tv(L.num("50 " + L.t("m", "মি.")), 12f, C.muted), lp(0, WRAP, weight = 1f))
    range.addView(tv(L.num("1000 " + L.t("m", "মি.")), 12f, C.muted))
    box.addView(range)

    box.addView(tv(L.t("When to lock", "কখন লক হবে"), 13f, C.muted), lp(WRAP, WRAP, t = dp(14)))
    var mode = PLACE_MODE_INSIDE
    val modeChips = ArrayList<TextView>()
    val mr = LinearLayout(this)
    val modeLabels = arrayOf(L.t("While I'm here", "এখানে থাকলে"), L.t("While I'm away", "বাইরে গেলে"))
    for ((i, ml) in modeLabels.withIndex()) {
        val ch = chip(ml, i == mode)
        ch.setOnClickListener {
            mode = i
            for (j in modeChips.indices) styleChip(modeChips[j], j == mode)
        }
        modeChips.add(ch)
        mr.addView(ch, lp(0, WRAP, r = if (i < modeLabels.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
    }
    box.addView(mr)

    val d = dlg()
        .setTitle(L.t("Save this place", "এই জায়গা সেভ করুন"))
        .setView(box)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        if (name.isEmpty()) {
            nameEt.error = L.t("Enter a name", "নাম লিখুন")
        } else {
            val id = System.currentTimeMillis()
            // no time: the lock follows presence only, so the place has no duration (min = 0)
            Extra.savePlaces(this, Extra.places(this) + Place(id, name, lat, lng, radius, 0, true, mode))
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
            d.dismiss()
            done()
        }
    }
}

// =====================================================================
// UI: blocked attempts (Statistics)
// =====================================================================

fun MainActivity.attemptsCard(): View {
    val c = card()
    c.addView(section(L.t("Blocked attempts", "আটকানো চেষ্টা")))
    val list = Extra.attempts(this)
    val now = System.currentTimeMillis()
    val today = Store.dayStart(now, 0)
    val week = Store.dayStart(now, -6)
    var nToday = 0
    var nWeek = 0
    var nPower = 0
    var nSettings = 0
    var nFocus = 0
    for ((t, k) in list) {
        if (t >= today) nToday++
        if (t >= week) nWeek++
        when (k) {
            "power" -> nPower++
            "settings" -> nSettings++
            else -> nFocus++
        }
    }
    val nums = LinearLayout(this)
    fun stat(value: Int, label: String, last: Boolean) {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.addView(tv(L.num(value.toString()), 26f, C.mint, 1))
        b.addView(tv(label, 12f, C.muted))
        nums.addView(b, lp(0, WRAP, r = if (last) 0 else dp(8), weight = 1f))
    }
    stat(nToday, L.t("Today", "আজ"), false)
    stat(nWeek, L.t("Last 7 days", "গত ৭ দিন"), false)
    stat(list.size, L.t("Total", "মোট"), true)
    c.addView(nums, lp(MATCH, WRAP, t = dp(12)))

    val chart = BarChartView(this)
    val vals = ArrayList<Long>()
    val labs = ArrayList<String>()
    for (off in -6..0) {
        val from = Store.dayStart(now, off)
        val to = Store.dayStart(now, off + 1)
        var n = 0L
        for ((t, _) in list) if (t >= from && t < to) n++
        vals.add(n)
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        labs.add(L.dayLong(dayIdx(cal)))
    }
    chart.values = vals
    chart.labels = labs
    c.addView(chart, lp(MATCH, dp(130), t = dp(14)))

    c.addView(
        tv(
            L.t(
                "Power menu: $nPower · App settings: $nSettings · Focus apps: $nFocus",
                "পাওয়ার মেনু: $nPower · অ্যাপ সেটিংস: $nSettings · ফোকাস অ্যাপ: $nFocus"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(10))
    )
    return c
}
