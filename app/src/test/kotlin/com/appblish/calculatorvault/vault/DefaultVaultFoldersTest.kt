package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.DefaultVaultFolders
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the predefined default-folder catalog (build spec §4, APP-225 — board-ruled on
 * APP-220): a fresh vault seeds each Phase-1 category (Photos, Videos, Audio) with exactly
 * one empty "Download" folder, with stable ids so seeding is idempotent. The out-of-phase
 * categories (Files, Contacts) seed nothing.
 */
class DefaultVaultFoldersTest {
    @Test
    fun `seeds one Download folder per Phase-1 category`() {
        val byCategory = DefaultVaultFolders.forFreshVault().groupBy { it.category }
        VaultCategory.PHASE1.forEach { category ->
            assertThat(byCategory[category]?.map { it.name }).containsExactly("Download")
        }
    }

    @Test
    fun `seeds nothing outside the Phase-1 categories`() {
        val categories = DefaultVaultFolders.forFreshVault().map { it.category }
        assertThat(categories).containsNoneOf(VaultCategory.FILES, VaultCategory.CONTACTS)
    }

    @Test
    fun `ids are stable and unique so re-seeding never duplicates`() {
        val first = DefaultVaultFolders.forFreshVault().map { it.id }
        val second = DefaultVaultFolders.forFreshVault().map { it.id }
        // Deterministic across calls (idempotent seeding key)…
        assertThat(second).isEqualTo(first)
        // …and each folder has a distinct id.
        assertThat(first.toSet()).hasSize(first.size)
        // Derived from the seed convention, not random UUIDs.
        assertThat(first).contains("seed_photos_download")
    }
}
