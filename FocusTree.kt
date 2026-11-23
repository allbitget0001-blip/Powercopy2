package com.powerlock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** One finished tree in the garden: when it ended, how long the lock ran, its final stage, alive or withered, and its species. */
data class GardenTree(val endMs: Long, val durMin: Int, val stage: Int, val alive: Boolean, val species: String = "classic")

/**
 * Focus tree: a seed is planted when a normal lock starts; it grows as the
 * lock progresses and survives if the lock completes, or withers if the lock
 * is stopped early. Location-unlock locks grow trees too (they are normal locks).
 */
object FocusTree {
    const val STAGES = 5
    private const val MAX_GARDEN = 30
    /** Location-only locks have no countdown, so the tree is fully grown after an hour of travel. */
    private const val LOC_FULL_MS = 60 * 60_000L

    /** Stage 0..4 from the fraction of the lock completed. */
    fun stageFor(frac: Float): Int = when {
        frac < 0.2f -> 0
        frac < 0.4f -> 1
        frac < 0.6f -> 2
        frac < 0.8f -> 3
        else -> 4
    }

    fun stageName(stage: Int): String = when (stage.coerceIn(0, 4)) {
        0 -> L.t("Seed", "বীজ")
        1 -> L.t("Sprout", "অঙ্কুর")
        2 -> L.t("Sapling", "চারা গাছ")
        3 -> L.t("Young tree", "ছোট গাছ")
        else -> L.t("Full tree", "পূর্ণ গাছ")
    }

    /** True while the running session has a tree planted. */
    fun isPlanted(ctx: Context): Boolean = Store.lock(ctx).getBoolean("tree_planted", false)

    /** The running tree's growth fraction (0..1). Location-only locks grow by elapsed time. */
    fun currentFraction(ctx: Context, now: Long = System.currentTimeMillis()): Float {
        return fractionFor(ctx, now)
    }

    /** Monotonic elapsed time (ms) of the running session: the wall clock
     * cannot distort the tree's growth. Falls back to wall elapsed for
     * sessions started before the monotonic timer existed. */
    private fun elapsedMs(ctx: Context, atMs: Long): Long {
        val p = Store.lock(ctx)
        return if ((p.getString("u_mode", UNLOCK_TIME) ?: UNLOCK_TIME) == UNLOCK_LOC) {
            val mark = p.getLong("start_mono", -1L)
            if (mark >= 0) (SystemClock.elapsedRealtime() - mark).coerceAtLeast(0L)
            else (atMs - p.getLong("start", atMs)).coerceAtLeast(0L)
        } else {
            val total = p.getLong("total", 0L)
            if (total <= 0L) 0L else (total - Store.remainingMs(ctx)).coerceIn(0L, total)
        }
    }

    private fun fractionFor(ctx: Context, atMs: Long): Float {
        val p = Store.lock(ctx)
        val elapsed = elapsedMs(ctx, atMs)
        return if ((p.getString("u_mode", UNLOCK_TIME) ?: UNLOCK_TIME) == UNLOCK_LOC) {
            (elapsed.toFloat() / LOC_FULL_MS.toFloat()).coerceIn(0f, 1f)
        } else {
            val total = p.getLong("total", 0L)
            if (total <= 0L) 0f else (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    }

    /**
     * Saves the planted tree to the garden and arms its one-time message.
     * Must run BEFORE the caller clears the session prefs (it needs the
     * stored start/total/unlock mode). No-op when no tree is planted.
     */
    fun recordOutcome(ctx: Context, alive: Boolean, endMs: Long) {
        val p = Store.lock(ctx)
        if (!p.getBoolean("tree_planted", false)) return
        val frac = fractionFor(ctx, endMs)
        val stage = stageFor(frac)
        val durMin = (elapsedMs(ctx, endMs) / 60000L).toInt().coerceAtLeast(0)
        val e = p.edit()
            .remove("tree_planted")
            .putString("tree_outcome", if (alive) "alive" else "dead")
        // a corrupt stored garden must never be overwritten with a fresh one —
        // the tree is lost, but the garden and the outcome flag survive
        if (JsonGuard.isCorrupt(p, "garden")) {
            Log.e(TAG, "garden corrupt, keeping stored value")
            e.apply()
            return
        }
        val list = garden(ctx).toMutableList()
        list.add(0, GardenTree(endMs, durMin, stage, alive, TreeSpecies.selected(ctx)))
        val trimmed = if (list.size > MAX_GARDEN) list.subList(0, MAX_GARDEN) else list
        val arr = JSONArray()
        for (t in trimmed) {
            val o = JSONObject()
            o.put("e", t.endMs)
            o.put("d", t.durMin)
            o.put("s", t.stage)
            o.put("a", t.alive)
            o.put("sp", t.species)
            arr.put(o)
        }
        e.putString("garden", arr.toString()).apply()
    }

    /** Saved trees, newest first (max 30). */
    fun garden(ctx: Context): List<GardenTree> {
        val out = ArrayList<GardenTree>()
        try {
            val arr = JSONArray(Store.lock(ctx).getString("garden", "[]"))
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    out.add(GardenTree(o.getLong("e"), o.getInt("d"), o.getInt("s"), o.getBoolean("a"), o.optString("sp", "classic")))
                } catch (_: Exception) {
                    // one malformed entry: skip it, keep the rest
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "garden read failed", e)
        }
        return out
    }

    /** The unshown outcome message ("alive"/"dead"), or null. */
    fun outcomePending(ctx: Context): String? =
        Store.lock(ctx).getString("tree_outcome", null)

    fun clearOutcome(ctx: Context) {
        Store.lock(ctx).edit().remove("tree_outcome").apply()
    }
}

/**
 * Draws a tree on a Canvas: seed → sprout → sapling → young tree → full tree.
 * [frac] 0..1 grows it continuously; [alive] false draws it withered and grey.
 * [species] recolours the leaves (classic follows the theme accent).
 * No image assets needed; colours follow the theme accent.
 */
class TreeView(ctx: Context) : View(ctx) {
    var frac: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
        }
    var alive: Boolean = true
    var species: String = "classic"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val groundY = h * 0.88f
        val f = frac
        // species leaf colours (classic = the theme accent)
        val sp = TreeSpecies.byId(species)
        val leafDark = sp?.dark?.let { Color.parseColor(it) } ?: Color.parseColor("#0E7A4F")
        val leafMid = sp?.mid?.let { Color.parseColor(it) } ?: C.primary
        val leafLight = sp?.light?.let { Color.parseColor(it) } ?: C.mint

