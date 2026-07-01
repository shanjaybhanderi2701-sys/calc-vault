package com.appblish.calculatorvault.applock

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [AppLockStore]. The locked-package list, settings, and unlock timestamps are
 * written through [EncryptedSharedPreferences] (AES-256-GCM under a keystore-backed
 * [MasterKey]) — the same at-rest guarantee as the credential store, so which apps a user
 * hides behind the vault is itself confidential. Kept in its own prefs file so an AppLock
 * reset never touches credentials or vault content.
 */
class EncryptedAppLockStore(
    context: Context,
) : BaseAppLockStore() {
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
        const val PREFS_NAME = "calcvault_applock"
    }
}
