package com.appblish.calculatorvault.vault.actions

import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

/**
 * Proves the Property dialog's values are derived **only from the index record** (spec §1.3
 * / W1-E2 DoD) and formatted per design §9 — the blob is never involved here (pure Kotlin).
 */
class PhotoPropertiesTest {
    private fun photo(
        name: String = "IMG_2043.jpg",
        mime: String? = "image/jpeg",
        relativePath: String? = "DCIM/Camera/",
        size: Long = 4_404_019L,
        width: Int = 4032,
        height: Int = 3024,
        modified: Long = dateMillis(2026, Calendar.APRIL, 12),
        added: Long = dateMillis(2026, Calendar.JULY, 3),
        folderId: String? = null,
    ) = VaultItem(
        id = "v1",
        category = VaultCategory.PHOTOS,
        originalName = name,
        dateLabel = "Today",
        sortKey = added,
        folderId = folderId,
        sizeBytes = size,
        mimeType = mime,
        relativePath = relativePath,
        widthPx = width,
        heightPx = height,
        dateModifiedMs = modified,
    )

    private fun dateMillis(
        year: Int,
        month: Int,
        day: Int,
    ): Long =
        Calendar
            .getInstance()
            .apply {
                clear()
                set(year, month, day, 9, 0, 0)
            }.timeInMillis

    @Test
    fun `single rows read every field from the index in order`() {
        val rows = PhotoProperties.rows(photo(), albumName = "Camera album")
        val map = rows.associate { it.label to it.value }
        assertThat(rows.map { it.label })
            .containsExactly("Name", "Format", "Original", "In vault", "Size", "Resolution", "Modified", "Added")
            .inOrder()
        assertThat(map["Name"]).isEqualTo("IMG_2043.jpg")
        assertThat(map["Format"]).isEqualTo("JPEG")
        assertThat(map["Original"]).isEqualTo("DCIM/Camera")
        assertThat(map["In vault"]).isEqualTo("Camera album")
        assertThat(map["Size"]).isEqualTo("4.2 MB")
        assertThat(map["Resolution"]).isEqualTo("4032 × 3024")
        assertThat(map["Modified"]).isEqualTo("12 Apr 2026")
        assertThat(map["Added"]).isEqualTo("3 Jul 2026")
    }

    @Test
    fun `unknown fields render as an em-dash, never a blank or a lie`() {
        val bare =
            photo(mime = null, relativePath = null, size = 0L, width = 0, height = 0, modified = 0L)
        val map = PhotoProperties.rows(bare, albumName = null).associate { it.label to it.value }
        assertThat(map["Format"]).isEqualTo(PhotoProperties.UNKNOWN)
        assertThat(map["Original"]).isEqualTo(PhotoProperties.UNKNOWN)
        assertThat(map["Size"]).isEqualTo(PhotoProperties.UNKNOWN)
        assertThat(map["Resolution"]).isEqualTo(PhotoProperties.UNKNOWN)
        assertThat(map["Modified"]).isEqualTo(PhotoProperties.UNKNOWN)
        assertThat(map["In vault"]).isEqualTo("Album root")
    }

    @Test
    fun `format label falls back to the file extension when mime is absent`() {
        assertThat(PhotoProperties.formatLabel(null, "clip.MP4")).isEqualTo("MP4")
        assertThat(PhotoProperties.formatLabel("image/png", "x")).isEqualTo("PNG")
        assertThat(PhotoProperties.formatLabel(null, "noext")).isEqualTo(PhotoProperties.UNKNOWN)
    }

    @Test
    fun `size formats across unit boundaries`() {
        assertThat(PhotoProperties.formatSize(512)).isEqualTo("512 B")
        assertThat(PhotoProperties.formatSize(1536)).isEqualTo("1.5 KB")
        assertThat(PhotoProperties.formatSize(5L * 1024 * 1024)).isEqualTo("5.0 MB")
    }

    @Test
    fun `aggregate summarises count, total size, date range and format breakdown`() {
        val jan1 = dateMillis(2026, Calendar.JANUARY, 1)
        val feb2 = dateMillis(2026, Calendar.FEBRUARY, 2)
        val mar5 = dateMillis(2026, Calendar.MARCH, 5)
        val items =
            listOf(
                photo(name = "a.jpg", mime = "image/jpeg", size = 1_000_000, modified = jan1),
                photo(name = "b.png", mime = "image/png", size = 2_000_000, modified = mar5),
                photo(name = "c.jpg", mime = "image/jpeg", size = 3_000_000, modified = feb2),
            )
        val map = PhotoProperties.aggregateRows(items).associate { it.label to it.value }
        assertThat(map["Items"]).isEqualTo("3")
        assertThat(map["Total size"]).isEqualTo("5.7 MB")
        assertThat(map["Date range"]).isEqualTo("1 Jan 2026 – 5 Mar 2026")
        assertThat(map["Formats"]).isEqualTo("2 JPEG · 1 PNG")
    }
}
