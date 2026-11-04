package com.kirkouski.gtwake.companion.stopwatch

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.stopwatchDataStore: DataStore<Preferences> by preferencesDataStore(name = "stopwatch_v1")

/**
 * The stored stopwatch, in its own DataStore so laps never touch
 * SettingsStore.state. The stored state is the source of truth: on Android 14+
 * a user can swipe the notification away. It stays apart from the alarm path:
 * no service, wake lock or AlarmManager.
 */
@Singleton
class StopwatchRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notifier: StopwatchNotifier,
) {
    val state: Flow<Stopwatch> = context.stopwatchDataStore.data.map(::read)

    fun clock(): StopwatchClock = StopwatchClock(
        elapsedMs = SystemClock.elapsedRealtime(),
        wallMs = System.currentTimeMillis(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
    )

    suspend fun update(transform: (Stopwatch, StopwatchClock) -> Stopwatch) {
        val clock = clock()
        val prefs = context.stopwatchDataStore.edit { prefs ->
            write(prefs, transform(read(prefs), clock))
        }
        notifier.show(read(prefs), clock)
    }

    suspend fun restore(previous: Stopwatch) = update { _, _ -> previous }

    /** Covers a swiped notification and a reboot. */
    suspend fun repostNotification() {
        notifier.show(state.first(), clock())
    }

    private fun read(prefs: Preferences): Stopwatch = Stopwatch(
        phase = Stopwatch.Phase.entries.getOrElse(prefs[KEY_PHASE] ?: 0) { Stopwatch.Phase.Idle },
        accumulatedMs = prefs[KEY_ACCUMULATED] ?: 0L,
        elapsedBaseMs = prefs[KEY_ELAPSED_BASE] ?: 0L,
        wallBaseMs = prefs[KEY_WALL_BASE] ?: 0L,
        bootCount = prefs[KEY_BOOT_COUNT] ?: 0,
        lapTotalsMs = prefs[KEY_LAPS].orEmpty().split(LAP_SEPARATOR).mapNotNull { it.toLongOrNull() },
    )

    private fun write(prefs: MutablePreferences, stopwatch: Stopwatch) {
        prefs[KEY_PHASE] = stopwatch.phase.ordinal
        prefs[KEY_ACCUMULATED] = stopwatch.accumulatedMs
        prefs[KEY_ELAPSED_BASE] = stopwatch.elapsedBaseMs
        prefs[KEY_WALL_BASE] = stopwatch.wallBaseMs
        prefs[KEY_BOOT_COUNT] = stopwatch.bootCount
        prefs[KEY_LAPS] = stopwatch.lapTotalsMs.joinToString(LAP_SEPARATOR)
    }

    private companion object {
        const val LAP_SEPARATOR = ","
        val KEY_PHASE = intPreferencesKey("phase")
        val KEY_ACCUMULATED = longPreferencesKey("accumulated_ms")
        val KEY_ELAPSED_BASE = longPreferencesKey("elapsed_base_ms")
        val KEY_WALL_BASE = longPreferencesKey("wall_base_ms")
        val KEY_BOOT_COUNT = intPreferencesKey("boot_count")
        val KEY_LAPS = stringPreferencesKey("lap_totals_ms")
    }
}
