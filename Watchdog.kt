package com.powerlock.app

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Safety net: every 15 minutes the system wakes this worker and it restarts the
 * foreground service if protection should be running. WorkManager re-schedules
 * itself even after the app process is killed, so this survives a swipe from
 * Recents and a dismissed notification — the two cases an alarm alone cannot
 * always survive on Vivo phones.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            val c = applicationContext
            L.load(c)
            if (Store.isActive(c) || Store.isLocProtecting(c)) {
                // startForegroundService from a background worker throws on Android 12+,
                // so ask for an alarm-clock restart instead: it needs no permission and
                // the system lets the alarm start the foreground service when it fires
                KeepAliveService.restartSoon(c, 1000)
                PlaceWatcher.checkNow(c)
                PlaceWatcher.checkUnlockNow(c) // location-unlock session? check arrival
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog failed", e)
            Result.retry()
        }
    }
}

object Watchdog {
    private const val NAME = "powerlock-watchdog"

    /** Enqueue once; KEEP means calling this again never creates a second worker. */
    fun enqueue(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog enqueue failed", e)
        }
    }
}
