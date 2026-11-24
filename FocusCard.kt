package com.powerlock.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Focus card share: renders today's focus stats as a shareable image
 * (dark theme + accent, drawn with Canvas only — no external libs) and
 * shares it through the Android share sheet via FileProvider.
 * The PNG lives only in the cache dir and is overwritten on every share.
 */
object FocusCardShare {

    private const val W = 1080
    private const val H = 1350

    fun share(activity: MainActivity) {
        try {
            val bmp = render(activity)
            val file = File(activity.cacheDir, "focus_card.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    L.t("My focus today — PowerLock 2", "আজ আমার ফোকাস — PowerLock 2")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(
                    intent, L.t("Share focus card", "ফোকাস কার্ড শেয়ার করুন")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "focus card share failed", e)
            activity.toast(L.t("Could not create the card", "কার্ড তৈরি করা যায়নি"))
        }
    }

    /** Draws the card: header, today's focus minutes, four stat cells, today's quote. */
    fun render(ctx: android.content.Context): Bitmap {
        val fs = Store.focusStats(ctx)
        val score = Extra.focusScore(ctx)
        val focusMinToday = Store.focusDayMap(ctx)[Store.dayKey(0)] ?: 0

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bg = Color.parseColor("#0A1510")
        val cardCol = Color.parseColor("#10261B")
        cv.drawColor(bg)

        // faint accent rings for depth (top-right, bottom-left)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 6f
        ring.color = tint(C.primary, 28)
        cv.drawCircle(W * 0.92f, H * 0.06f, 260f, ring)
        cv.drawCircle(W * 0.08f, H * 0.94f, 220f, ring)

        // shield + app name
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        iconPaint.color = C.primary
        Icons.draw(cv, Ic.SHIELD, W / 2f, 150f, 120f, iconPaint)

        val title = Paint(Paint.ANTI_ALIAS_FLAG)
        title.color = C.text
        title.textSize = 46f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.textAlign = Paint.Align.CENTER
        title.letterSpacing = 0.12f
        cv.drawText("POWERLOCK 2", W / 2f, 285f, title)

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        datePaint.color = C.muted
        datePaint.textSize = 32f
        datePaint.textAlign = Paint.Align.CENTER
        cv.drawText(fmtDate(System.currentTimeMillis()), W / 2f, 335f, datePaint)

        // divider
        val div = Paint(Paint.ANTI_ALIAS_FLAG)
        div.color = tint(C.primary, 90)
        div.strokeWidth = 3f
        cv.drawLine(W * 0.2f, 395f, W * 0.8f, 395f, div)

        // today's focus minutes, big
        val label = Paint(Paint.ANTI_ALIAS_FLAG)
        label.color = C.primary
        label.textSize = 34f
        label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        label.textAlign = Paint.Align.CENTER
        label.letterSpacing = 0.14f
        cv.drawText(L.t("TODAY'S FOCUS", "আজকের ফোকাস").uppercase(), W / 2f, 475f, label)

        val big = Paint(Paint.ANTI_ALIAS_FLAG)
        big.color = C.mint
        big.textSize = 128f
        big.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        big.textAlign = Paint.Align.CENTER
        cv.drawText(fmtDur(focusMinToday * 60000L), W / 2f, 600f, big)

        // four stat cells, 2x2
        val cells = listOf(
            Pair(L.num(score.score.toString()), L.t("Focus score", "ফোকাস স্কোর")),
            Pair(L.num(fs.streak.toString()), L.t("Day streak", "দিনের স্ট্রিক")),
            Pair(L.num(fs.completed.toString()), L.t("Locks completed", "সম্পন্ন লক")),
            Pair(L.num(score.blocksToday.toString()), L.t("Blocks resisted", "প্রতিহত ব্লক"))
        )
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        cellPaint.color = cardCol
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        valuePaint.color = C.mint
        valuePaint.textSize = 66f
        valuePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        valuePaint.textAlign = Paint.Align.CENTER
        val cellLabel = Paint(Paint.ANTI_ALIAS_FLAG)
        cellLabel.color = C.muted
        cellLabel.textSize = 30f
        cellLabel.textAlign = Paint.Align.CENTER
        val rect = RectF()
        for (i in cells.indices) {
            val col = i % 2
            val row = i / 2
            val left = 80f + col * 500f
            val top = 660f + row * 220f
            rect.set(left, top, left + 420f, top + 180f)
            cv.drawRoundRect(rect, 36f, 36f, cellPaint)
            val cx = left + 210f
            cv.drawText(cells[i].first, cx, top + 82f, valuePaint)
            cv.drawText(cells[i].second, cx, top + 138f, cellLabel)
        }

        // today's quote, word-wrapped
        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        quotePaint.color = C.textSoft
        quotePaint.textSize = 32f
        quotePaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        quotePaint.textAlign = Paint.Align.CENTER
        val quote = "“" + Quotes.today() + "”"
        val lines = wrap(quote, quotePaint, W - 180f)
        var y = 1180f
        for (line in lines.take(3)) {
            cv.drawText(line, W / 2f, y, quotePaint)
            y += 46f
        }

        val foot = Paint(Paint.ANTI_ALIAS_FLAG)
        foot.color = C.muted
        foot.textSize = 26f
        foot.textAlign = Paint.Align.CENTER
        cv.drawText(L.t("Focus, one lock at a time", "একবারে একটা লক, একদিনে একদিন"), W / 2f, H - 44f, foot)
        return bmp
    }

    /** Greedy word wrap for the quote (Canvas has no text wrapping). */
    private fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) <= maxW) {
                line = test
            } else {
                if (line.isNotEmpty()) out.add(line)
                line = word
            }
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }
}
