package com.powerlock.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/**
 * Home screen widget: shows the lock status — remaining time while locked,
 * "OFF" when idle. Tapping it opens the app. The widget never finalizes a
 * session itself (it only reads the prefs): the app and services refresh it
 * on lock start/end, from the 1 s ticker (throttled), the keep-alive
 * heartbeat (60 s) and the Watchdog (15 min), so the countdown stays fresh
 * without a 30-minute update period.
 */
class LockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        refresh(context)
    }

    companion object {
        /** Refresh every widget instance (no-op when none is placed). */
        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, LockWidget::class.java))
                for (id in ids) updateOne(ctx, mgr, id)
            } catch (e: Exception) {
                Log.e(TAG, "widget refresh failed", e)
            }
        }

        private fun updateOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            try {
                L.load(ctx)
                // raw prefs only — never finalize here (Store already refreshes
                // the widget when a session actually ends)
                val p = Store.lock(ctx)
                val now = System.currentTimeMillis()
                val end = p.getLong("end", 0)
                val active = p.getBoolean("active", false) && now < end
                val views = RemoteViews(ctx.packageName, R.layout.lock_widget)
                if (active) {
                    val mode = Store.unlockMode(ctx)
                    val target = Store.unlockTarget(ctx)
                    if (mode == UNLOCK_LOC && target != null) {
                        views.setTextViewText(R.id.widget_status, L.t("On the way", "যাত্রায়"))
                        views.setTextViewText(R.id.widget_sub, target.name)
                    } else {
                        views.setTextViewText(R.id.widget_status, L.t("Left ", "বাকি ") + fmtHms(end - now))
                        views.setTextViewText(R.id.widget_sub, L.t("Ends at ", "শেষ হবে ") + fmtClock(end))
                    }
                } else {
                    views.setTextViewText(R.id.widget_status, L.t("OFF", "বন্ধ"))
                    views.setTextViewText(R.id.widget_sub, L.t("Tap to open", "খুলতে চাপুন"))
                }
                val pi = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.widget_root, pi)
                mgr.updateAppWidget(id, views)
            } catch (e: Exception) {
                Log.e(TAG, "widget update failed", e)
            }
        }
    }
}
