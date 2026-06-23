package com.kirkouski.gtwake.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-tunable app preferences persisted via androidx.datastore.preferences.
 * Backed by a flow-based DataStore so the UI re-renders the moment a value
 * changes anywhere in the process.
 *
 * Pattern parallels [OnboardingState] (SharedPreferences boolean flags) —
 * but DataStore is the right fit here because:
 *   - We have heterogeneous types (Boolean? / Int / String?) and the Flow
 *     surface auto-pushes updates into Compose collectors.
 *   - More fields will land here as Settings grows; SharedPreferences would
 *     need a manual change-listener for each new flow consumer.
 *
 * Fields:
 *   - [SettingsState.use24Hour]: null = follow system locale (DateFormat
 *     `is24HourFormat`); explicit true/false overrides per user choice.
 *   - [SettingsState.firstDayOfWeek]: 1..7 (java.util.Calendar values:
 *     SUNDAY=1..SATURDAY=7); null = follow locale via
 *     `Calendar.getInstance().firstDayOfWeek`.
 *   - [SettingsState.defaultAbsoluteRingtoneUri] / Name: default sound for
 *     new clock-time alarms; null = system default (TYPE_ALARM).
 *   - [SettingsState.defaultRelativeRingtoneUri] / Name: default sound for
 *     new "in N minutes" alarms; null = system default.
 *
 * Memory note: pre-release; no migrations needed.
 */
