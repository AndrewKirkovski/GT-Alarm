package com.kirkouski.gtwake.companion.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.kirkouski.gtwake.companion.R
import com.kirkouski.gtwake.companion.domain.VibrationPattern

class AlarmAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val rampHandler = Handler(Looper.getMainLooper())
    private var rampRunnable: Runnable? = null

    // Guards every field above. MediaPlayer callbacks (prepared, error) and the
    // service's stop() are separate entry points; without one lock and one
    // `stopped` flag, a stop() that lands while start() is still preparing
    // would leave a player that then starts looping with nothing able to stop it.
    private val lock = Any()

    // Set by stop() and never cleared: an AlarmAudioPlayer serves exactly one
    // ring. Once set, start() is a no-op and every late callback releases its
    // player instead of starting it.
    private var stopped = false

    /** [forceBundledFallback] = pre-unlock path; skips ContentResolver-backed URI resolution. */
    fun start(
        uri: String?,
        vibrateOnly: Boolean,
        pattern: VibrationPattern = VibrationPattern.DEFAULT,
        volumeRampSeconds: Int = 0,
        forceBundledFallback: Boolean = false,
        phoneVibrationEnabled: Boolean = true,
    ) {
        synchronized(lock) {
            if (stopped) {
                Log.i(TAG, "start ignored — already stopped")
                return
            }
            startLocked(uri, vibrateOnly, pattern, volumeRampSeconds, forceBundledFallback, phoneVibrationEnabled)
        }
    }

    // reason: forwards start()'s own six parameters unchanged so the stopped-flag
    // check and the lock live in start() while the body stays as it was.
    @Suppress("LongParameterList")
    private fun startLocked(
        uri: String?,
        vibrateOnly: Boolean,
        pattern: VibrationPattern,
        volumeRampSeconds: Int,
        forceBundledFallback: Boolean,
        phoneVibrationEnabled: Boolean,
    ) {
        Log.i(
            TAG,
            "start vibrateOnly=$vibrateOnly userUri=${uri != null} " +
                "pattern=${pattern.name} rampSec=$volumeRampSeconds " +
                "forceBundledFallback=$forceBundledFallback phoneVib=$phoneVibrationEnabled",
        )
        // 1.0.6: sound (vibrateOnly) and phone vibration are independent switches.
        // Vibrate only when the phone-vibration switch is on; skip audio when
        // sound is off (vibrateOnly). Both off ⇒ fully silent (UI only).
        if (phoneVibrationEnabled) startVibration(pattern)
        if (vibrateOnly) return
        if (forceBundledFallback) {
            playBundledFallback(volumeRampSeconds)
            return
        }

        val parsedUserUri = uri?.let { runCatching { it.toUri() }.getOrNull() }
        val playbackUri = parsedUserUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (playbackUri == null) {
            Log.w(TAG, "no alarm audio URI available — using bundled fallback")
            playBundledFallback(volumeRampSeconds)
            return
        }
        if (parsedUserUri == null && uri != null) {
            Log.w(TAG, "user uri unparseable, using system default")
        }
        Log.i(TAG, "audio source=${if (parsedUserUri != null) "user" else "default"} uri=$playbackUri")

        // Alarm-ring path must never crash — any throw falls back.
        runCatching {
            releaseExistingPlayer()
            mediaPlayer = buildPlayer(
                playbackUri,
                onPrepared = { mp -> onPlayerPrepared(mp, volumeRampSeconds) },
                onError = { onPlayerError { tryFallback(volumeRampSeconds) } },
            )
        }.onFailure { t ->
            Log.w(TAG, "failed to set up $playbackUri: ${t.message}")
            tryFallback(volumeRampSeconds)
        }
    }

    /**
     * Prepared callback (main looper, outside start()'s lock scope). A stop()
     * that landed while the player was preparing has already nulled
     * [mediaPlayer]; the identity check then releases this player instead of
     * starting a looping tone nothing can stop.
     */
    private fun onPlayerPrepared(mp: MediaPlayer, volumeRampSeconds: Int) {
        synchronized(lock) {
            if (stopped || mediaPlayer !== mp) {
                runCatching { mp.release() }
                return
            }
            beginPlayback(mp, volumeRampSeconds)
        }
    }

    private fun onPlayerError(fallback: () -> Unit) {
        synchronized(lock) {
            if (!stopped) fallback()
        }
    }

    /** Release prior MediaPlayer — leftover native audio session blocks the replacement. */
    private fun releaseExistingPlayer() {
        // Cancel any in-flight volume ramp before releasing the player —
        // otherwise stale ticks keep calling setVolume() on the released
        // instance for the rest of the ramp duration. runCatching at the
        // tick site swallows the IllegalStateException so it's not user-
        // visible, but the CPU/battery cost is real.
        cancelRamp()
        mediaPlayer?.let { old ->
            runCatching { old.release() }
                .onFailure { Log.w(TAG, "release prior player failed: ${it.message}") }
        }
        mediaPlayer = null
    }

    private fun tryFallback(volumeRampSeconds: Int) {
        if (stopped) return
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (fallback == null) {
            Log.w(TAG, "system default ringtone null — using bundled fallback")
            playBundledFallback(volumeRampSeconds)
            return
        }
        runCatching {
            releaseExistingPlayer()
            mediaPlayer = buildPlayer(
                fallback,
                onPrepared = { mp -> onPlayerPrepared(mp, volumeRampSeconds) },
                onError = {
                    Log.e(TAG, "system-default playback failed in prepareAsync — bundled fallback")
                    onPlayerError { playBundledFallback(volumeRampSeconds) }
                },
            )
        }.onFailure { t ->
            Log.e(TAG, "system-default fallback also failed (${t.message}) — bundled fallback")
            playBundledFallback(volumeRampSeconds)
        }
    }

    /** APK-bundled tone — no ContentResolver, works pre-unlock. */
    private fun playBundledFallback(volumeRampSeconds: Int) {
        if (stopped) return
        releaseExistingPlayer()
        runCatching {
            val mp = MediaPlayer.create(context, R.raw.fallback_alarm)
            if (mp == null) {
                Log.e(TAG, "MediaPlayer.create(R.raw.fallback_alarm) returned null — vibration only")
                return
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "bundled fallback MediaPlayer error what=$what extra=$extra — vibration only")
                true
            }
            mediaPlayer = mp
            beginPlayback(mp, volumeRampSeconds)
        }.onFailure { t ->
            Log.e(TAG, "bundled fallback failed: ${t.message} — vibration only")
        }
    }

    /** Start MediaPlayer + (if configured) linear-ramp the alarm volume from
     *  near-silence to full over the configured seconds. Tick every 200 ms;
     *  a 30 s ramp = 150 ticks, negligible CPU. */
    private fun beginPlayback(mp: MediaPlayer, volumeRampSeconds: Int) {
        cancelRamp()
        val startVolume = if (volumeRampSeconds <= 0) VOLUME_MAX else VOLUME_FLOOR
        // A player released by a concurrent stop() throws IllegalStateException
        // from setVolume/start. That is the expected outcome of the race, not a
        // crash: the ring path must never throw out of a media callback.
        val started = runCatching {
            mp.setVolume(startVolume, startVolume)
            mp.start()
        }.onFailure { Log.w(TAG, "beginPlayback skipped — player released: ${it.message}") }.isSuccess
        if (!started || volumeRampSeconds <= 0) return
        val totalMs = volumeRampSeconds * 1000L
        val startMs = android.os.SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (stopped) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
                val frac = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                val vol = VOLUME_FLOOR + (VOLUME_MAX - VOLUME_FLOOR) * frac
                runCatching { mp.setVolume(vol, vol) }
                if (frac < 1f) rampHandler.postDelayed(this, RAMP_TICK_MS)
            }
        }
        rampRunnable = tick
        rampHandler.postDelayed(tick, RAMP_TICK_MS)
    }

    private fun cancelRamp() {
        rampRunnable?.let { rampHandler.removeCallbacks(it) }
        rampRunnable = null
    }

    /**
     * Build a MediaPlayer configured for alarm playback. Uses [prepareAsync]
     * so the main thread isn't blocked while a slow content:// URI is
     * resolved (custom ringtones on cloud-backed media storage can take
     * 100ms+, enough to ANR a foreground-service start). `onPrepared` fires
     * when the player is ready to start; `onError` fires on
     * setOnErrorListener — both invoked on the binder thread but post to
     * the main thread internally via MediaPlayer.
     */
    private fun buildPlayer(
        uri: android.net.Uri,
        onPrepared: (MediaPlayer) -> Unit,
        onError: () -> Unit,
    ): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        setDataSource(context, uri)
        isLooping = true
        setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            onError()
            true
        }
        setOnPreparedListener(onPrepared)
        prepareAsync()
    }

    private fun startVibration(pattern: VibrationPattern) {
        if (pattern == VibrationPattern.OFF) return
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val v = vm.defaultVibrator
        vibrator = v
        // repeatIndex=0 = loop the whole pattern from the start.
        val effect = VibrationEffect.createWaveform(pattern.phoneTimings, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            // reason: Vibrator.vibrate(VibrationEffect, AudioAttributes) is
            // required pre-API-33; the modern VibrationAttributes overload
            // doesn't exist on minSdk 31. Branched cleanly above.
            @Suppress("DEPRECATION")
            v.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        }
    }

    /**
     * Stops audio and vibration and makes this player permanently inert. Safe
     * to call at any point, including while [start] is still preparing: the
     * late prepared/error callbacks see [stopped] and release instead of play.
     */
    fun stop() {
        synchronized(lock) {
            stopped = true
            cancelRamp()
            // Log release failures — a hung MediaPlayer leaks the native audio session.
            mediaPlayer?.let { mp ->
                runCatching { mp.stop() }.onFailure { Log.w(TAG, "mp.stop() failed: ${it.message}") }
                runCatching { mp.release() }.onFailure { Log.w(TAG, "mp.release() failed: ${it.message}") }
            }
            mediaPlayer = null
            vibrator?.let { v ->
                runCatching { v.cancel() }.onFailure { Log.w(TAG, "vibrator.cancel() failed: ${it.message}") }
            }
            vibrator = null
        }
    }

    private companion object {
        const val TAG = "AlarmAudio"
        const val VOLUME_FLOOR = 0.01f
        const val VOLUME_MAX = 1.0f
        const val RAMP_TICK_MS = 200L
    }
}
