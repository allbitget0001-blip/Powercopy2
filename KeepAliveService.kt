package com.example.powerlocks

import android.graphics.drawable.Icon
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Foreground service: keeps the process alive and shows the countdown notification. */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { onFinished() }

    private val smallIcon: Icon by lazy { buildStatusIcon() }
    private val largeIcon: Bitmap? by lazy { buildLogo() }

    /** Status-bar icon: a shield with a lock cut-out (single colour, the system tints it). */
    private fun buildStatusIcon(): Icon {
        val s = 96
        val u = s / 24f
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.moveTo(12f * u, 2f * u)
        path.lineTo(20f * u, 5f * u)
        path.lineTo(20f * u, 11.5f * u)
        path.cubicTo(20f * u, 16.5f * u, 16.5f * u, 20f * u, 12f * u, 22f * u)
        path.cubicTo(7.5f * u, 20f * u, 4f * u, 16.5f * u, 4f * u, 11.5f * u)
        path.lineTo(4f * u, 5f * u)
        path.close()
        // lock body (cut out)
        path.addRect(9f * u, 11f * u, 15f * u, 16.2f * u, Path.Direction.CW)
        // lock shackle (cut out)
        path.moveTo(9.8f * u, 11f * u)
        path.lineTo(9.8f * u, 9.4f * u)
        path.arcTo(RectF(9.8f * u, 7.2f * u, 14.2f * u, 11.6f * u), 180f, 180f)
        path.lineTo(14.2f * u, 11f * u)
        path.lineTo(13f * u, 11f * u)
        path.lineTo(13f * u, 9.4f * u)
        path.arcTo(RectF(11f * u, 8.4f * u, 13f * u, 10.4f * u), 0f, -180f)
        path.lineTo(11f * u, 11f * u)
        path.close()
        c.drawPath(path, p)
        return Icon.createWithBitmap(bmp)
    }

    /**
     * Large icon: the app logo (milk white tile, green shield, white padlock), drawn here directly.
     * It does not depend on the launcher icon or on any icon cache of the phone.
     */
    private fun buildLogo(): Bitmap? {
        return try {
            val size = dp(64)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val milk = Color.parseColor("#F6F3EA")
            val green = Color.parseColor("#1E9E68")

            val tile = Paint(Paint.ANTI_ALIAS_FLAG)
            tile.color = milk
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.24f, size * 0.24f, tile)

            // same shapes as the launcher icon (108 x 108 viewport), zoomed on the shield
            c.save()
            c.scale(size / 72f, size / 72f)
            c.translate(-18f, -18f)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fill.style = Paint.Style.FILL
            fill.color = green
            val shield = Path()
            shield.moveTo(54f, 28f)
            shield.lineTo(74f, 36f)
            shield.lineTo(74f, 53f)
            shield.cubicTo(74f, 67f, 64f, 74.5f, 54f, 80f)
            shield.cubicTo(44f, 74.5f, 34f, 67f, 34f, 53f)
            shield.lineTo(34f, 36f)
            shield.close()
            c.drawPath(shield, fill)

            fill.color = milk
            c.drawRoundRect(RectF(44f, 53f, 64f, 67.5f), 2.5f, 2.5f, fill)

            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeWidth = 2.6f
            line.strokeCap = Paint.Cap.ROUND
            line.color = milk
            val shackle = Path()
            shackle.moveTo(48.5f, 53f)
            shackle.lineTo(48.5f, 49.5f)
            shackle.arcTo(RectF(48.5f, 44f, 59.5f, 55f), 180f, 180f)
            shackle.lineTo(59.5f, 53f)
            c.drawPath(shackle, line)

            fill.color = green
            c.drawCircle(54f, 58.5f, 1.8f, fill)
            c.restore()
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "logo failed", e)
            null
        }
    }

    /** MIUI / HyperOS hint: use the PowerLock logo as the notification's app icon (ignored by other phones). */
    private fun addLogoExtras(b: Notification.Builder) {
        try {
            val bmp = largeIcon ?: return
            val ex = Bundle()
            ex.putParcelable("miui.appIcon", Icon.createWithBitmap(bmp))
            b.addExtras(ex)
        } catch (e: Exception) {
            Log.e(TAG, "logo extras failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            L.load(this)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("powerlock", "PowerLock", NotificationManager.IMPORTANCE_LOW)
            )
            val done = NotificationChannel("powerlock_done", "Session finished", NotificationManager.IMPORTANCE_HIGH)
            done.enableVibration(true)
            nm.createNotificationChannel(done)
            nm.createNotificationChannel(
                NotificationChannel("powerlock_done_quiet", "Session finished (quiet)", NotificationManager.IMPORTANCE_LOW)
            )
            refresh()
            // location lock no longer uses sessions: end any old location session, the flag takes over
            if (Store.isLocationSession(this)) Store.stopSession(this, force = true)
            PlaceWatcher.sync(this) // resume location watching after a restart/reboot
            PlaceWatcher.checkNow(this) // already at a saved place? lock right away
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveService start failed", e)
        }
        return START_STICKY
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun refresh() {
        handler.removeCallbacks(finishTask)
        Store.finalizeIfExpired(this)
        val p = Store.lock(this)
        val end = p.getLong("end", 0)
        val now = System.currentTimeMillis()
        val active = p.getBoolean("active", false) && end > now

        val b = Notification.Builder(this, "powerlock")
            .setSmallIcon(smallIcon)
            .setLargeIcon(largeIcon)
            .setColor(Color.parseColor("#16C784"))
            .setOngoing(true)
            .setContentIntent(openApp())
        addLogoExtras(b)

        if (active) {
            b.setContentTitle(L.t("Protection active", "প্রটেকশন সক্রিয়"))
                .setContentText(L.t("Ends at ", "শেষ হবে ") + fmtClock(end))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(end)
            handler.postDelayed(finishTask, end - now)
        } else {
            b.setContentTitle(L.t("PowerLock is ready", "PowerLock প্রস্তুত"))
                .setContentText(L.t("Protection is off", "প্রটেকশন বন্ধ আছে"))
        }
        startForeground(1, b.build())
    }

    private fun onFinished() {
        try {
            L.load(this)
            if (Store.isActive(this)) { // clock changed, not finished yet
                refresh()
                return
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = if (Store.soundVib(this)) "powerlock_done" else "powerlock_done_quiet"
            val nb = Notification.Builder(this, channel)
                .setSmallIcon(smallIcon)
                .setLargeIcon(largeIcon)
                .setColor(Color.parseColor("#16C784"))
                .setContentTitle(L.t("Protection finished", "প্রটেকশন শেষ"))
                .setContentText(L.t("You can use the power menu now.", "এখন পাওয়ার মেনু ব্যবহার করতে পারবেন।"))
                .setAutoCancel(true)
                .setContentIntent(openApp())
            addLogoExtras(nb)
            nm.notify(2, nb.build())
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "onFinished failed", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
