package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.DefaultVaultFolders
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the predefined default-folder catalog (build spec §4, APP-225 — board-ruled on
 * APP-220): a fresh vault seeds each Phase-1 category (Photos, Videos, Audio) with exactly
 * one empty "Download" folder, with stable ids so seeding is idempotent. The out-of-phase
 * categories (Files, Contacts) seed nothing.
 *
 * Also guards the idempotent, category-scoped top-up ([DefaultVaultFolders.missingDefaults],
 * APP-225 board fix): stale indexes surviving uninstall in public storage get their missing
 * defaults on load, while any category already holding ≥1 folder is left alone.
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

    @Test
    fun `missing defaults of an empty vault equal the fresh seed`() {
        assertThat(DefaultVaultFolders.missingDefaults(existing = emptyList()))
            .isEqualTo(DefaultVaultFolders.forFreshVault())
    }

    @Test
    fun `a category with zero folders gets its Download back, populated ones are untouched`() {
        // Stale/migrated index: Videos and Audios kept folders, Photos has none.
        val existing =
            listOf(
                userFolder(VaultCategory.VIDEOS, "Holidays"),
                userFolder(VaultCategory.AUDIOS, "Voice notes"),
            )
        val added = DefaultVaultFolders.missingDefaults(existing)
        assertThat(added.map { it.category to it.name })
            .containsExactly(VaultCategory.PHOTOS to "Download")
        assertThat(added.single().id).isEqualTo("seed_photos_download")
    }

    @Test
    fun `old-build seed folders count as populated so no Download is added`() {
        // The legacy seed set (Camera/Screenshots/Videos/Music) from pre-Download builds:
        // every Phase-1 category already holds ≥1 folder, so the top-up adds NOTHING —
        // explicitly, no "Download" alongside the legacy names.
        val legacy =
            listOf(
                userFolder(VaultCategory.PHOTOS, "Camera"),
                userFolder(VaultCategory.PHOTOS, "Screenshots"),
                userFolder(VaultCategory.VIDEOS, "Videos"),
                userFolder(VaultCategory.AUDIOS, "Music"),
            )
        assertThat(DefaultVaultFolders.missingDefaults(legacy)).isEmpty()
    }

    @Test
    fun `non-Phase-1 folders neither seed nor suppress`() {
        // A FILES folder from an old build loads fine but is irrelevant to the Phase-1
        // top-up: all three Phase-1 categories are empty, so all three seed; FILES gets none.
        val existing = listOf(userFolder(VaultCategory.FILES, "Documents"))
        val added = DefaultVaultFolders.missingDefaults(existing)
        assertThat(added.map { it.category })
            .containsExactly(VaultCategory.PHOTOS, VaultCategory.VIDEOS, VaultCategory.AUDIOS)
        assertThat(added.map { it.name }).containsExactly("Download", "Download", "Download")
    }

    private fun userFolder(
        category: VaultCategory,
        name: String,
    ): VaultFolder = VaultFolder(id = "f-${category.name}-$name", category = category, name = name)
}
