package com.powerlock.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin receiver for the uninstall block. It does nothing on its own:
 * the protection comes from Android itself — while this admin is active, the
 * app cannot be uninstalled until the admin is removed first (in system
 * Settings, or from the in-app button when no lock is running).
 *
 * Note: the Device Admin API is deprecated, but it still works for this
 * purpose on current Android versions. The implementation is deliberately
 * minimal: no policies are requested, no lock/wipe is ever used.
 */
class DeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.e(TAG, "device admin enabled")
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.e(TAG, "device admin disabled")
        super.onDisabled(context, intent)
    }
}

object UninstallBlock {

    private fun component(ctx: Context): ComponentName = ComponentName(ctx, DeviceAdmin::class.java)

    private fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True while the app is an active device admin (uninstall is blocked). */
    fun isActive(ctx: Context): Boolean {
        return try {
            dpm(ctx).isAdminActive(component(ctx))
        } catch (e: Exception) {
            Log.e(TAG, "device admin check failed", e)
            false
        }
    }

    /** The system flow that asks the user to make the app a device admin. */
    fun enableIntent(ctx: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                L.t(
                    "PowerLock 2 will prevent its own uninstall while this is active. Remove the admin in Settings to uninstall.",
                    "এটি চালু থাকলে PowerLock 2 নিজের আনইনস্টল আটকাবে। আনইনস্টল করতে Settings থেকে অ্যাডমিন সরাতে হবে।"
                )
            )
    }

    /** Removes the app as device admin. Returns false when it failed. */
    fun remove(ctx: Context): Boolean {
        return try {
            dpm(ctx).removeActiveAdmin(component(ctx))
            true
        } catch (e: Exception) {
            Log.e(TAG, "device admin remove failed", e)
            false
        }
    }
}
