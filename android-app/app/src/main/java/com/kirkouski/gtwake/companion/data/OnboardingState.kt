package com.kirkouski.gtwake.companion.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot UI flags persisted across launches: which onboarding/rationale
 * cards has the user already dismissed? Backed by a tiny SharedPreferences
 * blob — no need for DataStore here, single boolean per flag.
 */
@Singleton
class OnboardingState @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun batteryOptCardDismissed(): Boolean =
        prefs.getBoolean(KEY_BATTERY_OPT_CARD_DISMISSED, false)

    fun markBatteryOptCardDismissed() {
        prefs.edit { putBoolean(KEY_BATTERY_OPT_CARD_DISMISSED, true) }
    }

    /**
     * True iff the user has accepted the CURRENT privacy-policy version. We
     * persist the accepted version (its effective date) rather than a boolean,
     * so that publishing a revised policy (bumping [PRIVACY_POLICY_VERSION])
     * re-prompts everyone for fresh consent.
     */
    fun privacyPolicyAccepted(): Boolean =
        prefs.getString(KEY_PRIVACY_POLICY_ACCEPTED_VERSION, null) == PRIVACY_POLICY_VERSION

    fun markPrivacyPolicyAccepted() {
        // commit=true: persist synchronously before the user proceeds into the
        // app, so a process death right after accepting can't re-show the gate.
        prefs.edit(commit = true) {
            putString(KEY_PRIVACY_POLICY_ACCEPTED_VERSION, PRIVACY_POLICY_VERSION)
        }
    }

    fun fsiPromptShown(): Boolean =
        prefs.getBoolean(KEY_FSI_PROMPT_SHOWN, false)

    fun markFsiPromptShown() {
        // commit=true: caller immediately deeplinks to system Settings.
        // Default `apply()` is async; if the OS reaps our process while the
        // user is in Settings before the flush completes, the bool is lost
        // and we'd re-prompt on next launch. Synchronous commit guarantees
        // the flag is persisted before we leave.
        prefs.edit(commit = true) { putBoolean(KEY_FSI_PROMPT_SHOWN, true) }
    }

    companion object {
        // Pure decision helper extracted for unit testing without a Context.
        // The card shows iff the user hasn't dismissed it AND the system says
        // we're not already exempt from battery optimizations.
        fun shouldShowBatteryOptCard(
            dismissed: Boolean,
            isIgnoringBatteryOptimizations: Boolean,
        ): Boolean = !dismissed && !isIgnoringBatteryOptimizations

        private const val PREFS_FILE = "onboarding_v1"
        private const val KEY_BATTERY_OPT_CARD_DISMISSED = "battery_opt_card_dismissed"
        // First-launch privacy-policy consent gate (AppGallery review rule 7.5).
        // The CURRENT policy version = its effective date. Bump this whenever the
        // privacy policy changes to re-prompt users; keep it in sync with
        // meta/privacy-policy.md and the site Privacy.vue EFFECTIVE_DATE.
        const val PRIVACY_POLICY_VERSION = "2026-06-08"
        private const val KEY_PRIVACY_POLICY_ACCEPTED_VERSION = "privacy_policy_accepted_version"
        // One-shot flag: auto-launch the system's full-screen-intent settings
        // page on first detection that the appop is not MODE_ALLOWED. After
        // it fires once, subsequent launches rely on the Setup banner in the
        // alarm list to nudge — auto-launching every cold start would yank
        // the user out of the app repeatedly on Samsung One UI where the
        // appop defaults to MODE_DEFAULT.
        private const val KEY_FSI_PROMPT_SHOWN = "fsi_prompt_shown_v1"
    }
}
