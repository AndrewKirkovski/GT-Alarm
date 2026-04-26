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
        startVibration()
        if (vibrateOnly) return

        val playbackUri = uri?.let { runCatching { it.toUri() }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (playbackUri == null) {
            Log.w(TAG, "no alarm audio available — vibration only")
            return
        }

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, playbackUri)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // reason: MediaPlayer.{setDataSource, prepare} can throw IOException,
            // IllegalArgumentException, IllegalStateException, SecurityException,
            // and an undocumented set of native errors (NPE through JNI on certain
            // ROMs). For the alarm-ring path we MUST not let any of these crash the
            // foreground service — otherwise the alarm goes silent. Catch-all is the
            // intent: log + fall back to system default.
            Log.w(TAG, "failed to play $playbackUri: ${t.message}")
            tryFallback()
        }
    }

    private fun tryFallback() {
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, fallback)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // reason: same as the primary catch above — alarm path must never
            // crash. If even the system default fallback fails (very rare,
            // typically permission/storage corruption), we log and continue
            // silently rather than crashing the foreground service.
            Log.e(TAG, "fallback playback also failed: ${t.message}")
        }
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
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private companion object {
        const val TAG = "AlarmAudio"
    }
}
