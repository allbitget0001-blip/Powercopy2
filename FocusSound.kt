package com.powerlock.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Focus sounds: calming noise generated in code (no downloads, no audio assets).
 * White / Brown / Rain, played through AudioTracks.
 *
 * Two ways to listen, never at the same time:
 *  - the lock sound: one chosen sound, auto-played while a lock runs
 *    ([sync]/[startExplicit]/[stop]);
 *  - the mixer: the user plays 2–3 sounds together with a per-sound volume,
 *    started explicitly from the Settings sound dialog ([startMixSound],
 *    [setMixVolume], [stopMixSound]). The mixer is manual-only: it plays
 *    whether or not a lock runs, and a lock ending never stops it.
 * One shared audio-focus request covers all streams; losing focus halts
 * everything and regaining it resumes whatever was playing.
 */
object FocusSound {
    const val OFF = "off"
    const val WHITE = "white"
    const val BROWN = "brown"
    const val RAIN = "rain"

    val SOUNDS = listOf(WHITE, BROWN, RAIN)

    fun choice(ctx: Context): String = Store.settings(ctx).getString("focus_sound", OFF) ?: OFF

    fun setChoice(ctx: Context, c: String) {
        Store.settings(ctx).edit().putString("focus_sound", c).apply()
    }

    fun label(c: String): String = when (c) {
        WHITE -> L.t("White noise", "হোয়াইট নয়েজ")
        BROWN -> L.t("Brown noise", "ব্রাউন নয়েজ")
        RAIN -> L.t("Rain", "বৃষ্টি")
        else -> L.t("Off", "বন্ধ")
    }

    private const val RATE = 22050
    private const val SINGLE_VOLUME = 0.6f
    private const val DEFAULT_MIX_VOLUME = 0.6f

    /** One playing sound: its track, its writer thread, and its own stop flag. */
    private class Stream(val choice: String, val track: AudioTrack) {
        @Volatile var running = true
        var worker: Thread? = null

