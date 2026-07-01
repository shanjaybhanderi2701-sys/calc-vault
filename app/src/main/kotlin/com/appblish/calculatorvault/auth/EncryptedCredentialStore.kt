package com.appblish.calculatorvault.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The production [CredentialStore]. Every value — the PBKDF2 PIN tokens, the decoy slot
 * list, the recovery material — is written through [EncryptedSharedPreferences], so at
 * rest it is AES-256-GCM encrypted under a hardware-keystore-backed [MasterKey]. This is
 * the "encrypted store foundation (AES-256)" the phase calls for; the same MasterKey is
 * the anchor a SQLCipher item database or the [com.appblish.calculatorvault.vault.crypto]
 * blob key will hang off in later phases.
 *
 * All work runs on [Dispatchers.IO]: opening the encrypted prefs and PBKDF2 hashing are
 * both blocking, so they must stay off the main thread.
 */
class EncryptedCredentialStore(
    context: Context,
) : BaseCredentialStore() {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
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

    override suspend fun getValue(key: String): String? =
        withContext(Dispatchers.IO) {
            prefs.getString(key, null)
        }

    override suspend fun setValue(
        key: String,
        value: String,
    ) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun removeValue(key: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(key).apply()
        }

    override suspend fun clearValues() =
        withContext(Dispatchers.IO) {
            prefs.edit().clear().apply()
        }

    private companion object {
        const val PREFS_NAME = "calcvault_credentials"
    }
}
