package com.powerlock.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device admin receiver: while the app is an active device admin, Android
 * blocks uninstalling it — the user must first deliberately deactivate the
 * admin in system Settings. That two-step friction stops impulsive
 * uninstalls during a lock. No policies are requested (no password, no
 * lock); the receiver exists only so uninstall is blocked while active.
 *
 * Honest limit: the app cannot stop the user from deactivating the admin —
 * Android always allows that. It only turns uninstalling into a deliberate
 * action instead of a one-tap impulse.
 */
class LockDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisabled(context: Context, intent: Intent) {
        // the user deactivated the admin in system Settings: log when, so
        // the Home warning + Settings row can show it
        try {
            Store.settings(context).edit()
                .putLong("admin_off_at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "admin off log failed", e)
        }
        super.onDisabled(context, intent)
    }
}

object DeviceAdmin {
    fun component(ctx: Context): ComponentName =
        ComponentName(ctx, LockDeviceAdmin::class.java)

    fun isActive(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(component(ctx))
        } catch (e: Exception) {
            false
        }
    }

    /** True once the admin has ever been observed active — used to notice a deactivation. */
    fun wasActive(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("admin_was_on", false)

    /** Call when the admin is observed active (Home tick / Settings build). */
    fun noteActive(ctx: Context) {
        val p = Store.settings(ctx)
        if (!p.getBoolean("admin_was_on", false) || p.getLong("admin_off_at", 0L) != 0L) {
            // re-enabled: the old deactivation is resolved
            p.edit().putBoolean("admin_was_on", true).remove("admin_off_at").apply()
        }
    }

    /** When the admin was last deactivated (0 = never). */
    fun deactivatedAt(ctx: Context): Long =
        Store.settings(ctx).getLong("admin_off_at", 0L)

    /** System prompt that asks the user to enable the device admin. */
    fun enableIntent(ctx: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                L.t(
                    "While active, PowerLock 2 cannot be uninstalled — you must first turn the admin off in system Settings. This stops impulsive uninstalls during a lock.",
                    "চালু থাকলে PowerLock 2 আনইনস্টল করা যাবে না — আগে সিস্টেম সেটিংসে অ্যাডমিন বন্ধ করতে হবে। লক চলাকালীন হঠাৎ আনইনস্টল ঠেকাতেই এটা।"
                )
            )
        }
    }
}
