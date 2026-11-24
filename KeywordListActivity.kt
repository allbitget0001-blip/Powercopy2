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
 * Keyword viewer: shows every word in the built-in keyword blocklist
 * (assets/keyword_blocklist.txt — Bengali + English bad words), one per
 * row, sorted A to Z, with live search. Same pattern as BlocklistActivity.
 *
 * The list loads on a background thread and the rows recycle through a
 * ListView adapter, so opening it never freezes the UI. Search filters
 * with a plain substring match on a worker thread and posts the result
 * back; only the newest filter request is ever applied. The user's own
 * keywords are not shown here — they are already listed in the settings
 * card that opens this viewer.
 */
class KeywordListActivity : Activity() {

    private lateinit var countTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var listView: ListView
    private lateinit var emptyTv: TextView
    private lateinit var adapter: KeywordAdapter

    /** All keywords, sorted A to Z; empty until the background load finishes. */
    @Volatile
    private var allKeywords: List<String> = emptyList()

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
        header.addView(heading(L.t("Keywords", "কিওয়ার্ড")))
        root.addView(header, lp(MATCH, WRAP, t = dp(8)))

        countTv = tv(L.t("Loading…", "লোড হচ্ছে…"), 13f, C.muted)
        root.addView(countTv, lp(MATCH, WRAP, l = dp(16), r = dp(16), t = dp(4)))

        searchEt = edit(L.t("Search keywords…", "কিওয়ার্ড সার্চ করুন…"), false)
        searchEt.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString() ?: "")
            }
        })
        root.addView(searchEt, lp(MATCH, WRAP, l = dp(16), r = dp(16), t = dp(12)))

        adapter = KeywordAdapter()
        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = null
        listView.dividerHeight = 0
        root.addView(listView, lp(MATCH, 0, t = dp(8), weight = 1f))

        emptyTv = tv(L.t("No keywords found", "কোনো কিওয়ার্ড পাওয়া যায়নি"), 14f, C.muted)
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

    /** Reads the asset on a worker thread, sorts it A to Z, then shows it (filtered if the user already typed). */
    private fun loadList() {
        val t = Thread({
            try {
                val list = ArrayList<String>(6000)
                assets.open("keyword_blocklist.txt").bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val k = raw.trim().lowercase()
                        if (k.isNotEmpty() && !k.startsWith("#")) list.add(k)
                    }
                }
                list.sort()
                if (destroyed) return@Thread
                allKeywords = list
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    filter(searchEt.text.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "keyword viewer load failed", e)
                if (!destroyed) runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    countTv.text = L.t("Could not load the keywords", "কিওয়ার্ড লোড করা যায়নি")
                }
            }
        }, "keyword-viewer-load")
        t.isDaemon = true
        t.start()
    }

    /** Filters the list for [queryRaw] (empty = show everything). Runs the scan off the UI thread. */
    private fun filter(queryRaw: String) {
        val query = queryRaw.trim().lowercase()
        val seq = ++filterSeq
        val all = allKeywords
        if (query.isEmpty()) {
            applyFilter(all, seq)
            return
        }
        val t = Thread({
            val out = ArrayList<String>()
            for (k in all) {
                if (k.contains(query)) out.add(k)
            }
            if (destroyed) return@Thread
            runOnUiThread { applyFilter(out, seq) }
        }, "keyword-viewer-filter")
        t.isDaemon = true
        t.start()
    }

    /** Applies a filter result, but only if it is still the newest request. */
    private fun applyFilter(items: List<String>, seq: Int) {
        if (seq != filterSeq || destroyed) return
        adapter.items = items
        adapter.notifyDataSetChanged()
        updateCount(items.size, allKeywords.size)
        val noMatch = items.isEmpty() && allKeywords.isNotEmpty()
        emptyTv.visibility = if (noMatch) View.VISIBLE else View.GONE
        listView.visibility = if (noMatch) View.GONE else View.VISIBLE
    }

    private fun updateCount(shown: Int, total: Int) {
        countTv.text = if (shown == total) {
            L.t(
                "${L.num(String.format(Locale.US, "%,d", total))} keywords blocked",
                "${L.num(String.format(Locale.US, "%,d", total))}টি কিওয়ার্ড ব্লক করা আছে"
            )
        } else {
            L.t(
                "${L.num(String.format(Locale.US, "%,d", shown))} of ${L.num(String.format(Locale.US, "%,d", total))} keywords",
                "মোট ${L.num(String.format(Locale.US, "%,d", total))}টির মধ্যে ${L.num(String.format(Locale.US, "%,d", shown))}টি পাওয়া গেছে"
            )
        }
    }

    /** One recycled TextView per row: 5,000+ rows stay smooth. */
    private inner class KeywordAdapter : BaseAdapter() {
        var items: List<String> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? TextView) ?: TextView(this@KeywordListActivity).apply {
                textSize = 15f
                setTextColor(C.text)
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            v.text = items[position]
            return v
        }
    }
}
