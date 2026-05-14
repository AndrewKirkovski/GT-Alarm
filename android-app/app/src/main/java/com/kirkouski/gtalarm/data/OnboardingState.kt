package com.kirkouski.gtalarm.data

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

    fun fsiPromptShown(): Boolean =
        prefs.getBoolean(KEY_FSI_PROMPT_SHOWN, false)

    fun markFsiPromptShown() {
        prefs.edit { putBoolean(KEY_FSI_PROMPT_SHOWN, true) }
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
        // One-shot flag: auto-launch the system's full-screen-intent settings
        // page on first detection that the appop is not MODE_ALLOWED. After
        // it fires once, subsequent launches rely on the Setup banner in the
        // alarm list to nudge — auto-launching every cold start would yank
        // the user out of the app repeatedly on Samsung One UI where the
        // appop defaults to MODE_DEFAULT.
        private const val KEY_FSI_PROMPT_SHOWN = "fsi_prompt_shown_v1"
    }
}
