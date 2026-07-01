package com.appblish.calculatorvault.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [SettingsStore]. Values live in [EncryptedSharedPreferences] under the same
 * hardware-keystore [MasterKey] the credential store uses, so the hardening switches are
 * AES-256-GCM encrypted at rest and cannot be read or toggled by inspecting plaintext
 * prefs. All work runs on [Dispatchers.IO] because opening the encrypted prefs blocks.
 */
class EncryptedSettingsStore(
    context: Context,
) : BaseSettingsStore() {
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

    override suspend fun allValues(): Map<String, String> =
        withContext(Dispatchers.IO) {
            prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        }

    override suspend fun replaceAll(values: Map<String, String>) =
        withContext(Dispatchers.IO) {
            prefs
                .edit()
                .apply {
                    clear()
                    values.forEach { (k, v) -> putString(k, v) }
                }.apply()
        }

    private companion object {
        const val PREFS_NAME = "calcvault_settings"
    }
}
