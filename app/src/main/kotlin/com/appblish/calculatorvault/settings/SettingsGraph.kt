package com.appblish.calculatorvault.settings

import android.content.Context

/**
 * Minimal service locator for the single [SettingsStore], mirroring
 * [com.appblish.calculatorvault.auth.AuthGraph]. [init] is called once from
 * [com.appblish.calculatorvault.CalculatorVaultApp]; tests use [override] to inject an
 * [InMemorySettingsStore]. A real DI graph (Hilt) folds these locators together later.
 */
object SettingsGraph {
    @Volatile
    private var store: SettingsStore? = null

    /**
     * Synchronously-readable snapshot of the "Re-lock on background" switch (APP-205). The
     * `ON_STOP` re-lock in [com.appblish.calculatorvault.navigation.VaultNavHost] runs on the
     * main thread with no coroutine to await the suspend [SettingsStore], so the flag is
     * cached here: warmed from the store at startup ([warmCaches]) and updated the instant the
     * user flips the switch in Settings ([cacheRelockOnBackground]). Defaults to the secure
     * choice (on) so re-lock stays enabled even before the store has been read.
     */
    @Volatile
    var relockOnBackgroundEnabled: Boolean = true
        private set

    /**
     * Synchronously-readable snapshot of the "Allow screenshots" switch (PIN Recovery W4).
     * [com.appblish.calculatorvault.MainActivity] decides `FLAG_SECURE` in `onCreate` /
     * `onResume` on the main thread with no coroutine to await the suspend [SettingsStore],
     * so the flag is cached here — warmed at startup ([warmCaches]) and updated the instant the
     * user flips the toggle ([cacheAllowScreenshots]). Defaults to the secure choice (off →
     * screenshots blocked) so protection holds even before the store has been read.
     */
    @Volatile
    var allowScreenshotsEnabled: Boolean = false
        private set

    /** Wire the production encrypted store. Idempotent — safe to call from Application.onCreate. */
    fun init(context: Context) {
        if (store == null) {
            store = EncryptedSettingsStore(context.applicationContext)
        }
    }

    /** Replace the store (tests / previews). */
    fun override(store: SettingsStore) {
        this.store = store
    }

    /** Refresh the synchronous caches from persisted settings (called once at app startup). */
    suspend fun warmCaches() {
        runCatching {
            val settings = settingsStore.load()
            relockOnBackgroundEnabled = settings.relockOnBackgroundEnabled
            allowScreenshotsEnabled = settings.allowScreenshotsEnabled
            // Hydrate the live theme (APP-525) from persisted values so the app opens on the
            // user's chosen mode + accent. Dark is the default, so there is no light→dark flash
            // while this async read completes.
            com.appblish.calculatorvault.ui.theme.ThemeController
                .apply(settings.themeMode, settings.accentColor)
        }
    }

    /** Keep [relockOnBackgroundEnabled] in step with a live toggle change from Settings. */
    fun cacheRelockOnBackground(enabled: Boolean) {
        relockOnBackgroundEnabled = enabled
    }

    /** Keep [allowScreenshotsEnabled] in step with a live toggle change from Settings. */
    fun cacheAllowScreenshots(enabled: Boolean) {
        allowScreenshotsEnabled = enabled
    }

    val settingsStore: SettingsStore
        get() = store ?: error("SettingsGraph.init(context) must be called before use")
}
