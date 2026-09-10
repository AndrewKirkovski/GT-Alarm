package com.kirkouski.gtwake.companion.wear

/**
 * Outcome of a user-initiated "authorize watch access" tap.
 *
 * Every branch is surfaced to the user. Wear Engine's `requestPermission` can
 * fail without ever rendering a dialog — Huawei Health missing, out of date, or
 * unavailable on the host OS — and the previous implementation only logged that
 * case. To an AppGallery reviewer that read as a button that does nothing, and
 * it cost a rule-3.1 rejection (2026-09: "the phone app does not respond after
 * you touch the key icon to grant the permission").
 *
 * Silence is never an acceptable branch here. See [WearBridgeService.requestPermissionFromActivity].
 */
sealed class WearPermissionOutcome {
    /** Huawei Health returned a grant. */
    object Granted : WearPermissionOutcome()

    /** The dialog was shown and the user declined or dismissed it. */
    object Cancelled : WearPermissionOutcome()

    /**
     * The request never reached a dialog — the SDK threw. [message] is the
     * SDK's own text, kept for logcat; the UI shows localized guidance rather
     * than this string, which is untranslated and vendor-shaped.
     */
    data class Unavailable(val message: String) : WearPermissionOutcome()
}
