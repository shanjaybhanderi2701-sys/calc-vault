package com.appblish.calculatorvault.auth

import android.content.Context

/**
 * Minimal service locator for the single [CredentialStore], mirroring
 * [com.appblish.calculatorvault.vault.VaultGraph]. A real DI graph (Hilt) replaces this in
 * the hardening phase; until then this is how the calculator/onboarding/recovery view
 * models — which are created by the argument-less Compose `viewModel()` factory — reach
 * the store.
 *
 * [init] is called once from [com.appblish.calculatorvault.CalculatorVaultApp]; tests use
 * [override] to inject an [InMemoryCredentialStore].
 */
object AuthGraph {
    @Volatile
    private var store: CredentialStore? = null

    /** Wire the production encrypted store. Idempotent — safe to call from Application.onCreate. */
    fun init(context: Context) {
        if (store == null) {
            store = EncryptedCredentialStore(context.applicationContext)
        }
    }

    /** Replace the store (tests / previews). */
    fun override(store: CredentialStore) {
        this.store = store
    }

    val credentialStore: CredentialStore
        get() = store ?: error("AuthGraph.init(context) must be called before use")
}
