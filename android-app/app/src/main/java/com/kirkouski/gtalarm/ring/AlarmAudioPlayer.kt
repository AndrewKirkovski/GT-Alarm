package com.kirkouski.gtalarm.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.net.toUri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class AlarmAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    fun start(uri: String?, vibrateOnly: Boolean) {
        Log.i(TAG, "start vibrateOnly=$vibrateOnly userUri=${uri != null}")
        startVibration()
        if (vibrateOnly) return

        val parsedUserUri = uri?.let { runCatching { it.toUri() }.getOrNull() }
        val playbackUri = parsedUserUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (playbackUri == null) {
            Log.w(TAG, "no alarm audio available — vibration only")
            return
        }
        if (parsedUserUri == null && uri != null) {
            Log.w(TAG, "user uri unparseable, using system default")
        }
        Log.i(TAG, "audio source=${if (parsedUserUri != null) "user" else "default"} uri=$playbackUri")

        try {
            val mp = buildPlayer(playbackUri, onPrepared = { it.start() }, onError = { tryFallback() })
            mediaPlayer = mp
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // reason: MediaPlayer.setDataSource can throw IOException,
            // IllegalArgumentException, IllegalStateException, SecurityException,
            // and an undocumented set of native errors (NPE through JNI on certain
            // ROMs). For the alarm-ring path we MUST not let any of these crash the
            // foreground service — otherwise the alarm goes silent. Catch-all is the
            // intent: log + fall back to system default.
            Log.w(TAG, "failed to set up $playbackUri: ${t.message}")
            tryFallback()
        }
    }

    private fun tryFallback() {
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        try {
            val mp = buildPlayer(
                fallback,
                onPrepared = { it.start() },
                onError = { Log.e(TAG, "fallback playback failed in prepareAsync") },
            )
            mediaPlayer = mp
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // reason: same as the primary catch above — alarm path must never
            // crash. If even the system default fallback fails (very rare,
            // typically permission/storage corruption), we log and continue
            // silently rather than crashing the foreground service.
            Log.e(TAG, "fallback playback also failed: ${t.message}")
        }
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

    private fun startVibration() {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val v = vm.defaultVibrator
        vibrator = v
        val pattern = longArrayOf(0L, 600L, 400L, 600L, 400L)
        val amps = intArrayOf(0, 255, 0, 255, 0)
        val effect = VibrationEffect.createWaveform(pattern, amps, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        }
    }

    fun stop() {
        // Log release failures: a hung MediaPlayer that fails to release
        // is a real leak (native audio session stays open + may interfere
        // with the next alarm). Silent runCatching was hiding diagnostics
        // about exactly which ROMs/codecs fail here.
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

    private companion object {
        const val TAG = "AlarmAudio"
    }
}
