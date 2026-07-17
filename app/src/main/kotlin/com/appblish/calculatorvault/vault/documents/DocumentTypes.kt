package com.appblish.calculatorvault.vault.documents

/**
 * Document-format classification for the Documents vault (APP-527, spec [APP-522 §3]).
 *
 * Unlike photos/videos, hidden documents show a **file-type icon** in the grid/list — no
 * image thumbnail — so the grid cell, the "open" hand-off, and the property sheet all need
 * to agree on what kind of file a [com.appblish.calculatorvault.vault.model.VaultItem] is.
 * That decision lives here as a pure function so it is unit-testable and has a single
 * source of truth (extension-first, MIME as a tiebreaker — a vault item's stored MIME can
 * be absent for SAF-picked files, and extensions are the format signal users recognize).
 *
 * This is the model seam only. The Compose glyph for each [DocumentKind] is rendered in the
 * UI layer (coordinated with the Principal Product Designer) — keep pixels out of here.
 */
enum class DocumentKind(
    /** Short badge label shown on the type icon (e.g. "PDF", "DOC"). */
    val badge: String,
    /** Accent hue (ARGB) for the type icon chip — distinct, muted per family. */
    val accent: Long,
) {
    PDF("PDF", 0xFFE53935),
    WORD("DOC", 0xFF1E88E5),
    EXCEL("XLS", 0xFF2E7D32),
    POWERPOINT("PPT", 0xFFEF6C00),
    TEXT("TXT", 0xFF607D8B),
    RICH_TEXT("RTF", 0xFF5E35B1),
    ARCHIVE("ZIP", 0xFF8D6E63),
    GENERIC("FILE", 0xFF546E7A),
    ;

    companion object {
        /**
         * Classify a hidden document from its original file name and (optional) stored MIME.
         * Extension wins because it is always present on the vault item's [originalName] and
         * is the format signal the user recognizes; MIME is consulted only when the extension
         * is unknown/absent. Falls back to [GENERIC] rather than guessing.
         */
        fun classify(fileName: String, mimeType: String? = null): DocumentKind {
            byExtension(fileName)?.let { return it }
            byMime(mimeType)?.let { return it }
            return GENERIC
        }

        private fun byExtension(fileName: String): DocumentKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase().trim()
            if (ext.isEmpty() || ext == fileName.lowercase()) return null
            return when (ext) {
                "pdf" -> PDF
                "doc", "docx", "dot", "dotx", "odt" -> WORD
                "xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "csv" -> EXCEL
                "ppt", "pptx", "pps", "ppsx", "pot", "potx", "odp" -> POWERPOINT
                "txt", "log", "md", "ini", "json", "xml", "yaml", "yml" -> TEXT
                "rtf" -> RICH_TEXT
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz" -> ARCHIVE
                else -> null
            }
        }

        private fun byMime(mimeType: String?): DocumentKind? {
            val mime = mimeType?.lowercase()?.trim().orEmpty()
            if (mime.isEmpty()) return null
            return when {
                mime == "application/pdf" -> PDF
                mime.contains("word") || mime.contains("opendocument.text") -> WORD
                mime.contains("excel") || mime.contains("spreadsheet") || mime == "text/csv" -> EXCEL
                mime.contains("powerpoint") || mime.contains("presentation") -> POWERPOINT
                mime == "application/rtf" || mime == "text/rtf" -> RICH_TEXT
                mime.startsWith("text/") -> TEXT
                mime.contains("zip") || mime.contains("compressed") ||
                    mime.contains("rar") || mime.contains("tar") || mime.contains("gzip") -> ARCHIVE
                else -> null
            }
        }
    }
}
