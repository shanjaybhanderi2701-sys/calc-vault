package com.appblish.calculatorvault.vault

/**
 * Holds the passphrase (the PIN / entry code) and storage namespace for the current vault
 * session so the device-backed repository can derive the data key that unwraps the vault
 * (see [com.appblish.calculatorvault.vault.crypto.VaultKeyFile]) from the right
 * `.CalcVault/` directory (see [com.appblish.calculatorvault.vault.storage.VaultStorage]).
 *
 * Captured the moment the vault is opened from the calculator disguise and kept only in
 * memory for the session — never persisted. Phase 6 feeds the real `auth.CredentialStore`
 * PIN into [begin]; the resolved [com.appblish.calculatorvault.auth.VaultKind] determines
 * the [namespace] (empty for the real vault, `decoy_<slot>` for each decoy) so distinct
 * spaces live in distinct sub-directories and can never read one another's content.
 */
object VaultSession {
    @Volatile
    var passphrase: String? = null
        private set

    /**
     * Sub-directory under `.CalcVault/` for the active vault. Empty string == the root
     * (real) vault, kept byte-compatible with the approved survive-uninstall layout.
     */
    @Volatile
    var namespace: String = ""
        private set

    /**
     * Record the passphrase and storage namespace for this session (called on vault entry).
     * [namespace] defaults to the root vault so existing callers/tests that only pass a code
     * keep the original single-vault behaviour.
     */
    fun begin(
        code: String,
        namespace: String = "",
    ) {
        passphrase = code
        this.namespace = namespace
    }

    /** Forget the passphrase + namespace (leaving the vault / lock). */
    fun clear() {
        passphrase = null
        namespace = ""
        // A re-lock ends the session, so the recovery nag flags reset to their "next
        // launch" state (banner returns, intro sheet eligible again) — PIN Recovery §4.
        com.appblish.calculatorvault.recovery.RecoveryPromptState
            .reset()
    }
}
