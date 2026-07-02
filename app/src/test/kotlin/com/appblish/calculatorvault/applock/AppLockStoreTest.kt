package com.appblish.calculatorvault.applock

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppLockStoreTest {
    private fun store() = InMemoryAppLockStore()

    @Test
    fun lockAndUnlock_roundTrips() =
        runTest {
            val s = store()
            assertThat(s.lockedPackages()).isEmpty()
            s.setLocked("com.whatsapp", true)
            assertThat(s.isLocked("com.whatsapp")).isTrue()
            s.setLocked("com.whatsapp", false)
            assertThat(s.isLocked("com.whatsapp")).isFalse()
            assertThat(s.lockedPackages()).isEmpty()
        }

    @Test
    fun lockAll_unionsWithoutDuplicates() =
        runTest {
            val s = store()
            s.setLocked("com.a", true)
            s.lockAll(listOf("com.a", "com.b", "com.c"))
            assertThat(s.lockedPackages()).containsExactly("com.a", "com.b", "com.c")
        }

    @Test
    fun unlocking_clearsRecordedUnlockTimestamp() =
        runTest {
            val s = store()
            s.setLocked("com.a", true)
            s.recordUnlock("com.a", atMs = 12_345)
            assertThat(s.lastUnlockAt("com.a")).isEqualTo(12_345)
            s.setLocked("com.a", false)
            assertThat(s.lastUnlockAt("com.a")).isNull()
        }

    @Test
    fun settings_roundTripAndClampThreshold() =
        runTest {
            val s = store()
            s.setSettings(
                AppLockSettings(
                    lockMethod = LockMethod.Biometric,
                    relockDelayMs = 30_000,
                    intruderEnabled = true,
                    intruderThreshold = 99, // out of range → clamped on write
                ),
            )
            val loaded = s.settings()
            assertThat(loaded.lockMethod).isEqualTo(LockMethod.Biometric)
            assertThat(loaded.relockDelayMs).isEqualTo(30_000)
            assertThat(loaded.intruderEnabled).isTrue()
            assertThat(loaded.intruderThreshold).isEqualTo(AppLockSettings.MAX_THRESHOLD)
        }

    @Test
    fun defaults_whenUnset() =
        runTest {
            val loaded = store().settings()
            assertThat(loaded.lockMethod).isEqualTo(LockMethod.Pin)
            assertThat(loaded.relockDelayMs).isEqualTo(0L)
            assertThat(loaded.intruderEnabled).isFalse()
            assertThat(loaded.intruderThreshold).isEqualTo(3)
        }

    @Test
    fun suggestionsSeen_flag() =
        runTest {
            val s = store()
            assertThat(s.hasSeenSuggestions()).isFalse()
            s.markSuggestionsSeen()
            assertThat(s.hasSeenSuggestions()).isTrue()
        }

    @Test
    fun clearAll_wipesEverything() =
        runTest {
            val s = store()
            s.lockAll(listOf("com.a", "com.b"))
            s.setSettings(AppLockSettings(intruderEnabled = true))
            s.clearAll()
            assertThat(s.lockedPackages()).isEmpty()
            assertThat(s.settings().intruderEnabled).isFalse()
        }
}
