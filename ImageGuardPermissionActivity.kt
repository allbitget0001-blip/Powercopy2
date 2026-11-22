package com.example.powerlocks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent activity that asks for screen-capture permission and hands the
 * result to [ImageGuardService]. The service is started (foreground, type
 * mediaProjection) BEFORE the permission dialog appears: on Android 14 the
 * foreground service must already be running when getMediaProjection() is
 * called. If the user denies, the service is stopped again and the caller
 * gets RESULT_CANCELED.
 *
 * Before the system dialog, a warning explains the scope choice: Android 14+
 * lets the user share a single app instead of the whole screen, and that
 * silently breaks the guard — it would only see that one app, while the
 * lockdown lifts as soon as ANY grant exists, leaving every other app
 * unwatched. The user must pick "Share entire screen".
 */
class ImageGuardPermissionActivity : Activity() {

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recreated while the system dialog was up (e.g. rotation): the
        // result is still on its way, so don't warn or ask twice
        if (savedInstanceState?.getBoolean("launched") == true) return
        try {
            startForegroundService(
                Intent(this, ImageGuardService::class.java)
                    .setAction(ImageGuardService.ACTION_PREPARE)
            )
        } catch (e: Exception) {
            Log.e(TAG, "image guard prepare failed", e)
        }
        AlertDialog.Builder(this)
            .setTitle(L.t("Choose the whole screen", "পুরো স্ক্রিন বাছুন"))
            .setMessage(
                L.t(
                    "On the next screen, choose 'Share entire screen'. If you choose 'Share one app', the guard will only watch that one app — it cannot catch bad images in any other app.",
                    "পরের স্ক্রিনে 'Share entire screen' বাছুন। 'Share one app' বাছলে গার্ড শুধু ওই একটা অ্যাপই দেখতে পাবে — বাকি কোনো অ্যাপে খারাপ ছবি ধরতে পারবে না।"
                )
            )
            .setPositiveButton(L.t("Got it", "বুঝেছি")) { _, _ -> launchCaptureIntent() }
            .setNegativeButton(L.t("Cancel", "বাতিল")) { _, _ -> deny() }
            .setOnCancelListener { deny() }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("launched", launched)
    }

    private fun launchCaptureIntent() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
            launched = true
        } catch (e: Exception) {
            Log.e(TAG, "capture intent failed", e)
            deny()
        }
    }

    /** Permission not granted (denied, cancelled, or the intent failed):
     * stop the service the PREPARE step started. */
    private fun deny() {
        try {
            stopService(Intent(this, ImageGuardService::class.java))
        } catch (_: Exception) {
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode == RESULT_OK && data != null) {
            try {
                startService(
                    Intent(this, ImageGuardService::class.java)
                        .setAction(ImageGuardService.ACTION_SET_PROJECTION)
                        .putExtra("code", resultCode)
                        .putExtra("data", data)
                )
            } catch (e: Exception) {
                Log.e(TAG, "set projection failed", e)
            }
            setResult(RESULT_OK)
            finish()
        } else {
            // permission denied: stop the service the PREPARE step started
            deny()
        }
    }
}
