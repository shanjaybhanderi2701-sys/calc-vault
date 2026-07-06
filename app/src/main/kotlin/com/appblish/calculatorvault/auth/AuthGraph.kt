package com.appblish.calculatorvault.auth

import android.content.Context
import com.appblish.calculatorvault.vault.crypto.VaultKeyFileReKeyer
import com.appblish.calculatorvault.vault.crypto.VaultReKeyer

/**
 * Minimal service locator for the single [CredentialStore], mirroring
 * [com.appblish.calculatorvault.vault.VaultGraph]. A real DI graph (Hilt) replaces this in
 * the hardening phase; until then this is how the calculator/onboarding/settings view
 * models — which are created by the argument-less Compose `viewModel()` factory — reach
 * the store.
 *
 * [init] is called once from [com.appblish.calculatorvault.CalculatorVaultApp]; tests use
 * [override] to inject an [InMemoryCredentialStore].
 */
object AuthGraph {
    @Volatile
    private var store: CredentialStore? = null

    @Volatile
    private var reKeyer: VaultReKeyer? = null

    /** Wire the production encrypted store. Idempotent — safe to call from Application.onCreate. */
    fun init(context: Context) {
        if (store == null) {
            store = EncryptedCredentialStore(context.applicationContext)
        }
        if (reKeyer == null) {
            reKeyer = VaultKeyFileReKeyer(context.applicationContext)
        }
    }

    /** Replace the store (tests / previews). */
    fun override(store: CredentialStore) {
        this.store = store
    }

    /** Replace the re-keyer (tests / previews). */
    fun overrideReKeyer(reKeyer: VaultReKeyer) {
        this.reKeyer = reKeyer
    }

    val credentialStore: CredentialStore
        get() = store ?: error("AuthGraph.init(context) must be called before use")

    /**
     * The change-PIN envelope re-keyer (APP-245). Lives beside the credential store because
     * it guards the credential commit: [CredentialStore.setRealPin] must never run on a PIN
     * change unless the `.vaultkey` envelope moved to the new PIN first.
     */
    val vaultReKeyer: VaultReKeyer
        get() = reKeyer ?: error("AuthGraph.init(context) must be called before use")
}
