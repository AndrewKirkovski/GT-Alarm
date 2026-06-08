package com.kirkouski.gtwake.companion.wear

/**
 * Outcome of a user-initiated "Force sync" tap. Surfaced to the UI as a
 * one-shot Snackbar/Toast so the user knows what happened without having
 * to chase logcat. See [WearBridgeService.forceSync].
 */
sealed class ForceSyncResult {
    /** Watch acked the ping and all alarms were dispatched. */
    data class Ok(val alarmCount: Int) : ForceSyncResult()

    /**
     * Pre-sync hash matched the watch's hash — no alarms needed pushing.
     * `alarmCount` is the count we would have sent (informational, for the
     * UI to say "Already in sync — N alarms match"). Surfaces explicitly
     * rather than as Ok(0) so the user can tell "we synced 0 because there
     * are no alarms" from "we synced 0 because the watch already matches".
     */
    data class AlreadyInSync(val alarmCount: Int) : ForceSyncResult()

    /** No bonded device found (Huawei Health not paired with a watch). */
    object NoDevice : ForceSyncResult()

    /** Wear Engine DEVICE_MANAGER permission not granted yet. */
    object NotAuthorized : ForceSyncResult()

    /** Bonded device exists but is currently disconnected (out of range / off). */
    object Disconnected : ForceSyncResult()

    /**
     * Ping reached the watch but the peer app isn't installed or hasn't been
     * launched at least once yet. [pingCode] is the raw `onPingResult(int)`
     * value from Wear Engine for debugging — see Huawei docs / vendor table.
     */
    data class PeerAppMissing(val pingCode: Int) : ForceSyncResult()

    /** Transport-level failure (SDK threw, send returned non-success). */
    data class Error(val message: String) : ForceSyncResult()
}
