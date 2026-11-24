package com.powerlock.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

/**
 * Blocklist viewer: shows every domain in the built-in 100,000-domain
 * blocklist (assets/adult_blocklist.txt), one per row, with live search.
 *
 * The list loads on a background thread and the rows recycle through a
 * ListView adapter, so opening it never freezes the UI. Search filters with
 * a plain substring match on a worker thread (a linear scan over 100k short
 * strings takes a few dozen milliseconds) and posts the result back; only
 * the newest filter request is ever applied.
 */
class BlocklistActivity : Activity() {

    private lateinit var countTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var listView: ListView
    private lateinit var emptyTv: TextView
    private lateinit var adapter: DomainAdapter

    /** All domains, sorted; empty until the background load finishes. */
    @Volatile
    private var allDomains: List<String> = emptyList()

    @Volatile
    private var destroyed = false

    /** Filter requests are serialised: only the newest query is applied. */
    @Volatile
    private var filterSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        C.load(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.setBackgroundColor(C.bg)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = tv("‹", 30f, C.primary, 1)
        back.setPadding(dp(16), dp(6), dp(10), dp(6))
        back.pressFeedback()
        back.setOnClickListener { finish() }
        header.addView(back)
        header.addView(heading(L.t("Blocklist", "ব্লকলিস্ট")))
        root.addView(header, lp(MATCH, WRAP, t = dp(8)))

        countTv = tv(L.t("Loading…", "লোড হচ্ছে…"), 13f, C.muted)
        root.addView(countTv, lp(MATCH, WRAP, l = dp(16), r = dp(16), t = dp(4)))

        searchEt = edit(L.t("Search sites…", "সাইট সার্চ করুন…"), false)
        searchEt.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString() ?: "")
            }
        })
        root.addView(searchEt, lp(MATCH, WRAP, l = dp(16), r = dp(16), t = dp(12)))

        adapter = DomainAdapter()
        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = null
        listView.dividerHeight = 0
        root.addView(listView, lp(MATCH, 0, t = dp(8), weight = 1f))

        emptyTv = tv(L.t("No sites found", "কোনো সাইট পাওয়া যায়নি"), 14f, C.muted)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.visibility = View.GONE
        root.addView(emptyTv, lp(MATCH, WRAP, t = dp(24)))

        setContentView(root)
        loadList()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    /** Reads the asset on a worker thread, sorts it, then shows it (filtered if the user already typed). */
    private fun loadList() {
        val t = Thread({
            try {
                val list = ArrayList<String>(110000)
                assets.open("adult_blocklist.txt").bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val d = raw.trim().lowercase()
                        if (d.isNotEmpty() && !d.startsWith("#")) list.add(d)
                    }
                }
                list.sort()
                if (destroyed) return@Thread
                allDomains = list
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    filter(searchEt.text.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "blocklist viewer load failed", e)
                if (!destroyed) runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    countTv.text = L.t("Could not load the blocklist", "ব্লকলিস্ট লোড করা যায়নি")
                }
            }
        }, "blocklist-viewer-load")
        t.isDaemon = true
        t.start()
    }

    /** Filters the list for [queryRaw] (empty = show everything). Runs the scan off the UI thread. */
    private fun filter(queryRaw: String) {
        val query = queryRaw.trim().lowercase()
        val seq = ++filterSeq
        val all = allDomains
        if (query.isEmpty()) {
            applyFilter(all, seq)
            return
        }
        val t = Thread({
            val out = ArrayList<String>()
            for (d in all) {
                if (d.contains(query)) out.add(d)
            }
            if (destroyed) return@Thread
            runOnUiThread { applyFilter(out, seq) }
        }, "blocklist-viewer-filter")
        t.isDaemon = true
        t.start()
    }

    /** Applies a filter result, but only if it is still the newest request. */
    private fun applyFilter(items: List<String>, seq: Int) {
        if (seq != filterSeq || destroyed) return
        adapter.items = items
        adapter.notifyDataSetChanged()
        updateCount(items.size, allDomains.size)
        val noMatch = items.isEmpty() && allDomains.isNotEmpty()
        emptyTv.visibility = if (noMatch) View.VISIBLE else View.GONE
        listView.visibility = if (noMatch) View.GONE else View.VISIBLE
    }

    private fun updateCount(shown: Int, total: Int) {
        countTv.text = if (shown == total) {
            L.t(
                "${L.num(String.format(Locale.US, "%,d", total))} sites blocked",
                "${L.num(String.format(Locale.US, "%,d", total))}টি সাইট ব্লক করা আছে"
            )
        } else {
            L.t(
                "${L.num(String.format(Locale.US, "%,d", shown))} of ${L.num(String.format(Locale.US, "%,d", total))} sites",
                "মোট ${L.num(String.format(Locale.US, "%,d", total))}টির মধ্যে ${L.num(String.format(Locale.US, "%,d", shown))}টি পাওয়া গেছে"
            )
        }
    }

    /** One recycled TextView per row: 100,000 rows stay smooth. */
    private inner class DomainAdapter : BaseAdapter() {
        var items: List<String> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? TextView) ?: TextView(this@BlocklistActivity).apply {
                textSize = 15f
                setTextColor(C.text)
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            v.text = items[position]
            return v
        }
    }
}
