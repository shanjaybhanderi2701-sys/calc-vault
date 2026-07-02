package com.appblish.calculatorvault.vault

import android.content.Context

/**
 * Minimal service locator for the vault-content singleton. Deliberately tiny — a real
 * DI graph (Hilt) attaches in the hardening phase. [init] (called from the Application)
 * installs the device-backed [EncryptedVaultContentRepository] so the whole vault shares
 * one instance and observes consistent state across home, categories, viewers, and the
 * recycle bin.
 *
 * If [init] was never called (Compose `@Preview`, unit tests), the getter falls back to
 * an [InMemoryVaultContentRepository] so screens still render seeded sample content.
 */
object VaultGraph {
    @Volatile
    private var repository: VaultContentRepository? = null

    /** Install the device-backed repository. Idempotent; safe to call from onCreate. */
    fun init(context: Context) {
        if (repository == null) {
            synchronized(this) {
                if (repository == null) {
                    repository = EncryptedVaultContentRepository(context.applicationContext)
                }
            }
        }
    }

    val contentRepository: VaultContentRepository
        get() = repository ?: synchronized(this) {
            repository ?: InMemoryVaultContentRepository().also { repository = it }
        }
}
