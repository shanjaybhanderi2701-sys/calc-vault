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

    val settingsStore: SettingsStore
        get() = store ?: error("SettingsGraph.init(context) must be called before use")
}
