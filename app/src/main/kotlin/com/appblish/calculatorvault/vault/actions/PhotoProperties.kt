package com.appblish.calculatorvault.vault.actions

import com.appblish.calculatorvault.vault.model.VaultItem
import java.util.Calendar
import java.util.Locale

/** One key/value row in the Property dialog. */
data class PropertyRow(
    val label: String,
    val value: String,
)

/**
 * Pure builder for the Property dialog's rows (design §9) — **no Android/Compose deps** so
 * "property values come from the index" (spec §1.3, DoD) is directly JVM-unit-testable.
 * Every value is read from the [VaultItem] index record; the encrypted blob is never
 * touched. Unknown fields (older items, non-image categories) render as a muted em-dash
 * rather than a lie or a blank.
 */
object PhotoProperties {
    const val UNKNOWN = "—"

    /** Single-item detail rows, in the design's fixed order. */
    fun rows(
        item: VaultItem,
        albumName: String?,
    ): List<PropertyRow> =
        listOf(
            PropertyRow("Name", item.originalName.ifBlank { UNKNOWN }),
            PropertyRow("Format", formatLabel(item.mimeType, item.originalName)),
            PropertyRow("Original", pathOrUnknown(item.relativePath)),
            PropertyRow("In vault", albumName?.ifBlank { null } ?: "Album root"),
            PropertyRow("Size", formatSize(item.sizeBytes)),
            PropertyRow("Resolution", formatResolution(item.widthPx, item.heightPx)),
            PropertyRow("Modified", absoluteDate(item.dateModifiedMs)),
            PropertyRow("Added", absoluteDate(item.sortKey)),
        )

    /**
     * Aggregate rows for a multi-selection (design §9 aggregate): count, total size, the
     * earliest–latest modified range, and a format breakdown. Also index-only.
     */
    fun aggregateRows(items: List<VaultItem>): List<PropertyRow> {
        if (items.isEmpty()) return emptyList()
        val totalSize = items.sumOf { it.sizeBytes }
        val dates = items.map { it.dateModifiedMs.takeIf { d -> d > 0L } ?: it.sortKey }.filter { it > 0L }
        val range =
            when {
                dates.isEmpty() -> UNKNOWN
                dates.min() == dates.max() -> absoluteDate(dates.min())
                else -> "${absoluteDate(dates.min())} – ${absoluteDate(dates.max())}"
            }
        return listOf(
            PropertyRow("Items", items.size.toString()),
            PropertyRow("Total size", formatSize(totalSize)),
            PropertyRow("Date range", range),
            PropertyRow("Formats", formatBreakdown(items)),
        )
    }

    /** "image/jpeg" → "JPEG"; falls back to the file extension, then em-dash. */
    fun formatLabel(
        mimeType: String?,
        name: String,
    ): String {
        val sub = mimeType?.substringAfterLast('/', "")?.trim().orEmpty()
        val normalized =
            when (sub.lowercase(Locale.US)) {
                "" -> ""
                "jpeg", "jpg" -> "JPEG"
                "svg+xml" -> "SVG"
                "x-ms-wmv" -> "WMV"
                else -> sub.uppercase(Locale.US)
            }
        if (normalized.isNotBlank()) return normalized
        val ext = name.substringAfterLast('.', "").trim()
        return if (ext.isNotBlank() && ext.length <= 5) ext.uppercase(Locale.US) else UNKNOWN
    }

    /** Human byte size: 0 → em-dash; then B / KB / MB / GB with one decimal above KB. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return UNKNOWN
        if (bytes < 1024L) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.lastIndex) {
            value /= 1024.0
            unitIdx++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIdx])
    }

    /** "4032 × 3024", or em-dash when either dimension is unknown. */
    fun formatResolution(
        width: Int,
        height: Int,
    ): String = if (width > 0 && height > 0) "$width × $height" else UNKNOWN

    /** "12 Apr 2026", or em-dash for a missing/zero timestamp. */
    fun absoluteDate(millis: Long): String {
        if (millis <= 0L) return UNKNOWN
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val months =
            arrayOf(
                "Jan",
                "Feb",
                "Mar",
                "Apr",
                "May",
                "Jun",
                "Jul",
                "Aug",
                "Sep",
                "Oct",
                "Nov",
                "Dec",
            )
        return "${cal.get(Calendar.DAY_OF_MONTH)} ${months[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)}"
    }

    private fun pathOrUnknown(relativePath: String?): String =
        relativePath?.trim()?.trim('/')?.ifBlank { null } ?: UNKNOWN

    /** "3 JPEG · 1 PNG" ordered by descending count, then label. */
    private fun formatBreakdown(items: List<VaultItem>): String {
        val counts =
            items
                .groupingBy { formatLabel(it.mimeType, it.originalName) }
                .eachCount()
        if (counts.isEmpty()) return UNKNOWN
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(" · ") { "${it.value} ${it.key}" }
    }
}
