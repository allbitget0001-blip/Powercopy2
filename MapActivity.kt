package com.powerlock.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.geojson.FeatureCollection
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

// =====================================================================
// MAP for the location lock (Settings > Location lock > "ম্যাপ খুলুন").
//
// Native MapLibre Android SDK — no WebView, no remote code. The map style
// is built in code (companion object below); only raster tile IMAGES are
// fetched, from OpenStreetMap (map) or Esri (satellite), with attribution
// shown by the SDK's attribution control. No lock/place/user data is sent
// anywhere. Tiles are cached on disk by the SDK, so repeat views of the
// same area don't re-fetch. Map tiles need internet; the lock itself works
// offline on GPS.
// =====================================================================

class MapActivity : Activity() {

    companion object {
        // Styles built in code — never fetched from the internet.
        private const val OSM_STYLE_JSON = "{\"version\":8,\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],\"tileSize\":256,\"attribution\":\"© OpenStreetMap contributors\"}},\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\",\"minzoom\":0,\"maxzoom\":19}]}"
        private const val SAT_STYLE_JSON = "{\"version\":8,\"sources\":{\"esri\":{\"type\":\"raster\",\"tiles\":[\"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}\"],\"tileSize\":256,\"attribution\":\"Esri, Maxar, Earthstar Geographics\"}},\"layers\":[{\"id\":\"esri\",\"type\":\"raster\",\"source\":\"esri\",\"minzoom\":0,\"maxzoom\":19}]}"
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleReady = false
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
    private lateinit var routeDayTv: TextView
    private var satOn = false
    private var routeDayOffset = 0 // 0 = today, 1 = yesterday, ... up to 29

    private val handler = Handler(Looper.getMainLooper())

    private var places = ArrayList<Place>()
    private var selectedId: Long? = null
    private var pendingLat: Double? = null
    private var pendingLng: Double? = null
    private var pendingRadius = 200
    private var displayRadius: Int? = null // live slider preview for the selected place (saved only on Save)
    private var meLoc: Location? = null
    private var lastMarkerTapMs = 0L // a marker tap must not also drop a pending pin

    private val placeMarkers = HashMap<Long, Marker>()
    private val placeMarkerColors = HashMap<Long, Int>()
    private var meMarker: Marker? = null
    private var pendingMarker: Marker? = null
    private val pinIcons = HashMap<Int, Icon>()
    private var meDotIconCached: Icon? = null

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

    // ---- lifecycle ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
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

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView, lp(MATCH, 0, weight = 1f))
        mapView.getMapAsync { m ->
            map = m
            val cLat = meLoc?.latitude ?: places.firstOrNull { it.enabled }?.lat ?: 23.8103
            val cLng = meLoc?.longitude ?: places.firstOrNull { it.enabled }?.lng ?: 90.4125
            m.cameraPosition = CameraPosition.Builder().target(LatLng(cLat, cLng)).zoom(15.0).build()
            m.addOnMapClickListener { point ->
                onMapTap(point)
                true
            }
            m.setOnMarkerClickListener { marker -> onMarkerTap(marker) }
            applyStyle()
        }

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
        // route day stepper: view any of the last 30 days' routes on the map
        val routeRow = LinearLayout(this)
        routeRow.orientation = LinearLayout.HORIZONTAL
        routeRow.gravity = Gravity.CENTER_VERTICAL
        val prevDay = tv("‹", 20f, C.primary, 1)
        prevDay.setPadding(dp(12), dp(2), dp(12), dp(2))
        prevDay.pressFeedback()
        prevDay.setOnClickListener { stepRouteDay(1) }
        routeRow.addView(prevDay)
        routeDayTv = tv("", 13f, C.muted)
        routeDayTv.gravity = Gravity.CENTER
        routeRow.addView(routeDayTv, lp(0, WRAP, weight = 1f))
        val nextDay = tv("›", 20f, C.primary, 1)
        nextDay.setPadding(dp(12), dp(2), dp(12), dp(2))
        nextDay.pressFeedback()
        nextDay.setOnClickListener { stepRouteDay(-1) }
        routeRow.addView(nextDay)
        panel.addView(routeRow, lp(MATCH, WRAP, t = dp(4)))
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

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::mapView.isInitialized) mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
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
        updateRouteDay() // label + renderAll()
    }

    /** Switch between the OSM map and the Esri satellite style. */
    private fun toggleSat() {
        satOn = !satOn
        satBtn.text = if (satOn) "🗺 " + L.t("Map", "ম্যাপ") else "🛰 " + L.t("Satellite", "স্যাটেলাইট")
        applyStyle()
    }

    private fun recenterOnMe() {
        toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
        Extra.currentLocation(this) { loc ->
            if (loc == null) {
                toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
            } else {
                meLoc = loc
                updatePanel()
                renderAll()
                try {
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.0))
                } catch (e: Exception) {
                    Log.e(TAG, "map recenter failed", e)
                }
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
        displayRadius = null
        editRadius = pl.radius
        editMode = pl.mode
        syncEditControls()
        updatePanel()
        renderAll()
        if (editRadius > 2000) fitCircle(pl.lat, pl.lng, editRadius)
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
        pendingRadius = editRadius
        val id = selectedId ?: return
        val pl = places.find { it.id == id } ?: return
        displayRadius = editRadius
        renderAll()
        if (editRadius > 2000) fitCircle(pl.lat, pl.lng, editRadius)
    }

    private fun setEditMode(m: Int) {
        editMode = m
        syncEditControls()
        updatePanel()
        renderAll()
    }

    private fun saveSelected() {
        val id = selectedId ?: return
        val idx = places.indexOfFirst { it.id == id }
        if (idx < 0) return
        places[idx] = places[idx].copy(radius = editRadius, mode = editMode)
        displayRadius = null
        Extra.savePlaces(this, places)
        PlaceWatcher.sync(this)
        PlaceWatcher.checkNow(this)
        toast(L.t("Saved", "সংরক্ষণ হয়েছে"))
        updatePanel()
        renderAll()
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
                displayRadius = null
                Extra.savePlaces(this, places)
                PlaceWatcher.sync(this)
                PlaceWatcher.checkNow(this)
                updatePanel()
                renderAll()
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
                val id = Store.newId()
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

    private fun stepRouteDay(delta: Int) {
        routeDayOffset = (routeDayOffset + delta).coerceIn(0, 29)
        updateRouteDay()
    }

    /** Label + polyline for the selected day's route. */
    private fun updateRouteDay() {
        val day = Store.dayKey(routeDayOffset)
        val distM = Store.distanceForDay(this, day)
        val distTxt = if (distM >= 1000f) {
            val km10 = (distM / 100).toInt()
            L.num("${km10 / 10}.${km10 % 10}") + " " + L.t("km", "কিমি")
        } else if (distM > 0f) {
            L.num(distM.toInt().toString()) + " " + L.t("m", "মি")
        } else {
            L.t("no route", "কোনো রুট নেই")
        }
        val label = when (routeDayOffset) {
            0 -> L.t("Today's route", "আজকের রুট")
            1 -> L.t("Yesterday's route", "গতকালের রুট")
            else -> {
                val c = java.util.Calendar.getInstance()
                c.add(java.util.Calendar.DAY_OF_YEAR, -routeDayOffset)
                L.num(c.get(java.util.Calendar.DAY_OF_MONTH).toString()) + " " + L.month(c.get(java.util.Calendar.MONTH)) + L.t("'s route", "র রুট")
            }
        }
        routeDayTv.text = "$label: $distTxt"
        renderAll()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- native map ---------------------------------------------------------

    /** Apply the current style (OSM map or Esri satellite); layers re-render on load. */
    private fun applyStyle() {
        val m = map ?: return
        styleReady = false
        style = null
        val json = if (satOn) SAT_STYLE_JSON else OSM_STYLE_JSON
        try {
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                style = s
                styleReady = true
                renderAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "map style failed", e)
        }
    }

    /** Redraw everything: place circles, pending circle, route line, markers. */
    private fun renderAll() {
        val s = style ?: return
        if (!styleReady) return
        try {
            renderPlaceLayers(s)
            renderPendingLayer(s)
            renderRouteLayer(s)
            renderMarkers()
        } catch (e: Exception) {
            Log.e(TAG, "map render failed", e)
        }
    }

    private fun renderPlaceLayers(s: Style) {
        val activeFeats = JSONArray()
        val inactiveFeats = JSONArray()
        for (pl in places) {
            val r = if (pl.id == selectedId) (displayRadius ?: pl.radius) else pl.radius
            if (placeActive(pl) == true) activeFeats.put(circleFeature(pl.lat, pl.lng, r))
            else inactiveFeats.put(circleFeature(pl.lat, pl.lng, r))
        }
        setGeoJsonSource(s, "places-active", featureCollection(activeFeats))
        setGeoJsonSource(s, "places-inactive", featureCollection(inactiveFeats))
        ensureFillLine(s, "places-active", "#22c55e")
        ensureFillLine(s, "places-inactive", "#3b82f6")
        // the selected place gets a thicker outline
        val sel = places.find { it.id == selectedId }
        val selFeats = JSONArray()
        if (sel != null) selFeats.put(circleFeature(sel.lat, sel.lng, displayRadius ?: sel.radius))
        setGeoJsonSource(s, "places-sel", featureCollection(selFeats))
        val selColor = if (sel != null && placeActive(sel) == true) "#22c55e" else "#3b82f6"
        if (s.getLayer("places-sel-line") == null) {
            s.addLayer(LineLayer("places-sel-line", "places-sel").withProperties(lineColor(selColor), lineWidth(4f)))
        } else {
            (s.getLayer("places-sel-line") as? LineLayer)?.setProperties(lineColor(selColor))
        }
    }

    private fun renderPendingLayer(s: Style) {
        val la = pendingLat
        val lo = pendingLng
        val feats = JSONArray()
        if (la != null && lo != null) feats.put(circleFeature(la, lo, pendingRadius))
        setGeoJsonSource(s, "pending-circle", featureCollection(feats))
        ensureFillLine(s, "pending-circle", "#FFB84D")
    }

    private fun renderRouteLayer(s: Style) {
        val coords = JSONArray()
        for (pt in Store.routeForDay(this, Store.dayKey(routeDayOffset))) {
            val pair = JSONArray()
            pair.put(pt.lng)
            pair.put(pt.lat)
            coords.put(pair)
        }
        val feats = JSONArray()
        if (coords.length() >= 2) {
            val geom = JSONObject()
            geom.put("type", "LineString")
            geom.put("coordinates", coords)
            val feat = JSONObject()
            feat.put("type", "Feature")
            feat.put("properties", JSONObject())
            feat.put("geometry", geom)
            feats.put(feat)
        }
        setGeoJsonSource(s, "route-line", featureCollection(feats))
        if (s.getLayer("route-line-layer") == null) {
            s.addLayer(
                LineLayer("route-line-layer", "route-line").withProperties(
                    lineColor("#10b981"), lineWidth(4f), lineOpacity(0.9f)
                )
            )
        }
    }

    private fun setGeoJsonSource(s: Style, id: String, geoJson: String) {
        val existing = s.getSource(id) as? GeoJsonSource
        if (existing != null) existing.setGeoJson(FeatureCollection.fromJson(geoJson))
        else s.addSource(GeoJsonSource(id, geoJson))
    }

    private fun ensureFillLine(s: Style, srcId: String, color: String) {
        if (s.getLayer("$srcId-fill") == null) {
            s.addLayer(FillLayer("$srcId-fill", srcId).withProperties(fillColor(color), fillOpacity(0.15f)))
        }
        if (s.getLayer("$srcId-line") == null) {
            s.addLayer(LineLayer("$srcId-line", srcId).withProperties(lineColor(color), lineWidth(2f)))
        }
    }

    /** Place pins (green = locking, blue = not), pending pin (orange), me dot (blue). */
    private fun renderMarkers() {
        val m = map ?: return
        val seen = HashSet<Long>()
        for (pl in places) {
            seen.add(pl.id)
            val col = if (placeActive(pl) == true) Color.parseColor("#22c55e") else Color.parseColor("#3b82f6")
            val existing = placeMarkers[pl.id]
            if (existing != null && placeMarkerColors[pl.id] == col) {
                existing.position = LatLng(pl.lat, pl.lng)
            } else {
                if (existing != null) m.removeMarker(existing)
                val marker = m.addMarker(MarkerOptions().position(LatLng(pl.lat, pl.lng)).icon(pinIcon(col)))
                placeMarkers[pl.id] = marker
                placeMarkerColors[pl.id] = col
            }
        }
        val dead = placeMarkers.keys.filter { it !in seen }
        for (id in dead) {
            val mk = placeMarkers[id]
            if (mk != null) m.removeMarker(mk)
            placeMarkers.remove(id)
            placeMarkerColors.remove(id)
        }
        val pla = pendingLat
        val plo = pendingLng
        if (pla != null && plo != null) {
            val pm = pendingMarker
            if (pm != null) pm.position = LatLng(pla, plo)
            else pendingMarker = m.addMarker(
                MarkerOptions().position(LatLng(pla, plo)).icon(pinIcon(Color.parseColor("#f59e0b")))
            )
        } else if (pendingMarker != null) {
            m.removeMarker(pendingMarker!!)
            pendingMarker = null
        }
        val me = meLoc
        if (me != null) {
            val mm = meMarker
            if (mm != null) mm.position = LatLng(me.latitude, me.longitude)
            else meMarker = m.addMarker(MarkerOptions().position(LatLng(me.latitude, me.longitude)).icon(meDotIcon()))
        } else if (meMarker != null) {
            m.removeMarker(meMarker!!)
            meMarker = null
        }
    }

    /** Tap on the map: inside a place circle selects it (nearest centre wins), else drops a pending pin. */
    private fun onMapTap(point: LatLng) {
        if (System.currentTimeMillis() - lastMarkerTapMs < 300) return
        var best: Place? = null
        var bestD = Float.MAX_VALUE
        for (pl in places) {
            val res = FloatArray(1)
            Location.distanceBetween(point.latitude, point.longitude, pl.lat, pl.lng, res)
            if (res[0] <= pl.radius && res[0] < bestD) {
                best = pl
                bestD = res[0]
            }
        }
        if (best != null) {
            selectPlace(best.id)
            return
        }
        pendingLat = point.latitude
        pendingLng = point.longitude
        selectedId = null
        displayRadius = null
        renderAll()
        updatePanel()
    }

    private fun onMarkerTap(marker: Marker): Boolean {
        lastMarkerTapMs = System.currentTimeMillis()
        val id = placeMarkers.entries.firstOrNull { it.value == marker }?.key
        if (id != null) selectPlace(id)
        return true
    }

    /** Zoom the camera so a circle of the given radius fits the screen. */
    private fun fitCircle(lat: Double, lng: Double, radiusM: Int) {
        val m = map ?: return
        try {
            val dLat = radiusM / 111320.0
            val dLng = radiusM / (111320.0 * Math.cos(Math.toRadians(lat)))
            val bounds = LatLngBounds.Builder()
                .include(LatLng(lat - dLat, lng - dLng))
                .include(LatLng(lat + dLat, lng + dLng))
                .build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 30))
        } catch (e: Exception) {
            Log.e(TAG, "map fit failed", e)
        }
    }

    // ---- GeoJSON helpers (org.json builds the strings; FeatureCollection parses them) -----

    /** A 64-gon polygon approximating a circle of radiusM metres around (lat, lng). */
    private fun circleFeature(lat: Double, lng: Double, radiusM: Int): JSONObject {
        val ring = JSONArray()
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        for (i in 0..64) {
            val b = i / 64.0 * 2 * Math.PI
            val d = radiusM / 6371000.0
            val lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(b))
            val lng2 = lng1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2))
            val pt = JSONArray()
            pt.put(Math.toDegrees(lng2))
            pt.put(Math.toDegrees(lat2))
            ring.put(pt)
        }
        val coords = JSONArray()
        coords.put(ring)
        val geom = JSONObject()
        geom.put("type", "Polygon")
        geom.put("coordinates", coords)
        val feat = JSONObject()
        feat.put("type", "Feature")
        feat.put("properties", JSONObject())
        feat.put("geometry", geom)
        return feat
    }

    private fun featureCollection(features: JSONArray): String {
        val fc = JSONObject()
        fc.put("type", "FeatureCollection")
        fc.put("features", features)
        return fc.toString()
    }

    // ---- marker icons (drawn in code — no drawable resources) -------------

    /** A teardrop pin bitmap in the given ARGB colour (cached per colour). */
    private fun pinIcon(color: Int): Icon {
        val cached = pinIcons[color]
        if (cached != null) return cached
        val d = resources.displayMetrics.density
        val w = (40 * d).toInt()
        val h = (56 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = color
        val cx = w / 2f
        val cy = h * 0.36f
        val r = w * 0.36f
        cv.drawCircle(cx, cy, r, paint)
        val path = Path()
        path.moveTo(cx - r * 0.72f, cy + r * 0.5f)
        path.lineTo(cx + r * 0.72f, cy + r * 0.5f)
        path.lineTo(cx, h * 0.97f)
        path.close()
        cv.drawPath(path, paint)
        paint.color = Color.WHITE
        cv.drawCircle(cx, cy, r * 0.45f, paint)
        val icon = IconFactory.getInstance(this).fromBitmap(bmp)
        pinIcons[color] = icon
        return icon
    }

    /** The blue "you are here" dot. */
    private fun meDotIcon(): Icon {
        val cached = meDotIconCached
        if (cached != null) return cached
        val d = resources.displayMetrics.density
        val size = (22 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        val c = size / 2f
        paint.color = Color.WHITE
        cv.drawCircle(c, c, size * 0.48f, paint)
        paint.color = Color.parseColor("#2563eb")
        cv.drawCircle(c, c, size * 0.34f, paint)
        val icon = IconFactory.getInstance(this).fromBitmap(bmp)
        meDotIconCached = icon
        return icon
    }
}
