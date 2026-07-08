package com.appblish.calculatorvault.vault.actions

import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Album property dialog's values come from the encrypted index records only (spec
 * §1.3, W2-E design §8): pure arithmetic over [VaultFolder] + member [VaultItem]s, with a
 * deliberate absence of any path row (design F-4) and an honest "0 MB" for empty albums.
 */
class AlbumPropertiesTest {
    // 2026-04-12 / 2026-07-07 in epoch millis (UTC), well clear of timezone day-boundaries.
    private val april12 = 1_776_320_000_000L
    private val july7 = 1_783_800_000_000L

    private fun folder(
        name: String = "Camera",
        createdAt: Long = april12,
        modifiedAt: Long = april12,
    ) = VaultFolder(
        id = "f1",
        category = VaultCategory.PHOTOS,
        name = name,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )

    private fun item(
        id: String,
        sizeBytes: Long,
        sortKey: Long = april12,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
        sizeBytes = sizeBytes,
        folderId = "f1",
    )

    @Test
    fun `single album rows carry name, count, summed size and both dates`() {
        val rows =
            AlbumProperties.rows(
                folder(),
                listOf(item("a", sizeBytes = 1_048_576L), item("b", sizeBytes = 1_048_576L, sortKey = july7)),
            )

        assertThat(rows.map { it.label }).containsExactly("Name", "Photos", "Size", "Created", "Modified").inOrder()
        assertThat(rows[0].value).isEqualTo("Camera")
        assertThat(rows[1].value).isEqualTo("2")
        assertThat(rows[2].value).isEqualTo("2.0 MB")
        assertThat(rows[3].value).isEqualTo(PhotoProperties.absoluteDate(april12))
        // Modified is the LATEST change — the newest add wins over the label stamp here.
        assertThat(rows[4].value).isEqualTo(PhotoProperties.absoluteDate(july7))
    }

    @Test
    fun `label rename after the newest add wins the modified row`() {
        val rows = AlbumProperties.rows(folder(modifiedAt = july7), listOf(item("a", 10L, sortKey = april12)))
        assertThat(rows[4].value).isEqualTo(PhotoProperties.absoluteDate(july7))
    }

    @Test
    fun `empty album reads zero photos and an honest 0 MB, never an error dash`() {
        val rows = AlbumProperties.rows(folder(), emptyList())
        assertThat(rows[1].value).isEqualTo("0")
        assertThat(rows[2].value).isEqualTo("0 MB")
    }

    @Test
    fun `legacy album without stamps shows the unknown dash for created`() {
        val rows = AlbumProperties.rows(folder(createdAt = 0L, modifiedAt = 0L), emptyList())
        assertThat(rows[3].value).isEqualTo(PhotoProperties.UNKNOWN)
        assertThat(rows[4].value).isEqualTo(PhotoProperties.UNKNOWN)
    }

    @Test
    fun `no row ever exposes a path — a vault album has none`() {
        val rows = AlbumProperties.rows(folder(), listOf(item("a", 10L)))
        assertThat(rows.map { it.label }).containsNoneOf("Original", "Path", "Location")
    }

    @Test
    fun `aggregate rows sum across albums and range the modified dates`() {
        val f1 = folder(name = "A").copy(id = "f1", modifiedAt = april12)
        val f2 = folder(name = "B").copy(id = "f2", modifiedAt = july7)
        val rows =
            AlbumProperties.aggregateRows(
                listOf(f1, f2),
                mapOf(
                    "f1" to listOf(item("a", 512L)),
                    "f2" to listOf(item("b", 512L), item("c", 1_024L)),
                ),
            )

        assertThat(rows.map { it.label }).containsExactly("Albums", "Photos", "Size", "Modified").inOrder()
        assertThat(rows[0].value).isEqualTo("2")
        assertThat(rows[1].value).isEqualTo("3")
        assertThat(rows[2].value).isEqualTo("2.0 KB")
        assertThat(rows[3].value)
            .isEqualTo("${PhotoProperties.absoluteDate(april12)} – ${PhotoProperties.absoluteDate(july7)}")
    }

    @Test
    fun `aggregate of nothing is empty`() {
        assertThat(AlbumProperties.aggregateRows(emptyList(), emptyMap())).isEmpty()
    }
}
