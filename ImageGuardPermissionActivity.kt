package com.example.powerlocks

import android.app.Activity
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
 */
class ImageGuardPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startForegroundService(
                Intent(this, ImageGuardService::class.java)
                    .setAction(ImageGuardService.ACTION_PREPARE)
            )
        } catch (e: Exception) {
            Log.e(TAG, "image guard prepare failed", e)
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        } catch (e: Exception) {
            Log.e(TAG, "capture intent failed", e)
            setResult(RESULT_CANCELED)
            finish()
        }
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
        } else {
            // permission denied: stop the service the PREPARE step started
            try {
                stopService(Intent(this, ImageGuardService::class.java))
            } catch (_: Exception) {
            }
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
