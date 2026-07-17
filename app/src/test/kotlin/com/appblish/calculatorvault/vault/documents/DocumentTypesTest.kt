package com.appblish.calculatorvault.vault.documents

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM contract tests for [DocumentKind.classify] (APP-527, spec [APP-522 §3]). The grid
 * type-icon and the open hand-off both depend on this classification, so every major doc
 * family the spec names (PDF, Word, Excel, PowerPoint, TXT, RTF, ZIP/RAR) must resolve
 * deterministically, extension must win over MIME, and anything unknown must fall back to
 * [DocumentKind.GENERIC] rather than mis-badge.
 */
class DocumentTypesTest {
    @Test
    fun `each major spec type resolves by extension`() {
        assertThat(DocumentKind.classify("report.pdf")).isEqualTo(DocumentKind.PDF)
        assertThat(DocumentKind.classify("Letter.doc")).isEqualTo(DocumentKind.WORD)
        assertThat(DocumentKind.classify("Letter.docx")).isEqualTo(DocumentKind.WORD)
        assertThat(DocumentKind.classify("budget.xls")).isEqualTo(DocumentKind.EXCEL)
        assertThat(DocumentKind.classify("budget.xlsx")).isEqualTo(DocumentKind.EXCEL)
        assertThat(DocumentKind.classify("deck.ppt")).isEqualTo(DocumentKind.POWERPOINT)
        assertThat(DocumentKind.classify("deck.pptx")).isEqualTo(DocumentKind.POWERPOINT)
        assertThat(DocumentKind.classify("notes.txt")).isEqualTo(DocumentKind.TEXT)
        assertThat(DocumentKind.classify("styled.rtf")).isEqualTo(DocumentKind.RICH_TEXT)
        assertThat(DocumentKind.classify("archive.zip")).isEqualTo(DocumentKind.ARCHIVE)
        assertThat(DocumentKind.classify("archive.rar")).isEqualTo(DocumentKind.ARCHIVE)
        assertThat(DocumentKind.classify("archive.7z")).isEqualTo(DocumentKind.ARCHIVE)
    }

    @Test
    fun `extension match is case-insensitive`() {
        assertThat(DocumentKind.classify("REPORT.PDF")).isEqualTo(DocumentKind.PDF)
        assertThat(DocumentKind.classify("Deck.PPTX")).isEqualTo(DocumentKind.POWERPOINT)
    }

    @Test
    fun `extension wins over a mismatched mime`() {
        // A user-renamed file: extension is the format signal, not the stale MIME.
        assertThat(DocumentKind.classify("real.pdf", "application/octet-stream"))
            .isEqualTo(DocumentKind.PDF)
    }

    @Test
    fun `mime is used when the extension is absent or unknown`() {
        assertThat(DocumentKind.classify("noextension", "application/pdf"))
            .isEqualTo(DocumentKind.PDF)
        assertThat(DocumentKind.classify("data.bin", "application/vnd.ms-excel"))
            .isEqualTo(DocumentKind.EXCEL)
        assertThat(
            DocumentKind.classify(
                "slides",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ),
        ).isEqualTo(DocumentKind.POWERPOINT)
    }

    @Test
    fun `unknown types fall back to generic, never guess`() {
        assertThat(DocumentKind.classify("mystery.q7z9")).isEqualTo(DocumentKind.GENERIC)
        assertThat(DocumentKind.classify("noextension")).isEqualTo(DocumentKind.GENERIC)
        assertThat(DocumentKind.classify("", null)).isEqualTo(DocumentKind.GENERIC)
    }

    @Test
    fun `dotfiles with no real extension are generic, not classified by the leading dot`() {
        assertThat(DocumentKind.classify(".gitignore")).isEqualTo(DocumentKind.GENERIC)
    }
}
