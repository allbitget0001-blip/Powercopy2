package com.example.powerlocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Foreground service: keeps the process alive and shows the countdown notification. */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { onFinished() }

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
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setColor(Color.parseColor("#16C784"))
            .setOngoing(true)
            .setContentIntent(openApp())

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
            val n = Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setColor(Color.parseColor("#16C784"))
                .setContentTitle(L.t("Protection finished", "প্রটেকশন শেষ"))
                .setContentText(L.t("You can use the power menu now.", "এখন পাওয়ার মেনু ব্যবহার করতে পারবেন।"))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            nm.notify(2, n)
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
