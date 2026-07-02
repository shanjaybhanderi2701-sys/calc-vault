package com.appblish.calculatorvault.vault.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Keystore-backed persistence for the vault's 256-bit AES data key.
 *
 * The raw data key is generated once on first run ([VaultCrypto.newKey]) and stored,
 * base64-encoded, inside [EncryptedSharedPreferences]. Those prefs are encrypted at rest
 * with a [MasterKey] that lives in the AndroidKeyStore (hardware-backed where the device
 * supports it), so the data key never touches disk in cleartext. [VaultCrypto] then uses
 * the returned [SecretKey] with a fresh per-blob IV.
 *
 * A single stable data key (rather than a keystore-resident AES key used directly) keeps
 * [VaultCrypto]'s self-describing IV-prefixed stream format — AndroidKeyStore GCM keys
 * reject caller-supplied IVs on encrypt — while still binding the key's secrecy to the
 * platform keystore.
 */
class VaultKeyStore(
    context: Context,
) {
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

    /** Load the persisted data key, generating and storing one on first access. */
    @Synchronized
    fun secretKey(): SecretKey {
        prefs.getString(KEY_DATA_KEY, null)?.let { encoded ->
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            return SecretKeySpec(raw, "AES")
        }
        val generated = VaultCrypto.newKey()
        prefs
            .edit()
            .putString(KEY_DATA_KEY, Base64.encodeToString(generated.encoded, Base64.NO_WRAP))
            .apply()
        return generated
    }

    private companion object {
        const val PREFS_NAME = "calculator_vault_data_key"
        const val KEY_DATA_KEY = "vault_aes_key"
    }
}
