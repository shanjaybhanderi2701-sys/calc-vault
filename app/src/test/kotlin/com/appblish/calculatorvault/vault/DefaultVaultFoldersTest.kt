package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.DefaultVaultFolders
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the predefined default-folder catalog (APP-206): a fresh vault must seed the same
 * xlock / Figma-parity folders with stable ids so seeding is idempotent, and Contacts must
 * stay folderless to match the home tile's plain count.
 */
class DefaultVaultFoldersTest {
    @Test
    fun `seeds default folders for the media categories`() {
        val byCategory = DefaultVaultFolders.forFreshVault().groupBy { it.category }
        assertThat(byCategory[VaultCategory.PHOTOS]?.map { it.name })
            .containsExactly("Camera", "Screenshots")
        assertThat(byCategory[VaultCategory.VIDEOS]?.map { it.name }).containsExactly("Videos")
        assertThat(byCategory[VaultCategory.AUDIOS]?.map { it.name }).containsExactly("Music")
        assertThat(byCategory[VaultCategory.FILES]?.map { it.name }).containsExactly("Documents")
    }

    @Test
    fun `does not seed a Contacts folder`() {
        val categories = DefaultVaultFolders.forFreshVault().map { it.category }
        assertThat(categories).doesNotContain(VaultCategory.CONTACTS)
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
        assertThat(first).contains("seed_photos_camera")
    }
}
