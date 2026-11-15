package com.example.powerlocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { onFinished() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("powerlock", "PowerLock", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("powerlock_done", "লক শেষ", NotificationManager.IMPORTANCE_HIGH)
        )
        refresh()
        return START_STICKY
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

    private fun refresh() {
        handler.removeCallbacks(finishTask)
        val end = getSharedPreferences("lock", MODE_PRIVATE).getLong("end", 0)
        val now = System.currentTimeMillis()

        val b = Notification.Builder(this, "powerlock")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setContentIntent(openApp())

        if (end > now) {
            b.setContentTitle("লক চালু আছে")
                .setContentText("বাকি সময়")
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(end)
            handler.postDelayed(finishTask, end - now)
        } else {
            b.setContentTitle("PowerLock প্রস্তুত")
                .setContentText("লক বন্ধ আছে")
        }
        startForeground(1, b.build())
    }

    private fun onFinished() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, "powerlock_done")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("লক শেষ")
            .setContentText("এখন পাওয়ার মেনু ব্যবহার করতে পারবেন")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(2, n)
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