@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dataStore: DataStore<Preferences>
        get() = context.settingsDataStore

    val state: Flow<SettingsState> = dataStore.data.map { prefs ->
        SettingsState(
            use24Hour = prefs[KEY_USE_24H],
            firstDayOfWeek = prefs[KEY_FIRST_DAY_OF_WEEK],
            defaultAbsoluteRingtoneUri = prefs[KEY_DEFAULT_ABS_URI],
            defaultAbsoluteRingtoneName = prefs[KEY_DEFAULT_ABS_NAME],
            defaultRelativeRingtoneUri = prefs[KEY_DEFAULT_REL_URI],
            defaultRelativeRingtoneName = prefs[KEY_DEFAULT_REL_NAME],
            defaultPhoneBackgroundUri = prefs[KEY_DEFAULT_PHONE_BG_URI],
            defaultWatchBackgroundUri = prefs[KEY_DEFAULT_WATCH_BG_URI],
            lastUploadedWatchBgHash = prefs[KEY_LAST_WATCH_BG_HASH],
            watchScreenWidth = prefs[KEY_WATCH_SCREEN_W] ?: 0,
            watchScreenHeight = prefs[KEY_WATCH_SCREEN_H] ?: 0,
            watchScreenShape = prefs[KEY_WATCH_SCREEN_SHAPE] ?: "",
            watchScreenModel = prefs[KEY_WATCH_SCREEN_MODEL] ?: "",
        )
    }

    /**
     * Flow of the default phone ring-screen background URI. Surfaced as a
     * narrow projection of [state] so callers that only care about the
     * background (AlarmActivity) don't re-collect on every preference
     * change. Returns null when no default has been set (Settings screen
     * hasn't been visited yet, or the user explicitly cleared it).
     */
    val defaultPhoneBackgroundUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PHONE_BG_URI]
    }

    suspend fun setDefaultPhoneBackgroundUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_DEFAULT_PHONE_BG_URI) else prefs[KEY_DEFAULT_PHONE_BG_URI] = uri
        }
    }

    /**
     * Flow of the default watch ring-screen background URI. The watch
     * picker writes a 466 × 466 cropped PNG into the app cache; this URI
     * points at it. Watch consumes the parallel BGRA `.bin` over P2P
     * file-transfer, not this URI — the phone keeps it only so the
     * Settings UI can show the current selection thumbnail.
     */
    val defaultWatchBackgroundUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_WATCH_BG_URI]
    }

    suspend fun setDefaultWatchBackgroundUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_DEFAULT_WATCH_BG_URI) else prefs[KEY_DEFAULT_WATCH_BG_URI] = uri
        }
    }

    /**
     * Persist the content hash of the most recently UPLOADED default watch
     * background (`bgd_<hash>.bin` — see [AlarmRepository.uploadDefaultWatchBackground]).
     * The watch echoes its own held marker in the `watch_screen` reply; the
     * phone re-uploads when it differs (auto-restore after watch reset, and
     * never-send-mismatch). null clears it (default bg cleared / source gone).
     */
    suspend fun setLastUploadedWatchBgHash(hash: String?) {
        dataStore.edit { prefs ->
            if (hash == null) prefs.remove(KEY_LAST_WATCH_BG_HASH) else prefs[KEY_LAST_WATCH_BG_HASH] = hash
        }
    }

    /**
     * Persist the connected watch's reported screen (from the `watch_screen`
     * envelope — see [com.kirkouski.gtwake.companion.data.sync.IncomingMessage.WatchScreen]).
     * The background picker + encoder read this to size/shape the uploaded
     * image to the real panel. One watch at a time, so a single stored triple
     * is sufficient. `width<=0` means "no watch has reported yet" → callers
     * fall back to the GT6 default (466 round).
     *
     * [model] is the bonded-device key the dims belong to (normalized display
     * label). The bridge only re-requests the screen when the connected
     * model differs from this stored value — so the watch isn't pinged for a
     * resolution we already have.
     */
    suspend fun setWatchScreen(width: Int, height: Int, shape: String, model: String) {
        dataStore.edit { prefs ->
            prefs[KEY_WATCH_SCREEN_W] = width
            prefs[KEY_WATCH_SCREEN_H] = height
            prefs[KEY_WATCH_SCREEN_SHAPE] = shape
            prefs[KEY_WATCH_SCREEN_MODEL] = model
        }
    }

    /**
     * Synchronously read the latest committed state. DataStore guarantees
     * the in-memory cache is populated after the first `.data` collection;
     * for repository code paths that need a one-shot read (e.g. populating
     * a default ringtone on alarm save) the suspend `Flow.first()` is the
     * canonical access path. Exposed via [snapshot] below for callers that
     * already have a suspend context.
     */
    suspend fun snapshot(): SettingsState = state.first()

    suspend fun setUse24Hour(value: Boolean?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_USE_24H) else prefs[KEY_USE_24H] = value
        }
    }

    suspend fun setFirstDayOfWeek(value: Int?) {
        dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(KEY_FIRST_DAY_OF_WEEK)
            } else {
                require(value in CALENDAR_DAY_MIN..CALENDAR_DAY_MAX) {
                    "firstDayOfWeek=$value out of range [$CALENDAR_DAY_MIN, $CALENDAR_DAY_MAX]"
                }
                prefs[KEY_FIRST_DAY_OF_WEEK] = value
            }
        }
    }

    suspend fun setDefaultAbsoluteRingtone(uri: String?, name: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_DEFAULT_ABS_URI) else prefs[KEY_DEFAULT_ABS_URI] = uri
            if (name == null) prefs.remove(KEY_DEFAULT_ABS_NAME) else prefs[KEY_DEFAULT_ABS_NAME] = name
        }
    }

    suspend fun setDefaultRelativeRingtone(uri: String?, name: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_DEFAULT_REL_URI) else prefs[KEY_DEFAULT_REL_URI] = uri
            if (name == null) prefs.remove(KEY_DEFAULT_REL_NAME) else prefs[KEY_DEFAULT_REL_NAME] = name
        }
    }

    companion object {
        // SUNDAY=1..SATURDAY=7 per java.util.Calendar.
        const val CALENDAR_DAY_MIN = 1
        const val CALENDAR_DAY_MAX = 7

        private const val PREFS_FILE = "settings_v1"

        private val KEY_USE_24H = booleanPreferencesKey("use_24h")
        private val KEY_FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week")
        private val KEY_DEFAULT_ABS_URI = stringPreferencesKey("default_abs_ringtone_uri")
        private val KEY_DEFAULT_ABS_NAME = stringPreferencesKey("default_abs_ringtone_name")
        private val KEY_DEFAULT_REL_URI = stringPreferencesKey("default_rel_ringtone_uri")
        private val KEY_DEFAULT_REL_NAME = stringPreferencesKey("default_rel_ringtone_name")
        // Default phone ring-screen background image (content://...). When
        // the per-alarm backgroundImageUri is also null, AlarmActivity falls
        // back to its all-black background.
        private val KEY_DEFAULT_PHONE_BG_URI = stringPreferencesKey("default_phone_bg_uri")
        // Default watch ring-screen background image (file:// to cached
        // cropped 466 × 466 PNG). Single shared default per Phase 5a — the
        // per-alarm watch-bg field was removed in db v8; when this is null
        // the watch falls back to its own default ring UI.
        private val KEY_DEFAULT_WATCH_BG_URI = stringPreferencesKey("default_watch_bg_uri")
        // Content hash of the last default watch bg the phone uploaded
        // (`bgd_<hash>.bin`). Compared against the watch's reported marker in
        // the watch_screen reply to drive auto-restore + no-mismatch re-upload.
        private val KEY_LAST_WATCH_BG_HASH = stringPreferencesKey("last_uploaded_watch_bg_hash")
        // Connected watch's reported screen (from the watch_screen envelope).
        // 0/0/"" = no report yet → callers default to GT6 466 round.
        private val KEY_WATCH_SCREEN_W = intPreferencesKey("watch_screen_w")
        private val KEY_WATCH_SCREEN_H = intPreferencesKey("watch_screen_h")
        private val KEY_WATCH_SCREEN_SHAPE = stringPreferencesKey("watch_screen_shape")
        // Bonded-device label the cached dims belong to — re-request gate.
        private val KEY_WATCH_SCREEN_MODEL = stringPreferencesKey("watch_screen_model")

        // Extension property generates a process-singleton DataStore<Preferences>
        // bound to the application Context. Hilt @Singleton on [SettingsStore]
        // makes the SettingsStore instance unique; the underlying DataStore is
        // also process-unique because of how `preferencesDataStore` is wired.
        private val Context.settingsDataStore by preferencesDataStore(name = PREFS_FILE)
    }
}

/** Snapshot of all user-tunable preferences. See [SettingsStore] for field semantics. */
data class SettingsState(
    val use24Hour: Boolean? = null,
    val firstDayOfWeek: Int? = null,
    val defaultAbsoluteRingtoneUri: String? = null,
    val defaultAbsoluteRingtoneName: String? = null,
    val defaultRelativeRingtoneUri: String? = null,
    val defaultRelativeRingtoneName: String? = null,
    val defaultPhoneBackgroundUri: String? = null,
    val defaultWatchBackgroundUri: String? = null,
    // Content hash of the last default watch bg the phone uploaded; null = none.
    val lastUploadedWatchBgHash: String? = null,
    // Connected watch's reported screen; 0/0/"" = unknown (no report yet).
    val watchScreenWidth: Int = 0,
    val watchScreenHeight: Int = 0,
    val watchScreenShape: String = "",
    // Bonded-device label the cached dims belong to (re-request gate).
    val watchScreenModel: String = "",
)
