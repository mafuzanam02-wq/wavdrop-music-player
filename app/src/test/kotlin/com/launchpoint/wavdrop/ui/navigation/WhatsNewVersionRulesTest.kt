package com.launchpoint.wavdrop.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewVersionRulesTest {

    @Test
    fun `fresh install shows whats new after onboarding is complete`() {
        assertTrue(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 0,
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `whats new does not show before onboarding is complete`() {
        assertFalse(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = false,
                lastSeenVersionCode = 0,
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `whats new does not repeat after dismissal for same version code`() {
        assertFalse(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 2,
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `whats new shows again when version code increases`() {
        assertTrue(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 2,
                currentVersionCode = 3,
            ),
        )
    }

    @Test
    fun `version code comparison is numeric`() {
        assertTrue(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 2,
                currentVersionCode = 10,
            ),
        )
    }
}