        fun halt() {
            running = false
            worker?.interrupt()
            try {
                worker?.join(500)
            } catch (_: Exception) {
            }
            worker = null
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Currently playing streams, keyed by sound (single sound and mix share the map). */
    private val streams = HashMap<String, Stream>()
    private var appCtx: Context? = null
    private var focusReq: AudioFocusRequest? = null
    @Volatile private var wantsPlay = false
    private var singleChoice = OFF

    // ---------- lock sound (one sound, auto-played during a lock) ----------

    /** Called on lock start/end and on service restart: play iff a lock runs and a sound is chosen. */
    @Synchronized
    fun sync(ctx: Context) {
        if (Store.isActive(ctx) && choice(ctx) != OFF) startInternal(ctx, explicit = false)
        else stop()
    }

    /** The user picked a sound in Settings: start right away even on silent/vibrate. */
    @Synchronized
    fun startExplicit(ctx: Context) {
        if (choice(ctx) != OFF) startInternal(ctx, explicit = true)
    }

    /** Stops the lock sound. The mixer is user-controlled and keeps playing. */
    @Synchronized
    fun stop() {
        wantsPlay = false
        stopSingle()
    }

    private fun startInternal(ctx: Context, explicit: Boolean) {
        val ch = choice(ctx)
        if (ch == OFF) {
            stopSingle()
            return
        }
        if (singleChoice == ch && streams.containsKey(ch)) {
            wantsPlay = true
            return
        }
        // the mixer owns playback while it runs — never play on top of it
        if (isMixPlaying(ctx)) {
            wantsPlay = true
            return
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // never auto-play on silent/vibrate; an explicit user choice may
        if (!explicit && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        appCtx = ctx.applicationContext
        wantsPlay = true
        stopSingle()
        if (!ensureFocus(ctx)) {
            wantsPlay = false
            return
        }
        if (!startStream(ch, SINGLE_VOLUME)) {
            wantsPlay = false
            abandonFocusIfIdle()
            return
        }
        singleChoice = ch
    }

    private fun stopSingle() {
        if (singleChoice != OFF) {
            streams.remove(singleChoice)?.halt()
            singleChoice = OFF
        }
        abandonFocusIfIdle()
    }

    // ---------- mixer (several sounds together, each with its own volume) ----------

    /** Persisted mixer: enabled sounds with their volumes (0..1). */
    fun mixVolumes(ctx: Context): Map<String, Float> {
        val out = HashMap<String, Float>()
        try {
            val obj = JSONObject(Store.settings(ctx).getString("mixer", "{}"))
            for (ch in SOUNDS) {
                if (obj.has(ch)) out[ch] = obj.optDouble(ch, DEFAULT_MIX_VOLUME.toDouble()).toFloat().coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "mixer read failed", e)
        }
        return out
    }

    private fun saveMix(ctx: Context, mix: Map<String, Float>) {
        try {
            val obj = JSONObject()
            for ((ch, v) in mix) obj.put(ch, v.toDouble())
            Store.settings(ctx).edit().putString("mixer", obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "mixer save failed", e)
        }
    }

    /** True when at least one mixer sound is enabled (the mix owns playback then). */
    fun isMixPlaying(ctx: Context): Boolean = mixVolumes(ctx).isNotEmpty()

    /** Mixer: start one sound now at [vol] (0..1). Explicit choice — plays even on silent.
     * Returns false when audio focus could not be taken (nothing is left enabled then). */
    @Synchronized
    fun startMixSound(ctx: Context, ch: String, vol: Float): Boolean {
        if (ch !in SOUNDS) return false
        val v = vol.coerceIn(0f, 1f)
        appCtx = ctx.applicationContext
        stopSingle() // the mix and the lock sound never play together
        val mix = mixVolumes(ctx).toMutableMap()
        mix[ch] = v
        saveMix(ctx, mix)
        if (streams.containsKey(ch)) {
            try {
                streams[ch]?.track?.setVolume(v)
            } catch (_: Exception) {
            }
            return true
        }
        if (!ensureFocus(ctx)) {
            // focus denied: roll the sound back out so the UI stays honest
            val rolled = mixVolumes(ctx).toMutableMap()
            rolled.remove(ch)
            saveMix(ctx, rolled)
            return false
        }
        if (!startStream(ch, v)) {
            // stream failed to start: roll the sound back out
            val rolled = mixVolumes(ctx).toMutableMap()
            rolled.remove(ch)
            saveMix(ctx, rolled)
            abandonFocusIfIdle()
            return false
        }
        return true
    }

    /** Mixer: change one sound's volume live (0..1). No-op when it isn't enabled. */
    @Synchronized
    fun setMixVolume(ctx: Context, ch: String, vol: Float) {
        val v = vol.coerceIn(0f, 1f)
        val mix = mixVolumes(ctx).toMutableMap()
        if (!mix.containsKey(ch)) return
        mix[ch] = v
        saveMix(ctx, mix)
        try {
            streams[ch]?.track?.setVolume(v)
        } catch (_: Exception) {
        }
    }

    /** Mixer: stop one sound. */
    @Synchronized
    fun stopMixSound(ctx: Context, ch: String) {
        val mix = mixVolumes(ctx).toMutableMap()
        mix.remove(ch)
        saveMix(ctx, mix)
        streams.remove(ch)?.halt()
        abandonFocusIfIdle()
    }

    // ---------- streams + audio focus (shared by both modes) ----------

    private fun startStream(ch: String, vol: Float): Boolean {
        var tr: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = (minBuf * 2).coerceAtLeast(RATE / 2)
            tr = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            tr.setVolume(vol.coerceIn(0f, 1f))
            tr.play()
            val st = Stream(ch, tr)
            val w = Thread { writeLoop(ch, st) }
            st.worker = w
            streams[ch] = st
            w.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "focus sound: start failed", e)
            streams.remove(ch)?.halt()
            try {
                tr?.stop()
                tr?.release()
            } catch (_: Exception) {
            }
            return false
        }
    }

    /** Halts every stream (audio-focus loss). Playback wishes are kept, so a
     * focus regain can resume via [resumeAll]. */
    private fun haltAll() {
        for ((_, st) in streams) st.halt()
        streams.clear()
        singleChoice = OFF
        abandonFocusIfIdle()
    }

    /** Restarts whatever was playing before a focus loss: the mix first,
     * otherwise the lock sound when a lock is running. */
    private fun resumeAll(ctx: Context) {
        val mix = mixVolumes(ctx)
        if (mix.isNotEmpty()) {
            if (!ensureFocus(ctx)) return
            for ((ch, vol) in mix) {
                if (!streams.containsKey(ch)) startStream(ch, vol)
            }
            return
        }
        if (wantsPlay && Store.isActive(ctx)) startInternal(ctx, explicit = true)
    }

    /** Requests audio focus once (shared by all streams). Returns true when held. */
    private fun ensureFocus(ctx: Context): Boolean {
        if (focusReq != null) return true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            synchronized(this) { haltAll() }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            val c = appCtx
                            if (c != null) synchronized(this) { resumeAll(c) }
                        }
                    }
                }, Handler(Looper.getMainLooper()))
                .build()
            if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            focusReq = req
            return true
        } catch (e: Exception) {
            Log.e(TAG, "focus sound: audio focus failed", e)
            return false
        }
    }

    private fun abandonFocusIfIdle() {
        if (streams.isNotEmpty()) return
        try {
            val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val req = focusReq
            if (am != null && req != null) am.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
        focusReq = null
    }

    /** Generates the chosen noise in chunks and streams it to the track. */
    private fun writeLoop(ch: String, st: Stream) {
        val chunk = ShortArray(2048)
        val rnd = Random(System.currentTimeMillis())
        var brownLast = 0f
        var lp = 0f
        var phase = 0.0
        while (st.running) {
            try {
                for (i in chunk.indices) {
                    val w = rnd.nextFloat() * 2f - 1f
                    val v = when (ch) {
                        BROWN -> {
                            brownLast = (brownLast + 0.02f * w) / 1.02f
                            (brownLast * 3.5f).coerceIn(-1f, 1f)
                        }
                        RAIN -> {
                            // low-passed noise + a slow amplitude wobble = rain-like patter
                            lp += 0.12f * (w - lp)
                            phase += 2.0 * PI * 0.09 / RATE
                            val lfo = 0.75f + 0.25f * sin(phase).toFloat()
                            (lp * 2.2f * lfo).coerceIn(-1f, 1f)
                        }
                        else -> w * 0.5f // white, gentler
                    }
                    chunk[i] = (v * 12000f).toInt().coerceIn(-32768, 32767).toShort()
                }
                if (!st.running) break
                st.track.write(chunk, 0, chunk.size)
            } catch (e: Exception) {
                Log.e(TAG, "focus sound: write failed", e)
                break
            }
        }
    }
}
