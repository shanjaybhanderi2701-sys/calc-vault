package com.appblish.calculatorvault.applock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuggestedAppsTest {
    @Test
    fun suggestedPackages_onlyReturnsInstalledCandidates() {
        val installed = setOf("com.whatsapp", "com.instagram.android", "com.example.notes")
        val suggested = SuggestedApps.suggestedPackages(installed)
        assertThat(suggested).containsExactly("com.whatsapp", "com.instagram.android")
    }

    @Test
    fun suggestedPackages_emptyWhenNoneInstalled() {
        assertThat(SuggestedApps.suggestedPackages(setOf("com.example.notes"))).isEmpty()
    }

    @Test
    fun isSuggested_knownAndUnknown() {
        assertThat(SuggestedApps.isSuggested("com.google.android.gm")).isTrue()
        assertThat(SuggestedApps.isSuggested("com.example.unknown")).isFalse()
    }

    @Test
    fun candidates_coverBoardCategories() {
        // Board-specified set: messaging, store, email, socials.
        assertThat(SuggestedApps.CANDIDATES).containsAtLeast(
            "com.whatsapp",
            "com.android.vending",
            "com.google.android.gm",
            "com.instagram.android",
        )
    }
}
