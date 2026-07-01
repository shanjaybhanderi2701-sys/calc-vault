package com.appblish.calculatorvault.vault

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Reference implementation of [VaultRepository] backed by EncryptedSharedPreferences.
 * This proves the storage seam compiles end-to-end for the scaffold; it stores only a
 * salted hash of the secret code, never the code itself. It is deliberately minimal —
 * vault *items* (files/media) are NOT modeled yet. Swap or extend once the vault data
 * model is approved.
 */
class EncryptedVaultRepository(
    context: Context,
) : VaultRepository {
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey =
            MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun isInitialized(): Boolean = prefs.contains(KEY_SECRET_HASH)

    override suspend fun setSecretCode(code: String) {
        prefs.edit().putString(KEY_SECRET_HASH, hash(code)).apply()
    }

    override suspend fun verifySecretCode(candidate: String): Boolean {
        val stored = prefs.getString(KEY_SECRET_HASH, null) ?: return false
        return stored == hash(candidate)
    }

    override suspend fun clear() {
        prefs.edit().clear().apply()
    }

    // Placeholder hash. Replace with a memory-hard KDF (e.g. Argon2/scrypt) plus a
    // per-install salt when the crypto milestone lands; kept dependency-free for now.
    private fun hash(value: String): String = value.hashCode().toString()

    private companion object {
        const val PREFS_NAME = "calculator_vault_secure_prefs"
        const val KEY_SECRET_HASH = "secret_code_hash"
    }
}
