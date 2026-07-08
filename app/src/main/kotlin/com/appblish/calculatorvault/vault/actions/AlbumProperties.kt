package com.appblish.calculatorvault.vault.actions

import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem

/**
 * Pure builder for the Album property dialog's rows (W2-E design §8) — **no Android/Compose
 * deps** so "album property values come from the index" (spec §1.3, DoD) is directly
 * JVM-unit-testable, mirroring [PhotoProperties]. Every value derives from the encrypted
 * index records ([VaultFolder] + its member [VaultItem]s); no blob is ever decrypted to
 * open the dialog, and there is deliberately **no path row** — a vault album has no
 * filesystem path and printing an internal one would fake semantics the vault doesn't
 * have (design decision F-4).
 */
object AlbumProperties {
    /**
     * Single-album rows: Name · Photos · Size (sum of contents) · Created (added-to-vault
     * time of the label) · Modified (latest change — label create/rename or newest add).
     */
    fun rows(
        folder: VaultFolder,
        items: List<VaultItem>,
    ): List<PropertyRow> =
        listOf(
            PropertyRow("Name", folder.name.ifBlank { PhotoProperties.UNKNOWN }),
            PropertyRow("Photos", items.size.toString()),
            PropertyRow("Size", sizeOrZero(items)),
            PropertyRow("Created", PhotoProperties.absoluteDate(folder.createdAt)),
            PropertyRow("Modified", PhotoProperties.absoluteDate(modifiedAt(folder, items))),
        )

    /**
     * Aggregate rows for N selected albums (design §8): Albums · Photos · Size ·
     * Modified (earliest–latest range across the albums). No per-album rows — that's a
     * list, not a property dialog.
     */
    fun aggregateRows(
        folders: List<VaultFolder>,
        itemsByFolder: Map<String, List<VaultItem>>,
    ): List<PropertyRow> {
        if (folders.isEmpty()) return emptyList()
        val allItems = folders.flatMap { itemsByFolder[it.id].orEmpty() }
        val modified = folders.map { modifiedAt(it, itemsByFolder[it.id].orEmpty()) }.filter { it > 0L }
        val range =
            when {
                modified.isEmpty() -> PhotoProperties.UNKNOWN
                modified.min() == modified.max() -> PhotoProperties.absoluteDate(modified.min())
                else ->
                    "${PhotoProperties.absoluteDate(modified.min())} – ${PhotoProperties.absoluteDate(modified.max())}"
            }
        return listOf(
            PropertyRow("Albums", folders.size.toString()),
            PropertyRow("Photos", allItems.size.toString()),
            PropertyRow("Size", sizeOrZero(allItems)),
            PropertyRow("Modified", range),
        )
    }

    /** An empty album reads "0 MB" (honest, not an error) rather than the unknown em-dash. */
    private fun sizeOrZero(items: List<VaultItem>): String =
        if (items.isEmpty()) "0 MB" else PhotoProperties.formatSize(items.sumOf { it.sizeBytes })

    /** Latest change in the album: label create/rename vs the newest added-to-vault time. */
    private fun modifiedAt(
        folder: VaultFolder,
        items: List<VaultItem>,
    ): Long = maxOf(folder.modifiedAt, items.maxOfOrNull { it.sortKey } ?: 0L)
}
