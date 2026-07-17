package com.appblish.calculatorvault.vault.documents

import com.appblish.calculatorvault.vault.model.VaultCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-533 JVM DoD (design §6) for the two Documents seams that carry no Android runtime:
 * the **category model** (§2 — relabel FILES→"Documents" without breaking index compat, and
 * the new home surface list) and the **SAF import staging** (§2 — a picked `content://`
 * document becomes a [VaultCategory.FILES] staged item the shared `hide()` path can encrypt).
 * The classification itself is covered by [DocumentTypesTest]; the icon glyph and the
 * temp-file view hand-off are covered by the instrumented `DocumentsHandoffDoDTest`.
 */
class DocumentsCategoryDoDTest {
    @Test
    fun `FILES relabels to Documents but keeps its enum name for index compat`() {
        // The display string is the only thing that changed — the persisted index serializes
        // category via `name`, so the enum name MUST stay FILES or older vaults stop decoding.
        assertThat(VaultCategory.FILES.label).isEqualTo("Documents")
        assertThat(VaultCategory.FILES.name).isEqualTo("FILES")
    }

    @Test
    fun `home surface adds Documents without leaking FILES into the media-only PHASE1 list`() {
        // PHASE1 drives the MediaStore paths (import picker, thumbnails, seeded folders) and
        // must stay media-only; HOME is PHASE1 + Documents and drives the home tile grid.
        assertThat(VaultCategory.PHASE1)
            .containsExactly(
                VaultCategory.PHOTOS,
                VaultCategory.VIDEOS,
                VaultCategory.AUDIOS,
            ).inOrder()
        assertThat(VaultCategory.HOME)
            .containsExactly(
                VaultCategory.PHOTOS,
                VaultCategory.VIDEOS,
                VaultCategory.AUDIOS,
                VaultCategory.FILES,
            ).inOrder()
        // Contacts stays off every surface (spec §0).
        assertThat(VaultCategory.HOME).doesNotContain(VaultCategory.CONTACTS)
    }

    @Test
    fun `stage builds a FILES item carrying the source uri, mime, size, and recency key`() {
        val now = 1_700_000_000_000L
        val meta = DocumentImport.Meta(displayName = "Q3 report.pdf", mimeType = "application/pdf", sizeBytes = 4096L)

        val staged = DocumentImport.stage(meta, uriString = "content://docs/42", now = now, folderId = "folder-7")

        assertThat(staged.category).isEqualTo(VaultCategory.FILES)
        assertThat(staged.originalName).isEqualTo("Q3 report.pdf")
        assertThat(staged.mimeType).isEqualTo("application/pdf")
        assertThat(staged.sizeBytes).isEqualTo(4096L)
        assertThat(staged.sourceUri).isEqualTo("content://docs/42")
        assertThat(staged.sortKey).isEqualTo(now)
        assertThat(staged.folderId).isEqualTo("folder-7")
        // Blank id: the repository assigns the blob-derived id at hide time.
        assertThat(staged.id).isEmpty()
        // A real date-section header, never blank (drives the list subtitle + fast-scroll).
        assertThat(staged.dateLabel).isNotEmpty()
        // The staged item classifies to the icon the grid will render.
        assertThat(DocumentKind.classify(staged.originalName, staged.mimeType)).isEqualTo(DocumentKind.PDF)
    }

    @Test
    fun `stage defaults folderId to null for a category-root import`() {
        val staged =
            DocumentImport.stage(
                DocumentImport.Meta("notes.txt", "text/plain", 12L),
                uriString = "content://docs/1",
                now = 1L,
            )
        assertThat(staged.folderId).isNull()
    }
}
