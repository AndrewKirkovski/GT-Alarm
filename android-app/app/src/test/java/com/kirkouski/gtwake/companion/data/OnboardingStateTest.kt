package com.kirkouski.gtwake.companion.data

import com.kirkouski.gtwake.companion.data.OnboardingState.Companion.shouldShowBatteryOptCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {

    @Test
    fun `card shows on first launch when not exempt`() {
        assertTrue(shouldShowBatteryOptCard(dismissed = false, isIgnoringBatteryOptimizations = false))
    }

    @Test
    fun `card hides once user dismisses it`() {
        assertFalse(shouldShowBatteryOptCard(dismissed = true, isIgnoringBatteryOptimizations = false))
    }

    @Test
    fun `card hides when system already exempts the app`() {
        assertFalse(shouldShowBatteryOptCard(dismissed = false, isIgnoringBatteryOptimizations = true))
    }

    @Test
    fun `card stays hidden if both conditions resolved`() {
        assertFalse(shouldShowBatteryOptCard(dismissed = true, isIgnoringBatteryOptimizations = true))
    }
}
