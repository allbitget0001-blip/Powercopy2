package com.example.powerlocks

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// MAP for the location lock (Settings > Location lock > "ম্যাপ খুলুন").
//
// WebView + Leaflet + OpenStreetMap / Esri satellite tiles (no API key, no new dependency).
// Map tiles need internet; the lock itself works offline on GPS.
// All buttons/panels are native Android views, only the map is a WebView.
// =====================================================================

class MapActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bannerTv: TextView
    private lateinit var nameTv: TextView
    private lateinit var distTv: TextView
    private lateinit var editBox: LinearLayout
    private lateinit var radiusVal: TextView
    private lateinit var seek: SeekBar
    private lateinit var addBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var delBtn: TextView
    private lateinit var hintTv: TextView
    private lateinit var satBtn: TextView
    private var satOn = false

    private var pageReady = false
    private val pendingJs = ArrayList<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var places = ArrayList<Place>()
    private var selectedId: Long? = null
    private var pendingLat: Double? = null
    private var pendingLng: Double? = null
    private var meLoc: Location? = null

    private var editRadius = 200
    private var editMode = PLACE_MODE_INSIDE
    private val radiusChips = ArrayList<TextView>()
    private val modeChips = ArrayList<TextView>()

    private val tick = object : Runnable {
        override fun run() {
            try {
                refreshTick()
            } catch (e: Exception) {
                Log.e(TAG, "map tick failed", e)
            }
            handler.postDelayed(this, 5000)
        }
    }

    // ---- JS bridge: called from the Leaflet map -----------------------

    inner class MapBridge {
        @JavascriptInterface
        fun onMapTap(lat: Double, lng: Double) {
            runOnUiThread {
                pendingLat = lat
                pendingLng = lng
                selectedId = null
                pushPendingJs()
                updatePanel()
            }
        }

        @JavascriptInterface
        fun onPlaceTap(id: Long) {
            runOnUiThread { selectPlace(id) }
        }
    }

    // ---- lifecycle ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        C.load(this)
        places = ArrayList(Extra.places(this))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.bg)
        setContentView(root)

        // header: back + title + my-location
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(12), dp(12), dp(12), dp(8))
        val back = tv("←", 22f, C.text, 1)
        back.setPadding(dp(8), dp(4), dp(12), dp(4))
        back.setOnClickListener { finish() }
        head.addView(back)
        head.addView(tv(L.t("Location map", "লোকেশন ম্যাপ"), 19f, C.text, 1), lp(0, WRAP, weight = 1f))
        val myLoc = outlineBtn("◎ " + L.t("My place", "আমার জায়গা"), C.primary)
        myLoc.setOnClickListener { recenterOnMe() }
        head.addView(myLoc)
        satBtn = outlineBtn("🛰 " + L.t("Satellite", "স্যাটেলাইট"), C.primary)
        satBtn.setOnClickListener { toggleSat() }
        head.addView(satBtn)
        root.addView(head)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(MapBridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                for (js in pendingJs) {
                    try {
                        web.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "map js failed", e)
                    }
                }
                pendingJs.clear()
                pushDataJs()
                pushMeJs()
            }
        }
        root.addView(web, lp(MATCH, 0, weight = 1f))
        web.loadDataWithBaseURL("https://unpkg.com", mapHtml(), "text/html", "utf-8", null)

        // ---- bottom panel (native Android views) ----
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(C.card)
        panel.setPadding(dp(16), dp(12), dp(16), dp(16))
        val scroll = ScrollView(this)
        scroll.addView(panel)
        root.addView(scroll, lp(MATCH, WRAP))

        bannerTv = tv("", 14f, C.muted, 1)
        panel.addView(bannerTv)
        nameTv = tv(L.t("Tap the map to add a place, or tap a marker.", "নতুন জায়গা যোগ করতে ম্যাপে চাপ দিন, বা জায়গা দেখতে মার্কারে চাপ দিন।"), 16f, C.text, 1)
        panel.addView(nameTv, lp(MATCH, WRAP, t = dp(6)))
        distTv = tv("", 13f, C.muted)
        panel.addView(distTv, lp(MATCH, WRAP, t = dp(4)))
        hintTv = tv(
            L.t("Map and satellite tiles need internet. The lock itself works offline on GPS.", "ম্যাপ ও স্যাটেলাইট দেখতে ইন্টারনেট লাগবে, লক নিজে GPS-এ অফলাইনে কাজ করে।"),
            12f, C.muted
        )
        panel.addView(hintTv, lp(MATCH, WRAP, t = dp(6)))

        // edit box: radius + mode + save/delete (only when a place is selected)
        editBox = LinearLayout(this)
        editBox.orientation = LinearLayout.VERTICAL
        editBox.visibility = View.GONE
        val rHead = LinearLayout(this)
        rHead.orientation = LinearLayout.HORIZONTAL
        rHead.gravity = Gravity.CENTER_VERTICAL
        rHead.addView(tv(L.t("Radius", "ব্যাসার্ধ"), 13f, C.muted), lp(0, WRAP, weight = 1f))
        radiusVal = tv(L.num("200 " + L.t("m", "মি.")), 16f, C.primary, 1)
        rHead.addView(radiusVal)
        editBox.addView(rHead, lp(MATCH, WRAP, t = dp(12)))
        val radiiRow1 = intArrayOf(100, 200, 500)
        val radiiRow2 = intArrayOf(1000, 5000, 10000)
        for (rowRadii in listOf(radiiRow1, radiiRow2)) {
            val rr = LinearLayout(this)
            for ((i, rv) in rowRadii.withIndex()) {
                val ch = chip(L.num("$rv " + L.t("m", "মি.")), rv == editRadius)
                ch.setOnClickListener { setEditRadius(rv) }
                radiusChips.add(ch)
                rr.addView(ch, lp(0, WRAP, r = if (i < rowRadii.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
            }
            editBox.addView(rr)
        }
        seek = SeekBar(this)
        seek.max = 995 // (10000 - 50) / 10: 50 m to 10000 m in 10 m steps
        seek.progressTintList = ColorStateList.valueOf(C.primary)
        seek.progressBackgroundTintList = ColorStateList.valueOf(C.card2)
        seek.thumbTintList = ColorStateList.valueOf(C.mint)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setEditRadius(50 + p * 10)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        editBox.addView(seek, lp(MATCH, WRAP, t = dp(10)))
        val range = LinearLayout(this)
        range.orientation = LinearLayout.HORIZONTAL
        range.addView(tv(L.num("50 " + L.t("m", "মি.")), 12f, C.muted), lp(0, WRAP, weight = 1f))
        range.addView(tv(L.num(L.t("10000 m (10 km)", "10000 মি. (10 কিমি)")), 12f, C.muted))
        editBox.addView(range)
        editBox.addView(tv(L.t("When to lock", "কখন লক হবে"), 13f, C.muted), lp(WRAP, WRAP, t = dp(10)))
        val mr = LinearLayout(this)
        val modeLabels = arrayOf(L.t("While I'm here", "এখানে থাকলে"), L.t("While I'm away", "বাইরে গেলে"))
        for ((i, ml) in modeLabels.withIndex()) {
            val ch = chip(ml, i == editMode)
            ch.setOnClickListener { setEditMode(i) }
            modeChips.add(ch)
            mr.addView(ch, lp(0, WRAP, r = if (i < modeLabels.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
        }
        editBox.addView(mr)
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        saveBtn = primaryBtn(L.t("Save", "সংরক্ষণ"))
        saveBtn.setOnClickListener { saveSelected() }
        btnRow.addView(saveBtn, lp(0, WRAP, t = dp(12), weight = 1f))
        delBtn = outlineBtn(L.t("Delete", "মুছুন"), C.danger)
        delBtn.setOnClickListener { deleteSelected() }
        btnRow.addView(delBtn, lp(0, WRAP, l = dp(10), t = dp(12), weight = 1f))
        editBox.addView(btnRow)
        panel.addView(editBox)

        // add button: only when the map was tapped (pending place)
        addBtn = primaryBtn("＋ " + L.t("Add a place here", "এখানে জায়গা যোগ করুন"))
        addBtn.visibility = View.GONE
        addBtn.setOnClickListener {
            val la = pendingLat
            val lo = pendingLng
            if (la != null && lo != null) openAddDialog(la, lo)
        }
        panel.addView(addBtn, lp(MATCH, WRAP, t = dp(12)))

        if (!Perm.locationOn(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                11
            )
        }
        refreshTick()
    }

    override fun onResume() {
        super.onResume()
        try {
            web.onResume()
        } catch (e: Exception) {
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        try {
            web.onPause()
        } catch (e: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            web.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 11) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) recenterOnMe()
            refreshTick()
        }
    }

    // ---- location + panel -------------------------------------------------

    /** Cheap last-known fix for the 5 s tick (no new listener each tick). */
    private fun lastKnown(): Location? {
        if (!Perm.locationOn(this)) return null
        return try {
            val m = getSystemService(LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = m.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshTick() {
        meLoc = lastKnown()
        updatePanel()
        pushMeJs()
        pushDataJs()
    }

    /** Switch the Leaflet base layer between OpenStreetMap and Esri satellite. */
    private fun toggleSat() {
        satOn = !satOn
        satBtn.text = if (satOn) "🗺 " + L.t("Map", "ম্যাপ") else "🛰 " + L.t("Satellite", "স্যাটেলাইট")
        eval("setSat(" + satOn + ");")
    }

    private fun recenterOnMe() {
        toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
        Extra.currentLocation(this) { loc ->
            if (loc == null) {
                toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
            } else {
                meLoc = loc
                updatePanel()
                pushMeJs()
                eval("recenter(" + loc.latitude + "," + loc.longitude + ");")
            }
        }
    }

    /** Is this place demanding protection right now? Null when location is unknown/untrusted. */
    private fun placeActive(pl: Place): Boolean? {
        val me = meLoc ?: return null
        if (me.hasAccuracy() && me.accuracy > 200f) return null
        val res = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, pl.lat, pl.lng, res)
        val d = res[0]
        return if (pl.mode == PLACE_MODE_OUTSIDE) d > pl.radius * 1.3f else d <= pl.radius
    }

    private fun distTo(pl: Place): Float? {
        val me = meLoc ?: return null
        val res = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, pl.lat, pl.lng, res)
        return res[0]
    }

    private fun updatePanel() {
        val locOn = Store.settings(this).getBoolean("loc_on", false)
        if (!locOn) {
            bannerTv.text = L.t("Location lock is OFF — turn it on in Settings.", "লোকেশন লক বন্ধ আছে — Settings থেকে চালু করুন।")
            bannerTv.setTextColor(C.warn)
        } else if (Store.isLocProtecting(this)) {
            bannerTv.text = "🔒 " + L.t("LOCK ACTIVE — power menu is blocked", "লক সক্রিয় — পাওয়ার মেনু ব্লক")
            bannerTv.setTextColor(C.primary)
        } else {
            bannerTv.text = "🔓 " + L.t("Open — power menu works", "খোলা — পাওয়ার মেনু খোলা")
            bannerTv.setTextColor(C.muted)
        }

        val sel = places.find { it.id == selectedId }
        if (sel != null) {
            editBox.visibility = View.VISIBLE
            addBtn.visibility = View.GONE
            nameTv.text = sel.name
            val d = distTo(sel)
            val active = placeActive(sel)
            distTv.text = if (d == null) {
                L.t("Location unavailable — turn on GPS.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করুন।")
            } else {
                val where = if (active == true) {
                    if (sel.mode == PLACE_MODE_OUTSIDE) L.t("you are away — locking", "বাইরে আছো — লক চলছে") else L.t("you are inside — locking", "ভেতরে আছো — লক চলছে")
                } else {
                    if (sel.mode == PLACE_MODE_OUTSIDE) L.t("you are inside — open", "ভেতরে আছো — খোলা") else L.t("you are outside — open", "বাইরে আছো — খোলা")
                }
                L.num("${d.toInt()} " + L.t("m away", "মি দূরে")) + " · " + where
            }
            return
        }
        editBox.visibility = View.GONE
        val pla = pendingLat
        val plo = pendingLng
        if (pla != null && plo != null) {
            addBtn.visibility = View.VISIBLE
            nameTv.text = L.t("New place here", "এখানে নতুন জায়গা")
            val coords = L.num(String.format(java.util.Locale.US, "%.5f, %.5f", pla, plo))
            val me = meLoc
            distTv.text = if (me == null) {
                coords + " · " + L.t("Location unavailable — turn on GPS.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করুন।")
            } else {
                val res = FloatArray(1)
                Location.distanceBetween(me.latitude, me.longitude, pla, plo, res)
                L.num("${res[0].toInt()} " + L.t("m away", "মি দূরে")) + " · " + coords
            }
        } else {
            addBtn.visibility = View.GONE
            nameTv.text = L.t("Tap the map to add a place, or tap a marker.", "নতুন জায়গা যোগ করতে ম্যাপে চাপ দিন, বা জায়গা দেখতে মার্কারে চাপ দিন।")
            distTv.text = if (places.isEmpty()) {
                L.t("No places saved yet.", "এখনো কোনো জায়গা সেভ করা হয়নি।")
            } else {
                L.num(places.size.toString()) + " " + L.t("place(s) saved", "টি জায়গা সেভ করা আছে")
            }
        }
    }

    // ---- selection + editing ----------------------------------------------

    private fun selectPlace(id: Long) {
        val pl = places.find { it.id == id } ?: return
        selectedId = id
        pendingLat = null
        pendingLng = null
        editRadius = pl.radius
        editMode = pl.mode
        syncEditControls()
        updatePanel()
        pushDataJs()
        if (editRadius > 2000) eval("fitCircle(" + pl.lat + "," + pl.lng + "," + editRadius + ");")
    }

    private fun syncEditControls() {
        radiusVal.text = L.num("$editRadius " + L.t("m", "মি."))
        seek.progress = (editRadius - 50) / 10
        val radii = intArrayOf(100, 200, 500, 1000, 5000, 10000)
        for (i in radiusChips.indices) styleChip(radiusChips[i], radii[i] == editRadius)
        for (i in modeChips.indices) styleChip(modeChips[i], i == editMode)
    }

    private fun setEditRadius(r: Int) {
        editRadius = r.coerceIn(50, 10000)
        syncEditControls()
        val id = selectedId ?: return
        val pl = places.find { it.id == id } ?: return
        eval("updateCircle(" + id + "," + editRadius + "," + (placeActive(pl) == true) + ");")
        if (editRadius > 2000) eval("fitCircle(" + pl.lat + "," + pl.lng + "," + editRadius + ");")
    }

    private fun setEditMode(m: Int) {
        editMode = m
        syncEditControls()
        updatePanel()
        pushDataJs()
    }

    private fun saveSelected() {
        val id = selectedId ?: return
        val idx = places.indexOfFirst { it.id == id }
        if (idx < 0) return
        places[idx] = places[idx].copy(radius = editRadius, mode = editMode)
        Extra.savePlaces(this, places)
        PlaceWatcher.sync(this)
        PlaceWatcher.checkNow(this)
        toast(L.t("Saved", "সংরক্ষণ হয়েছে"))
        updatePanel()
        pushDataJs()
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        val pl = places.find { it.id == id } ?: return
        dlg()
            .setTitle(L.t("Delete this place?", "এই জায়গা মুছবেন?"))
            .setMessage(pl.name)
            .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
                // a place that is locking right now cannot be deleted while you are there:
                // the watcher only clears the flag when you leave, so refuse while active
                if (Store.isLocProtecting(this) && placeActive(pl) == true) {
                    toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
                    return@setPositiveButton
                }
                places.removeAll { it.id == id }
                selectedId = null
                Extra.savePlaces(this, places)
                PlaceWatcher.sync(this)
                PlaceWatcher.checkNow(this)
                updatePanel()
                pushDataJs()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun openAddDialog(lat: Double, lng: Double) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val nameEt = edit(L.t("Place name (e.g. Office)", "জায়গার নাম (যেমন অফিস)"), false)
        box.addView(nameEt)
        box.addView(tv(L.num(String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)), 13f, C.muted), lp(MATCH, WRAP, t = dp(8)))
        val d = dlg()
            .setTitle(L.t("Save this place", "এই জায়গা সেভ করুন"))
            .setView(box)
            .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEt.text.toString().trim()
            if (name.isEmpty()) {
                nameEt.error = L.t("Enter a name", "নাম লিখুন")
            } else {
                val id = System.currentTimeMillis()
                val pl = Place(id, name, lat, lng, editRadius, 0, true, editMode)
                places.add(pl)
                Extra.savePlaces(this, places)
                PlaceWatcher.sync(this)
                PlaceWatcher.checkNow(this)
                pendingLat = null
                pendingLng = null
                d.dismiss()
                selectPlace(id)
                toast(L.t("Saved", "সংরক্ষণ হয়েছে"))
            }
        }
    }

    // ---- JS calls -----------------------------------------------------------

    private fun eval(js: String) {
        if (pageReady) {
            try {
                web.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.e(TAG, "map js failed", e)
            }
        } else {
            pendingJs.add(js)
        }
    }

    private fun pushDataJs() {
        val arr = JSONArray()
        for (pl in places) {
            val o = JSONObject()
            o.put("id", pl.id)
            o.put("name", pl.name)
            o.put("lat", pl.lat)
            o.put("lng", pl.lng)
            o.put("r", pl.radius)
            o.put("active", placeActive(pl) == true)
            arr.put(o)
        }
        val sel = selectedId ?: -1L
        eval("setData($arr,$sel);")
    }

    private fun pushMeJs() {
        val me = meLoc ?: return
        eval("setMe(" + me.latitude + "," + me.longitude + ");")
    }

    private fun pushPendingJs() {
        val la = pendingLat
        val lo = pendingLng
        if (la != null && lo != null) eval("setPending(" + la + "," + lo + ");")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- the map page (Leaflet + OpenStreetMap, no API key) -----------------

    private fun mapHtml(): String {
        val me = meLoc
        val cLat = me?.latitude ?: places.firstOrNull { it.enabled }?.lat ?: 23.8103
        val cLng = me?.longitude ?: places.firstOrNull { it.enabled }?.lng ?: 90.4125
        return """
<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;margin:0;padding:0;background:#0A1510;}</style></head>
<body><div id="map"></div><script>
var map = L.map('map').setView([__LAT__, __LNG__], 15);
var osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19, attribution: '&copy; OpenStreetMap'}).addTo(map);
var sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {maxZoom: 19, attribution: 'Esri, Maxar, Earthstar Geographics'});
L.control.layers({'ম্যাপ': osm, 'স্যাটেলাইট': sat}).addTo(map);
function setSat(on) { if (on) { map.removeLayer(osm); sat.addTo(map); } else { map.removeLayer(sat); osm.addTo(map); } }
function fitCircle(lat, lng, radius) { var c = L.circle([lat, lng], {radius: radius}); map.fitBounds(c.getBounds(), {padding: [30, 30]}); }
var layer = L.layerGroup().addTo(map);
var circles = {};
var meMarker = null, pendingMarker = null, pendingCircle = null;
function setData(json, selId) {
  layer.clearLayers();
  circles = {};
  for (var i = 0; i < json.length; i++) {
    (function(p) {
      var col = p.active ? '#22c55e' : '#3b82f6';
      var c = L.circle([p.lat, p.lng], {radius: p.r, color: col, weight: (p.id === selId ? 4 : 2), fillColor: col, fillOpacity: 0.15}).addTo(layer);
      circles[p.id] = c;
      var m = L.marker([p.lat, p.lng]).addTo(layer);
      m.bindPopup(p.name);
      m.on('click', function() { Android.onPlaceTap(p.id); });
      c.on('click', function() { Android.onPlaceTap(p.id); });
    })(json[i]);
  }
}
function setMe(lat, lng) {
  if (meMarker) { meMarker.setLatLng([lat, lng]); }
  else { meMarker = L.circleMarker([lat, lng], {radius: 8, color: '#ffffff', weight: 2, fillColor: '#2563eb', fillOpacity: 1}).addTo(map).bindPopup('আমি'); }
}
function setPending(lat, lng) {
  if (pendingMarker) { pendingMarker.setLatLng([lat, lng]); }
  else { pendingMarker = L.marker([lat, lng]).addTo(map); }
  if (pendingCircle) { pendingCircle.setLatLng([lat, lng]); }
  else { pendingCircle = L.circle([lat, lng], {radius: 200, color: '#FFB84D', weight: 2, fillColor: '#FFB84D', fillOpacity: 0.15}).addTo(map); }
}
function updateCircle(id, radius, active) {
  var c = circles[id];
  if (c) {
    var col = active ? '#22c55e' : '#3b82f6';
    c.setRadius(radius);
    c.setStyle({color: col, fillColor: col});
  }
  if (pendingCircle) { pendingCircle.setRadius(radius); }
}
function recenter(lat, lng) { map.setView([lat, lng], 16); }
map.on('click', function(e) { Android.onMapTap(e.latlng.lat, e.latlng.lng); });
</script></body></html>
""".replace("__LAT__", cLat.toString()).replace("__LNG__", cLng.toString())
    }
}
