package com.appblish.calculatorvault.intruder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production [IntruderLogStore]. The event index sits in its own [EncryptedSharedPreferences]
 * file (AES-256-GCM) — the log of who tried to break in and when is itself sensitive — and
 * the captured JPEGs are written to an app-private `intruder/` directory that no other app
 * can read. Photos are plain JPEGs (not blob-encrypted) in Phase 3; encrypting them at rest
 * is folded into the Phase 5 hardening pass alongside the vault index encryption.
 */
class EncryptedIntruderLogStore(
    context: Context,
) : BaseIntruderLogStore() {
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

    private val photoDir: File by lazy {
        File(appContext.filesDir, "intruder").apply { mkdirs() }
    }

    override suspend fun getIndex(): String? =
        withContext(Dispatchers.IO) {
            prefs.getString(KEY_INDEX, null)
        }

    override suspend fun setIndex(value: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_INDEX, value).apply()
        }

    override suspend fun clearIndex() =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_INDEX).apply()
        }

    override suspend fun persistPhoto(
        id: String,
        bytes: ByteArray,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(photoDir, "$id.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }

    override suspend fun deleteAllPhotos() {
        withContext(Dispatchers.IO) {
            photoDir.listFiles()?.forEach { it.delete() }
        }
    }

    private companion object {
        const val PREFS_NAME = "calcvault_intruder"
        const val KEY_INDEX = "intruder_index"
    }
}
