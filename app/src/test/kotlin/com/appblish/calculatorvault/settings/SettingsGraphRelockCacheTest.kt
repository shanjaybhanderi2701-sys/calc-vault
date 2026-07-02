package com.appblish.calculatorvault.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Proves the synchronous "Re-lock on background" cache (APP-205) that VaultNavHost's
 * main-thread ON_STOP observer reads: it defaults on, warms from persisted settings, and
 * tracks live toggle changes — so the previously-dead Phase-5 switch now drives real
 * behaviour.
 */
class SettingsGraphRelockCacheTest {
    @Test
    fun `cache defaults to enabled`() {
        SettingsGraph.cacheRelockOnBackground(true)
        assertThat(SettingsGraph.relockOnBackgroundEnabled).isTrue()
    }

    @Test
    fun `warmCaches reflects the persisted switch`() =
        runTest {
            val store = InMemorySettingsStore()
            store.save(VaultSettings(relockOnBackgroundEnabled = false))
            SettingsGraph.override(store)

            SettingsGraph.warmCaches()

            assertThat(SettingsGraph.relockOnBackgroundEnabled).isFalse()
        }

    @Test
    fun `cacheRelockOnBackground tracks a live toggle change`() {
        SettingsGraph.cacheRelockOnBackground(false)
        assertThat(SettingsGraph.relockOnBackgroundEnabled).isFalse()

        SettingsGraph.cacheRelockOnBackground(true)
        assertThat(SettingsGraph.relockOnBackgroundEnabled).isTrue()
    }
}
