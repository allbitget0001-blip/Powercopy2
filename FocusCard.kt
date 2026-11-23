package com.powerlock.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Renders today's focus stats as a portrait card image (1080x1350) and
 * shares it through the standard Android share sheet. No server, no upload —
 * the image is written to the app's cache dir and shared via FileProvider.
 */
object FocusCard {

    fun share(ctx: Context) {
        try {
            val fs = Store.focusStats(ctx)
            val todayMin = Store.focusDayMap(ctx)[Store.dayKey(0)] ?: 0
            val bmp = render(todayMin, fs.streak, fs.completed)
            val file = File(ctx.cacheDir, "focus_card.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val text = L.t(
                "My focus today with PowerLock 2: ${fmtDur(todayMin * 60000L)} · ${fs.streak}-day streak!",
                "PowerLock 2 দিয়ে আজকের ফোকাস: ${fmtDur(todayMin * 60000L)} · ${L.num(fs.streak.toString())} দিনের স্ট্রিক!"
            )
            val i = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(
                Intent.createChooser(i, L.t("Share focus card", "ফোকাস কার্ড শেয়ার করুন"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "focus card share failed", e)
            if (ctx is MainActivity) ctx.toast(L.t("Could not share the card", "কার্ড শেয়ার করা যায়নি"))
        }
    }

    private fun render(todayMin: Int, streak: Int, completed: Int): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // background: deep green + a soft glow circle behind the shield
        c.drawColor(Color.parseColor("#0A1510"))
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.color = Color.parseColor("#123324")
        c.drawCircle(w / 2f, 330f, 480f, glow)

        // shield icon (line-icon set draws with the paint's colour)
        val ip = Paint(Paint.ANTI_ALIAS_FLAG)
        ip.color = C.primary
        Icons.draw(c, Ic.SHIELD, w / 2f, 300f, 210f, ip)

        fun textPaint(size: Float, color: Int, medium: Boolean): Paint {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = size
            p.color = color
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(
                if (medium) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
            return p
        }

        val white = Color.parseColor("#F2FBF6")
        val muted = Color.parseColor("#8FA89B")
        // brand + date
        c.drawText("POWERLOCK 2", w / 2f, 520f, textPaint(44f, C.primary, true))
        c.drawText(fmtDate(System.currentTimeMillis()), w / 2f, 590f, textPaint(38f, muted, false))
        // today's focus, big
        val big = fmtDur(todayMin * 60000L)
        val bigSize = if (big.length > 12) 118f else 150f
        c.drawText(big, w / 2f, 800f, textPaint(bigSize, white, true))
        c.drawText(L.t("FOCUS TODAY", "আজকের ফোকাস"), w / 2f, 875f, textPaint(40f, muted, true))
        // divider
        val line = Paint()
        line.color = Color.parseColor("#1F4A38")
        line.strokeWidth = 3f
        c.drawLine(w * 0.18f, 960f, w * 0.82f, 960f, line)
        // streak + completed locks, side by side
        val numPaint = textPaint(96f, C.mint, true)
        val labPaint = textPaint(36f, muted, false)
        c.drawText(L.num(streak.toString()), w * 0.28f, 1100f, numPaint)
        c.drawText(L.t("DAY STREAK", "দিনের স্ট্রিক"), w * 0.28f, 1160f, labPaint)
        c.drawText(L.num(completed.toString()), w * 0.72f, 1100f, numPaint)
        c.drawText(L.t("LOCKS DONE", "সম্পন্ন লক"), w * 0.72f, 1160f, labPaint)
        // footer
        c.drawText(
            L.t("Focus is strength.", "ফোকাসই শক্তি।"),
            w / 2f, 1290f, textPaint(36f, C.primary, false)
        )
        return bmp
    }
}
