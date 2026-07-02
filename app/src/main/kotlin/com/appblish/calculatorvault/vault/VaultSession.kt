package com.appblish.calculatorvault.vault

/**
 * Holds the passphrase (the PIN / entry code) for the current vault session so the
 * device-backed repository can derive the data key that unwraps the vault (see
 * [com.appblish.calculatorvault.vault.crypto.VaultKeyFile]).
 *
 * The passphrase is captured the moment the vault is opened from the calculator disguise
 * and lives only in memory for the session — it is never persisted. On this Phase-2 branch
 * the entry code is the debug `1234`; Phase-6 integration feeds the real
 * `auth.CredentialStore` PIN into [begin] at the same seam, and the storage layer needs no
 * change because it only ever sees "the passphrase".
 */
object VaultSession {
    @Volatile
    var passphrase: String? = null
        private set

    /** Record the passphrase for this session (called on vault entry). */
    fun begin(code: String) {
        passphrase = code
    }

    /** Forget the passphrase (leaving the vault / lock). */
    fun clear() {
        passphrase = null
    }
}