        // ground mound
        paint.style = Paint.Style.FILL
        paint.color = if (alive) tint(C.primary, 0x2E) else tint(C.muted, 0x2E)
        canvas.drawOval(cx - w * 0.34f, groundY - h * 0.03f, cx + w * 0.34f, groundY + h * 0.06f, paint)

        val trunkCol = if (alive) Color.parseColor("#8A5A3B") else Color.parseColor("#6E7B74")

        if (f < 0.2f) {
            // seed on the ground, with a tiny shoot as it nears sprouting
            paint.color = trunkCol
            canvas.drawOval(cx - w * 0.05f, groundY - h * 0.045f, cx + w * 0.05f, groundY + h * 0.01f, paint)
            if (f > 0.08f && alive) {
                val shoot = ((f - 0.08f) / 0.12f).coerceIn(0f, 1f)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = w * 0.012f
                paint.color = C.primary
                val top = groundY - h * 0.045f - h * 0.08f * shoot
                canvas.drawLine(cx, groundY - h * 0.04f, cx, top, paint)
            }
            return
        }

        // trunk: grows with f, from a short stem to a tall trunk
        val trunkTop = groundY - h * (0.10f + 0.40f * f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = trunkCol
        paint.strokeWidth = w * (0.03f + 0.035f * f)
        canvas.drawLine(cx, groundY, cx, trunkTop, paint)

        // branches (from sapling on): two lines fanning out
        if (f >= 0.4f) {
            val bf = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val by = trunkTop + h * 0.10f
            val reach = w * 0.16f * bf
            val rise = h * 0.12f * bf
            paint.strokeWidth = w * (0.015f + 0.012f * f)
            canvas.drawLine(cx, by, cx - reach, by - rise, paint)
            canvas.drawLine(cx, by, cx + reach, by - rise, paint)
            if (!alive) {
                // withered: a third bare twig straight up
                canvas.drawLine(cx, trunkTop, cx + w * 0.05f, trunkTop - h * 0.08f, paint)
            }
        }

        if (!alive) return // withered: bare trunk + branches only

        // leaves: a small pair for the sprout, a full canopy as it grows
        paint.style = Paint.Style.FILL
        if (f < 0.4f) {
            val r = w * (0.05f + 0.05f * f)
            paint.color = leafMid
            canvas.drawCircle(cx - r * 0.8f, trunkTop - r * 0.4f, r, paint)
            paint.color = leafLight
            canvas.drawCircle(cx + r * 0.8f, trunkTop - r * 0.4f, r, paint)
            return
        }
        // canopy: overlapping circles, growing with f
        val cr = w * (0.10f + 0.17f * f)
        val cy = trunkTop - cr * 0.35f
        paint.color = leafDark
        canvas.drawCircle(cx, cy - cr * 0.35f, cr * 0.95f, paint)
        paint.color = leafMid
        canvas.drawCircle(cx - cr * 0.75f, cy + cr * 0.25f, cr * 0.8f, paint)
        canvas.drawCircle(cx + cr * 0.75f, cy + cr * 0.25f, cr * 0.8f, paint)
        paint.color = leafLight
        canvas.drawCircle(cx - cr * 0.3f, cy - cr * 0.5f, cr * 0.45f, paint)
    }
}
