package com.appblish.calculatorvault.vault

/**
 * Minimal service locator for the vault-content singleton. Deliberately tiny — a real
 * DI graph (Hilt) attaches in the hardening phase; until then the whole vault shares one
 * [InMemoryVaultContentRepository] so navigation between home, categories, viewers, and
 * the recycle bin observes the same state.
 */
object VaultGraph {
    val contentRepository: VaultContentRepository by lazy { InMemoryVaultContentRepository() }
}
