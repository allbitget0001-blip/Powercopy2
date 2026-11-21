package com.example.powerlocks

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.FloatBuffer
import javax.net.ssl.HttpsURLConnection

/** Request code for the screen-capture permission activity. */
const val REQ_IMAGE_GUARD = 1041

/**
 * The on-device NSFW model (OwenElliott/image-safety-classifier-l, MIT):
 * a SwiftFormer-L image classifier, 28.5M parameters, 3 classes [NSFL, NSFW,
 * SFW] — the most powerful of the family (98.20% accuracy). The model file
 * (~106 MB) is downloaded once, on first enable, and stored under filesDir
 * — it is never bundled in the APK, and no image ever leaves the phone:
 * every frame is classified on the device and then discarded.
 */
/** Result of a model download: [ok], plus a short English [reason] on failure. */
data class ModelDownloadResult(val ok: Boolean, val reason: String = "")

object ImageGuardModel {
    const val MODEL_URL =
        "https://huggingface.co/OwenElliott/image-safety-classifier-l/resolve/main/onnx/image-safety-classifier-l.onnx"

    /** A complete download is ~106 MB; anything smaller is a failed download. */
    const val MIN_BYTES = 50_000_000L

    fun dir(ctx: Context): File = File(ctx.filesDir, "imageguard")

    /** The file name carries the model size, so an older smaller model left
     * on the phone is never mistaken for this one. */
    fun file(ctx: Context): File = File(dir(ctx), "model-l.onnx")

    fun ready(ctx: Context): Boolean = try {
        val f = file(ctx)
        f.exists() && f.length() > MIN_BYTES
    } catch (_: Exception) {
        false
    }

    /**
     * Downloads the model on the calling thread (call off the main thread).
     * [onProgress] gets 0..100. Downloads to a .tmp file and renames only
     * when complete, so a partial download can never look like a model.
     * Tries twice: a dropped mobile connection is common on a ~106 MB
     * download. A 4xx response is not retried — it means the URL is wrong
     * (that is what broke the first build: the file was not at the URL).
     * The result carries a short English reason on failure, so the user
     * sees WHY it failed instead of only "try again".
     */
    fun download(ctx: Context, onProgress: (Int) -> Unit): ModelDownloadResult {
        var last: ModelDownloadResult = ModelDownloadResult(false, "error")
        repeat(2) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(2000)
                } catch (_: Exception) {
                }
                onProgress(0)
            }
            last = downloadOnce(ctx, onProgress)
            if (last.ok) return last
            if (last.reason.startsWith("HTTP 4")) return last
        }
        return last
    }

    /** One download attempt. */
    private fun downloadOnce(ctx: Context, onProgress: (Int) -> Unit): ModelDownloadResult {
        return try {
            val d = dir(ctx)
            if (!d.exists()) d.mkdirs()
            // drop the old small model if it is still on the phone
            try {
                val old = File(d, "model.onnx")
                if (old.exists()) old.delete()
            } catch (_: Exception) {
            }
            val tmp = File(d, "model-l.onnx.tmp")
            if (tmp.exists()) tmp.delete()
            val conn = URL(MODEL_URL).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            if (conn.responseCode !in 200..299) {
                val code = conn.responseCode
                conn.disconnect()
                return ModelDownloadResult(false, "HTTP $code")
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (tmp.length() <= MIN_BYTES) {
                tmp.delete()
                return ModelDownloadResult(false, "incomplete")
            }
            if (!tmp.renameTo(file(ctx))) {
                tmp.delete()
                return ModelDownloadResult(false, "save failed")
            }
            Store.settings(ctx).edit().putBoolean("ig_model_err", false).apply()
            ModelDownloadResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "model download failed", e)
            try {
                File(dir(ctx), "model-l.onnx.tmp").delete()
            } catch (_: Exception) {
            }
            ModelDownloadResult(false, reasonFor(e))
        }
    }

    /** Short plain-English reason for a download exception. */
    private fun reasonFor(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException -> "no internet"
        is SocketTimeoutException -> "timeout"
        is IOException -> "network error"
        else -> "error"
    }
}

