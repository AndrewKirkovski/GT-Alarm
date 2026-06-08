package com.kirkouski.gtwake.companion.ui.edit

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Preview controller for audio-picker rows. Plays the selected ringtone via
 * MediaPlayer with `USAGE_MEDIA` (NOT `USAGE_ALARM`) — this routes through
 * the media volume stream which the user has already turned down to a sane
 * level, so previewing a ringtone doesn't blast at full alarm volume in a
 * quiet bedroom. One-shot (no loop), auto-stops after [previewSeconds] or
 * end-of-track, whichever first.
 *
 * Pattern lifted in spirit from sweakpl/qralarm-android `AlarmRingtonePlayer.kt`:
 * single shared player, calling `play(newUri)` while one is in flight stops
 * the previous before starting the next. Compose layer wraps with a
 * [DisposableEffect] that stops on screen leave.
 */
class AudioPreview(private val context: Context) {

    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoStopJob: Job? = null

    /** Reflects whether playback is currently active. Drives Play/Stop button icon. */
    val playing: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Begin previewing [uri]. If null, plays the system default alarm tone
     * (matches what the actual alarm-fire path uses when the alarm has no
     * custom URI). Stops any in-flight preview first.
     */
    fun play(uri: String?) {
        stop()
        val parsed = uri?.let { runCatching { it.toUri() }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (parsed == null) {
            Log.w(TAG, "no audio uri to preview (default ringtone also missing)")
            return
        }
        // MediaPlayer.setDataSource throws a documented mix of IOException,
        // IllegalArgumentException, IllegalStateException, SecurityException —
        // plus undocumented native errors on some ROMs. For a preview button,
        // silently fail and log; never crash the edit screen.
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_MEDIA = preview-volume (media stream), not the
                        // alarm stream's max-level behavior. User can adjust
                        // with volume keys without it being shocking.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, parsed)
                isLooping = false
                setOnPreparedListener {
                    it.start()
                    playing.value = true
                    Log.i(TAG, "preview started uri=$parsed")
                }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "preview MediaPlayer error what=$what extra=$extra")
                    stop()
                    true
                }
                prepareAsync()
            }
            autoStopJob?.cancel()
            autoStopJob = scope.launch {
                delay(PREVIEW_TIMEOUT_MS)
                Log.i(TAG, "preview auto-stop after ${PREVIEW_TIMEOUT_MS}ms")
                stop()
            }
        }.onFailure { t ->
            Log.w(TAG, "preview setup failed for $parsed: ${t.message}")
            stop()
        }
    }

    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
        player?.let { mp ->
            runCatching { mp.stop() }.onFailure { Log.w(TAG, "mp.stop failed: ${it.message}") }
            runCatching { mp.release() }.onFailure { Log.w(TAG, "mp.release failed: ${it.message}") }
        }
        player = null
        playing.value = false
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private companion object {
        const val TAG = "AudioPreview"
        const val PREVIEW_TIMEOUT_MS = 6_000L
    }
}

/**
 * Composable-scoped audio preview that auto-disposes on leave. Returns the
 * [AudioPreview] for the caller to invoke `.play(uri)` / `.stop()` /
 * `.playing` from button onClick handlers.
 */
@Composable
fun rememberAudioPreview(): AudioPreview {
    val ctx = LocalContext.current
    val preview = remember(ctx) { AudioPreview(ctx) }
    DisposableEffect(preview) {
        onDispose { preview.dispose() }
    }
    return preview
}

