package com.appblish.calculatorvault.settings

import com.appblish.calculatorvault.ui.theme.AccentColor
import com.appblish.calculatorvault.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The APP-525 DoD requires that theme mode + accent *persist*. This exercises the encode/decode
 * for [VaultSettings.themeMode] / [VaultSettings.accentColor] through the shared
 * [BaseSettingsStore] (in-memory here; identical on the encrypted on-device store).
 */
class ThemeSettingsPersistenceTest {
    private fun store() = InMemorySettingsStore()

    @Test
    fun `unset theme prefs fall back to first-run defaults - Dark and Blue`() =
        runTest {
            val settings = store().load()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(settings.accentColor).isEqualTo(AccentColor.BLUE)
        }

    @Test
    fun `theme mode and accent survive a save then load`() =
        runTest {
            val store = store()
            store.save(VaultSettings(themeMode = ThemeMode.SYSTEM, accentColor = AccentColor.ROSE))
            val loaded = store.load()
            assertThat(loaded.themeMode).isEqualTo(ThemeMode.SYSTEM)
            assertThat(loaded.accentColor).isEqualTo(AccentColor.ROSE)
        }

    @Test
    fun `an unknown persisted accent name decodes to the default instead of throwing`() =
        runTest {
            val store = store()
            // Simulate a swatch that was removed in a later build (name no longer in the enum).
            store.importRaw(mapOf("accent_color" to "MAGENTA_LEGACY", "theme_mode" to "OLED_LEGACY"))
            val loaded = store.load()
            assertThat(loaded.accentColor).isEqualTo(AccentColor.DEFAULT)
            assertThat(loaded.themeMode).isEqualTo(ThemeMode.DEFAULT)
        }
}