/**
 * ONNX Runtime wrapper for the image-safety model. Input tensor "image",
 * shape [1, 3, 224, 224], float32, RGB pixels in 0-255 (normalisation and
 * softmax are baked into the graph). Output is float[1][3] probabilities
 * in class order [NSFL, NSFW, SFW] — index 1 is the NSFW score.
 */
private class NsfwModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    companion object {
        fun load(ctx: Context): NsfwModel? {
            val file = ImageGuardModel.file(ctx)
            if (!file.exists() || file.length() <= ImageGuardModel.MIN_BYTES) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                opts.setIntraOpNumThreads(4)
                val session = env.createSession(file.absolutePath, opts)
                try {
                    opts.close()
                } catch (_: Exception) {
                }
                NsfwModel(env, session)
            } catch (e: Exception) {
                Log.e(TAG, "NsfwModel load failed", e)
                null
            }
        }
    }

    /** NSFW probability (0..1), or null if inference failed. [bmp224] must be 224x224. */
    fun nsfwScore(bmp224: Bitmap): Float? {
        val pixels = IntArray(224 * 224)
        bmp224.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        // CHW order: all reds, then greens, then blues, as 0-255 floats
        val buf = FloatBuffer.allocate(3 * 224 * 224)
        for (p in pixels) buf.put(((p shr 16) and 0xFF).toFloat())
        for (p in pixels) buf.put(((p shr 8) and 0xFF).toFloat())
        for (p in pixels) buf.put((p and 0xFF).toFloat())
        buf.rewind()
        return try {
            OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, 224, 224)).use { input ->
                session.run(mapOf("image" to input)).use { result ->
                    val out = result[0].value as? Array<*>
                    val row = out?.get(0) as? FloatArray
                    row?.getOrNull(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NsfwModel inference failed", e)
            null
        }
    }

    fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Image guard: while a lock runs, captures the screen with MediaProjection,
 * classifies a frame every ~1s with the on-device ONNX model, and when the
 * NSFW score passes the sensitivity threshold covers the screen and sends
 * the user Home. Nothing is saved or sent anywhere — frames are classified
 * in memory and recycled at once.
 *
 * The service only runs while the guard is on AND a lock is protecting
 * (see [sync]); the sampling loop itself also checks the lock, so no frame
 * is ever captured outside a lock. Screen-capture permission is granted
 * through [ImageGuardPermissionActivity] and is lost on reboot — in that
 * case the user gets a notification asking to allow it again.
 */
class ImageGuardService : Service() {

    companion object {
        const val ACTION_PREPARE = "com.example.powerlocks.IG_PREPARE"
        const val ACTION_SET_PROJECTION = "com.example.powerlocks.IG_SET_PROJECTION"
        const val ACTION_START = "com.example.powerlocks.IG_START"

        private const val NOTIF_ID = 11
        private const val NOTIF_REGRANT_ID = 12
        private const val NOTIF_MODEL_ERR_ID = 13
        private const val CHANNEL_ID = "powerlock_image"
        private const val SCAN_INTERVAL_MS = 1000L
        private const val IDLE_INTERVAL_MS = 3000L
        private const val COVER_HIDE_MS = 1500L
        private const val DETECT_COOLDOWN_MS = 5000L
        private const val REGRANT_NOTIFY_MIN_MS = 30 * 60_000L

        @Volatile
        var isRunning = false
            private set

        /** The screen-capture grant from the permission activity. Static on
         * purpose: it survives a service restart, but not a reboot or a
         * process kill — then the user must allow it again. */
        @Volatile
        private var projectionResultCode = Activity.RESULT_CANCELED

        @Volatile
        private var projectionData: Intent? = null

        @Volatile
        private var lastRegrantNotify = 0L

        fun hasProjection(): Boolean = projectionData != null

        /**
         * Starts the service iff the guard is on, a lock is protecting, the
         * model is downloaded and the screen-capture grant exists; otherwise
         * stops it. Cheap and idempotent — safe to call every second from
         * the MainActivity ticker and from the accessibility service.
         * Starting a foreground service from the background can throw; that
         * is caught here and retried on the next call, never a crash.
         */
        @Synchronized
        fun sync(ctx: Context) {
            try {
                val on = Extra.imageGuardOn(ctx)
                val protecting = Store.isActive(ctx) || Store.isLocProtecting(ctx)
                val modelOk = ImageGuardModel.ready(ctx)
                val want = on && protecting && modelOk && projectionData != null
                if (want && !isRunning) {
                    ctx.startForegroundService(
                        Intent(ctx, ImageGuardService::class.java).setAction(ACTION_START)
                    )
                } else if (!want && isRunning) {
                    ctx.stopService(Intent(ctx, ImageGuardService::class.java))
                }
                // the guard is wanted but the capture grant is gone (reboot /
                // revoked): remind the user to allow it again, at most every
                // 30 minutes
                if (on && protecting && modelOk && projectionData == null) {
                    maybeNotifyRegrant(ctx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ImageGuard sync failed", e)
            }
        }

        private fun ensureChannel(ctx: Context) {
            try {
                if (Build.VERSION.SDK_INT < 26) return
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Image guard", NotificationManager.IMPORTANCE_LOW)
                )
            } catch (e: Exception) {
                Log.e(TAG, "image guard channel failed", e)
            }
        }

        fun notifyRegrantNow(ctx: Context) {
            try {
                ensureChannel(ctx)
                L.load(ctx)
                val pi = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
                val b = Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle(L.t("Image guard needs permission again", "ছবি সুরক্ষার জন্য আবার অনুমতি দিন"))
                    .setContentText(L.t("Screen-capture permission ended — open PowerLock to allow it again", "স্ক্রিন দেখার অনুমতি শেষ হয়ে গেছে — আবার দিতে PowerLock খুলুন"))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_REGRANT_ID, b.build())
                lastRegrantNotify = SystemClock.uptimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "regrant notify failed", e)
            }
        }

        private fun maybeNotifyRegrant(ctx: Context) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRegrantNotify < REGRANT_NOTIFY_MIN_MS) return
            notifyRegrantNow(ctx)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var inferThread: HandlerThread? = null
    private var inferHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @Volatile
    private var inferBusy = false

    @Volatile
    private var model: NsfwModel? = null

    @Volatile
    private var modelFailed = false

    @Volatile
    private var destroyed = false

    private var foreground = false
    private var lastDetectAt = 0L
    private var idleTicks = 0
    private var cover: View? = null
    private var coverIcon: IconView? = null
    private var coverAttached = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // the grant was revoked (e.g. from the status bar): forget it,
            // tell the user, and stop — sync() will not restart us without
            // a fresh grant
            try {
                projectionData = null
                releaseCapture()
                notifyRegrantNow(this@ImageGuardService)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "projection callback failed", e)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            var nextDelay = SCAN_INTERVAL_MS
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastDetectAt < DETECT_COOLDOWN_MS) {
                    // cooling down after a detection: the cover itself must
                    // never be scanned
                } else if (!protecting()) {
                    releaseCapture()
                    nextDelay = IDLE_INTERVAL_MS
                    idleTicks++
                    // idle for ~30s: let sync() stop us if we are no longer
                    // wanted (lock ended while the app was closed)
                    if (idleTicks >= 10) {
                        idleTicks = 0
                        sync(this@ImageGuardService)
                    }
                } else {
                    idleTicks = 0
                    if (mediaProjection == null) initProjection()
                    if (mediaProjection == null) {
                        nextDelay = IDLE_INTERVAL_MS
                    } else {
                        if (virtualDisplay == null) startCapture()
                        scanFrame()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "image guard tick failed", e)
            }
            if (!destroyed) handler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            L.load(this)
        } catch (_: Exception) {
        }
        isRunning = true
        val t = HandlerThread("ig-infer")
        t.start()
        inferThread = t
        inferHandler = Handler(t.looper)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_PREPARE -> goForeground()
                ACTION_SET_PROJECTION -> {
                    goForeground()
                    val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
                    @Suppress("DEPRECATION")
                    val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra("data", Intent::class.java)
                    } else {
                        intent.getParcelableExtra("data")
                    }
                    if (code == Activity.RESULT_OK && data != null) {
                        projectionResultCode = code
                        projectionData = data
                        initProjection()
                        startSampling()
                    }
                }
                else -> { // ACTION_START or a system restart (null intent)
                    if (projectionData == null) {
                        // no grant (e.g. process was killed): stop, and let
                        // sync() post the re-grant reminder instead
                        stopSelf()
                    } else {
                        goForeground()
                        initProjection()
                        startSampling()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageGuard start failed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        try {
            handler.removeCallbacks(tick)
            handler.removeCallbacksAndMessages(null)
            releaseCapture()
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                mediaProjection?.stop()
            } catch (_: Exception) {
            }
            mediaProjection = null
            hideCover()
            try {
                model?.close()
            } catch (_: Exception) {
            }
            model = null
            try {
                inferThread?.quitSafely()
            } catch (_: Exception) {
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "ImageGuard destroy failed", e)
        }
        isRunning = false
        foreground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun protecting(): Boolean = Store.isActive(this) || Store.isLocProtecting(this)

    /** Must run BEFORE getMediaProjection() on Android 14 (target 34). */
    private fun goForeground() {
        if (foreground) return
        foreground = true
        ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(L.t("Image guard active", "ছবি সুরক্ষা সক্রিয়"))
            .setContentText(L.t("Watching the screen while the lock runs", "লক চলাকালীন স্ক্রিন পর্যবেক্ষণ চলছে"))
            .setOngoing(true)
            .setContentIntent(pi)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, b.build())
        }
    }

    private fun initProjection() {
        if (mediaProjection != null) return
        val data = projectionData ?: return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(projectionResultCode, data)
            proj.registerCallback(projectionCallback, handler)
            mediaProjection = proj
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            projectionData = null
            notifyRegrantNow(this)
            stopSelf()
        }
    }

    private fun startSampling() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun startCapture() {
        val proj = mediaProjection ?: return
        try {
            val metrics = resources.displayMetrics
            val w = 540
            val h = (540f * metrics.heightPixels / metrics.widthPixels).toInt().coerceIn(240, 1200)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val vd = proj.createVirtualDisplay(
                "ImageGuard", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
            )
            imageReader = reader
            virtualDisplay = vd
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)
            releaseCapture()
        }
    }

    private fun releaseCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
    }

    /** Latest frame as a Bitmap, or null when no frame is available yet. */
    private fun frameBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val full = Bitmap.createBitmap(
                w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buf)
            if (rowPadding == 0) return full
            val cropped = Bitmap.createBitmap(full, 0, 0, w, h)
            try {
                full.recycle()
            } catch (_: Exception) {
            }
            return cropped
        } catch (e: Exception) {
            Log.e(TAG, "frameBitmap failed", e)
            return null
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun scanFrame() {
        val bmp = frameBitmap() ?: return
        val small = try {
            Bitmap.createScaledBitmap(bmp, 224, 224, true)
        } catch (e: Exception) {
            null
        }
        try {
            bmp.recycle()
        } catch (_: Exception) {
        }
        if (small == null) return
        if (inferBusy) {
            try {
                small.recycle()
            } catch (_: Exception) {
            }
            return
        }
        val h = inferHandler
        if (h == null) {
            try {
                small.recycle()
            } catch (_: Exception) {
            }
            return
        }
        inferBusy = true
        h.post { runInference(small) }
    }

    /** Runs on the inference thread. */
    private fun runInference(small: Bitmap) {
        try {
            val m = getModel() ?: return
            val score = m.nsfwScore(small)
            if (score != null && score >= Extra.igThreshold(this)) {
                handler.post { onDetected(score) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "inference failed", e)
        } finally {
            try {
                small.recycle()
            } catch (_: Exception) {
            }
            inferBusy = false
        }
    }

    /** Lazy model load, on the inference thread. */
    private fun getModel(): NsfwModel? {
        model?.let { return it }
        if (modelFailed) return null
        val m = NsfwModel.load(this)
        if (m == null) {
            modelFailed = true
            Store.settings(this).edit().putBoolean("ig_model_err", true).apply()
            handler.post { notifyModelError() }
            return null
        }
        Store.settings(this).edit().putBoolean("ig_model_err", false).apply()
        model = m
        return m
    }

    private fun notifyModelError() {
        try {
            ensureChannel(this)
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val b = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(L.t("Image guard model failed to load", "ছবি সুরক্ষার মডেল লোড হয়নি"))
                .setContentText(L.t("Turn image guard off and on again to re-download the model", "মডেল আবার ডাউনলোড করতে ছবি সুরক্ষা বন্ধ করে আবার চালু করুন"))
                .setContentIntent(pi)
                .setAutoCancel(true)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_MODEL_ERR_ID, b.build())
        } catch (e: Exception) {
            Log.e(TAG, "model error notify failed", e)
        }
    }

    // ---------- detection response ----------

    private fun onDetected(score: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDetectAt < DETECT_COOLDOWN_MS) return
        lastDetectAt = now
        Log.i(TAG, "image guard: NSFW detected score=$score")
        try {
            Extra.logAttempt(this, "image")
        } catch (_: Exception) {
        }
        try {
            showCover()
        } catch (e: Exception) {
            Log.e(TAG, "showCover failed", e)
        }
        goHome()
        handler.postDelayed({ hideCover() }, COVER_HIDE_MS)
    }

    private fun goHome() {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "goHome failed", e)
        }
    }

    private fun ensureCover(): View {
        cover?.let { return it }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(250, 8, 16, 12))
        root.isClickable = true // touches must not reach the app underneath
        root.setPadding(dp(32), dp(32), dp(32), dp(32))
        val icon = IconView(this, Ic.SHIELD, C.primary)
        root.addView(icon, lp(dp(84), dp(84)))
        coverIcon = icon
        val title = tv(L.t("Inappropriate image detected", "অনুপযুক্ত ছবি শনাক্ত হয়েছে"), 22f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv(L.t("The screen is covered while the lock runs", "লক চলাকালীন স্ক্রিন ঢেকে দেওয়া হয়েছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        val btn = primaryBtn(L.t("Back to Home", "হোমে ফিরুন"))
        btn.setOnClickListener {
            hideCover()
            goHome()
        }
        root.addView(btn, lp(MATCH, dp(52), t = dp(28)))
        cover = root
        return root
    }

    private fun showCover() {
        if (coverAttached) return
        try {
            val v = ensureCover()
            v.alpha = 0f
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            coverAttached = true
            v.animate().alpha(1f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            coverIcon?.let { ic ->
                ic.scaleX = 0.6f
                ic.scaleY = 0.6f
                ic.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
        } catch (ex: Exception) {
            // overlay permission missing: the cover cannot show, but the
            // Home intent in onDetected still takes the user away
            Log.e(TAG, "showCover failed (overlay permission?)", ex)
            coverAttached = false
        }
    }

    private fun hideCover() {
        if (!coverAttached) return
        coverAttached = false
        try {
            val v = cover ?: return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideCover failed", ex)
        }
    }
}
