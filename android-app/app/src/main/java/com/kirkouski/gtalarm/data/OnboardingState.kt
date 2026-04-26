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
    }
}
