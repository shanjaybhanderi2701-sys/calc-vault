package com.appblish.calculatorvault.applock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockLogicTest {
    private val apps =
        listOf(
            InstalledApp("com.a", "Alpha", locked = true),
            InstalledApp("com.b", "Beta", locked = false),
            InstalledApp("com.c", "Gamma", locked = true, suggested = true),
        )

    @Test
    fun filter_all_returnsEverything() {
        assertThat(AppLockLogic.filter(apps, AppLockFilter.All)).hasSize(3)
    }

    @Test
    fun filter_locked_returnsOnlyLocked() {
        val locked = AppLockLogic.filter(apps, AppLockFilter.Locked)
        assertThat(locked.map { it.packageName }).containsExactly("com.a", "com.c")
    }

    @Test
    fun filter_unlocked_returnsOnlyUnlocked() {
        val unlocked = AppLockLogic.filter(apps, AppLockFilter.Unlocked)
        assertThat(unlocked.map { it.packageName }).containsExactly("com.b")
    }

    @Test
    fun search_matchesLabelCaseInsensitively() {
        assertThat(AppLockLogic.search(apps, "bet").map { it.label }).containsExactly("Beta")
    }

    @Test
    fun search_matchesPackage() {
        assertThat(AppLockLogic.search(apps, "com.c").map { it.label }).containsExactly("Gamma")
    }

    @Test
    fun search_emptyQuery_returnsAll() {
        assertThat(AppLockLogic.search(apps, "   ")).hasSize(3)
    }

    @Test
    fun shouldCapture_onlyWhenEnabledAndExactlyAtThreshold() {
        val on = AppLockSettings(intruderEnabled = true, intruderThreshold = 3)
        assertThat(AppLockLogic.shouldCaptureIntruder(2, on)).isFalse()
        assertThat(AppLockLogic.shouldCaptureIntruder(3, on)).isTrue()
        // Fires exactly once at the threshold, not on every later failure.
        assertThat(AppLockLogic.shouldCaptureIntruder(4, on)).isFalse()
    }

    @Test
    fun shouldCapture_neverWhenDisabled() {
        val off = AppLockSettings(intruderEnabled = false, intruderThreshold = 3)
        assertThat(AppLockLogic.shouldCaptureIntruder(3, off)).isFalse()
    }
}
