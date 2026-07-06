package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Home-dashboard polish contracts from the APP-234 spec (implemented under APP-238):
 * the first-run hint's gating ignores seeded folders (§1.1), the dual-count subtitles
 * pluralize properly (§1.3), and the video duration badge formats sanely (§2.2).
 */
class VaultHomeStateTest {
    private fun item(id: String) =
        VaultItem(
            id = id,
            category = VaultCategory.PHOTOS,
            originalName = "IMG_$id.jpg",
            dateLabel = "Today",
            sortKey = 1L,
        )

    @Test
    fun `fresh vault with seeded folders still counts as empty for the first-run hint`() {
        // Every fresh vault seeds one Download folder per Phase-1 category (APP-206), so
        // the hint gate must ignore folder counts entirely — otherwise it never shows.
        val state =
            VaultHomeState(
                folderCounts = VaultCategory.PHASE1.associateWith { 1 },
            )
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun `any hidden item suppresses the first-run hint`() {
        val withCount =
            VaultHomeState(counts = mapOf(VaultCategory.PHOTOS to 1))
        assertThat(withCount.isEmpty).isFalse()
    }

    @Test
    fun `stale surviving vault content suppresses the first-run hint via recent`() {
        // A previous install's .CalcVault/ floods `recent` on first unlock (spec defect 6):
        // the hint must stay hidden even though this session hid nothing.
        val withRecent = VaultHomeState(recent = listOf(item("stale")))
        assertThat(withRecent.isEmpty).isFalse()
    }

    @Test
    fun `dual-count subtitles pluralize both nouns`() {
        assertThat(categorySubtitle(VaultCategory.VIDEOS, items = 0, folders = 1))
            .isEqualTo("0 Videos / 1 Folder")
        assertThat(categorySubtitle(VaultCategory.PHOTOS, items = 1, folders = 8))
            .isEqualTo("1 Photo / 8 Folders")
        assertThat(categorySubtitle(VaultCategory.AUDIOS, items = 300, folders = 0))
            .isEqualTo("300 Audios / 0 Folders")
    }

    @Test
    fun `bin subtitle pluralizes items`() {
        assertThat(pluralize(1, "items")).isEqualTo("1 item")
        assertThat(pluralize(2, "items")).isEqualTo("2 items")
    }

    @Test
    fun `duration badge formats minutes and hours`() {
        assertThat(formatDuration(0L)).isEqualTo("0:00")
        assertThat(formatDuration(59_000L)).isEqualTo("0:59")
        assertThat(formatDuration(61_000L)).isEqualTo("1:01")
        assertThat(formatDuration(3_600_000L)).isEqualTo("1:00:00")
        assertThat(formatDuration(3_661_000L)).isEqualTo("1:01:01")
    }
}
