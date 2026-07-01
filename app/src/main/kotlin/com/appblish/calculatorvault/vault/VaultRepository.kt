package com.appblish.calculatorvault.vault

/**
 * Boundary for encrypted vault storage. Intentionally schema-light for the scaffold:
 * it exposes only what the navigation skeleton needs (is the vault initialized, can a
 * secret be set/verified) so screens can compile and be wired without committing to a
 * file/DB layout. The concrete encryption scheme, item model, and media handling are
 * decided in later milestones against the approved wireframe (APP-142).
 *
 * All calls are suspend so implementations can back onto encrypted storage /
 * key-store operations off the main thread without changing the interface.
 */
interface VaultRepository {
    /** True once a secret code has been configured (first-run vs. returning user). */
    suspend fun isInitialized(): Boolean

    /** Store the user's secret code (hashed by the implementation, never in cleartext). */
    suspend fun setSecretCode(code: String)

    /** Verify a candidate secret code against the stored one. */
    suspend fun verifySecretCode(candidate: String): Boolean

    /** Wipe all vault contents and the stored secret. Irreversible. */
    suspend fun clear()
}
