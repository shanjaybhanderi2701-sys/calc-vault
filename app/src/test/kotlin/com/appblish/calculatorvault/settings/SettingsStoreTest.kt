package com.appblish.calculatorvault.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Exercises the shared [BaseSettingsStore] encode/decode through the in-memory store. The
 * results hold identically for the on-device [EncryptedSettingsStore].
 */
class SettingsStoreTest {
    private fun store() = InMemorySettingsStore()

    @Test
    fun `defaults match the deck when nothing is stored`() =
        runTest {
            val settings = store().load()
            assertThat(settings.keypadSkin).isEqualTo(KeypadSkin.GREEN_CLASSIC)
            assertThat(settings.unlockAnimation).isEqualTo(UnlockAnimation.FADE_IN)
            assertThat(settings.breakInAlertsEnabled).isTrue()
            assertThat(settings.fakePasswordEnabled).isTrue()
            assertThat(settings.preventUninstallEnabled).isFalse()
            assertThat(settings.disguiseIconEnabled).isFalse()
            assertThat(settings.relockOnBackgroundEnabled).isTrue()
            assertThat(settings.shufflePinPadEnabled).isFalse()
            assertThat(settings.incorrectVibrationEnabled).isTrue()
        }

    @Test
    fun `save then load round-trips every field`() =
        runTest {
            val store = store()
            val saved =
                VaultSettings(
                    keypadSkin = KeypadSkin.EMERALD_ROUNDED,
                    unlockAnimation = UnlockAnimation.SLIDE_LEFT,
                    breakInAlertsEnabled = false,
                    fakePasswordEnabled = false,
                    preventUninstallEnabled = true,
                    disguiseIconEnabled = true,
                    relockOnBackgroundEnabled = false,
                    shufflePinPadEnabled = true,
                    incorrectVibrationEnabled = false,
                )
            store.save(saved)
            assertThat(store.load()).isEqualTo(saved)
        }

    @Test
    fun `an unknown persisted skin name falls back to the default`() =
        runTest {
            val store = store()
            store.importRaw(mapOf("keypad_skin" to "NOT_A_SKIN"))
            assertThat(store.load().keypadSkin).isEqualTo(KeypadSkin.DEFAULT)
        }

    @Test
    fun `importRaw ignores keys outside the known settings set`() =
        runTest {
            val store = store()
            store.importRaw(mapOf("keypad_skin" to KeypadSkin.FOREST_DEEP.name, "malicious_key" to "x"))
            assertThat(store.exportRaw()).doesNotContainKey("malicious_key")
            assertThat(store.load().keypadSkin).isEqualTo(KeypadSkin.FOREST_DEEP)
        }
}
