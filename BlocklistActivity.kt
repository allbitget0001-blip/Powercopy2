package com.powerlock.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
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
 *
 * An A-Z fast-scroll index sits beside the list (same pattern as the app
 * picker in Features.kt): touch or drag a letter to jump to that section,
 * with a letter bubble while dragging. The index hides while searching.
 */
class BlocklistActivity : Activity() {

    private lateinit var countTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var listView: ListView
    private lateinit var emptyTv: TextView
    private lateinit var adapter: DomainAdapter
    private lateinit var lettersCol: LinearLayout

    /** All domains, sorted; empty until the background load finishes. */
    @Volatile
    private var allDomains: List<String> = emptyList()

    /** First index for each side-index letter, built once when the list loads. */
    @Volatile
    private var sectionStarts: Map<Char, Int> = emptyMap()

    /** Current search query (trimmed, lowercase); the index hides while searching. */
    private var currentQuery = ""

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

        // A-Z fast-scroll index beside the list: touch or drag a letter to
        // jump to the first domain starting with it (same pattern as the
        // app picker in Features.kt; hidden while searching).
        val letters = ('A'..'Z').toList()
        lettersCol = LinearLayout(this)
        lettersCol.orientation = LinearLayout.VERTICAL
        lettersCol.gravity = Gravity.CENTER
        lettersCol.visibility = View.GONE
        val letterViews = ArrayList<TextView>()
        for (ch in letters) {
            val lt = tv(ch.toString(), 10f, C.muted)
            lt.gravity = Gravity.CENTER
            lt.setPadding(0, dp(1), 0, dp(1))
            lettersCol.addView(lt, lp(MATCH, WRAP))
            letterViews.add(lt)
        }
        // the letter under the finger pops a bit bigger (animated) while touched
        var enlargedIdx = -1
        fun setLetterPop(idx: Int, popped: Boolean) {
            val v = letterViews.getOrNull(idx) ?: return
            v.setTextColor(if (popped) C.primary else C.muted)
            val s = if (popped) 1.5f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
        // big letter bubble in the middle while dragging the index (like Contacts)
        val bubble = tv("", 30f, C.onPrimary)
        bubble.gravity = Gravity.CENTER
        bubble.background = rounded(C.primary, dp(32).toFloat())
        bubble.visibility = View.GONE
        bubble.alpha = 0f
        fun showBubble(ch: Char) {
            bubble.text = ch.toString()
            if (bubble.visibility != View.VISIBLE) {
                bubble.animate().cancel()
                bubble.visibility = View.VISIBLE
                bubble.alpha = 0f
                bubble.scaleX = 0.6f
                bubble.scaleY = 0.6f
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
        fun hideBubble() {
            if (bubble.visibility != View.VISIBLE) return
            bubble.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150).withEndAction {
                bubble.visibility = View.GONE
            }.start()
        }
        lettersCol.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val h = lettersCol.height
                    if (h > 0) {
                        val idx = ((ev.y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
                        if (idx != enlargedIdx) {
                            if (enlargedIdx >= 0) setLetterPop(enlargedIdx, false)
                            setLetterPop(idx, true)
                            enlargedIdx = idx
                        }
                        showBubble(letters[idx])
                        jumpToLetter(letters[idx])
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (enlargedIdx >= 0) {
                        setLetterPop(enlargedIdx, false)
                        enlargedIdx = -1
                    }
                    hideBubble()
                    true
                }
                else -> false
            }
        }
        val contentRow = LinearLayout(this)
        contentRow.orientation = LinearLayout.HORIZONTAL
        contentRow.addView(listView, lp(0, MATCH, weight = 1f))
        contentRow.addView(lettersCol, lp(dp(22), MATCH, l = dp(4)))
        val contentFrame = FrameLayout(this)
        contentFrame.addView(contentRow, FrameLayout.LayoutParams(MATCH, MATCH))
        val bubbleSize = dp(64)
        val bubbleParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        bubbleParams.gravity = Gravity.CENTER
        contentFrame.addView(bubble, bubbleParams)
        root.addView(contentFrame, lp(MATCH, 0, t = dp(8), weight = 1f))

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
                sectionStarts = buildSections(list)
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
        currentQuery = query
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
        // the A-Z index only makes sense on the full list, not on a search result
        lettersCol.visibility = if (allDomains.isNotEmpty() && currentQuery.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * First index for each side-index letter: the first domain whose
     * first letter is that letter (or the next letter that has domains).
     * Built on the worker thread when the list loads, so jumps are
     * then O(1) instead of a 100k scan per touch.
     */
    private fun buildSections(list: List<String>): Map<Char, Int> {
        val map = LinkedHashMap<Char, Int>()
        for (ch in ('A'..'Z')) {
            val target = ch.lowercaseChar()
            val idx = list.indexOfFirst { (it.firstOrNull() ?: ' ') >= target }
            if (idx >= 0) map[ch] = idx
        }
        return map
    }

    /**
     * Jumps the list to [ch]'s section. Instant, not animated: ListView
     * has no bounded-time smooth scroll, and a smooth scroll over
     * 100,000 rows would lag behind the finger while dragging.
     */
    private fun jumpToLetter(ch: Char) {
        val pos = sectionStarts[ch] ?: return
        listView.setSelection(pos)
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
