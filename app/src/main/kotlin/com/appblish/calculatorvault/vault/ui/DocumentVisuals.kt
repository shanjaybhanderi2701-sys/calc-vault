package com.appblish.calculatorvault.vault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.vault.documents.DocumentKind

/*
 * The Compose glyphs for the Documents category (APP-527 §5 seam 1). Documents show a
 * file-type icon in the grid/list, never a decrypted image thumbnail — the icon is a single
 * folded-corner page glyph tinted by the format family's accent, with the format's short
 * badge ("PDF", "DOC", …) laid over the page face. `material-icons-core` is the only icon
 * artifact on the classpath and lacks a document glyph, so the page is built here from
 * standard Material `InsertDriveFile` path data via the core builders (same pattern as the
 * category Photo/MusicNote glyphs). The classification itself — the single source of truth
 * for both this icon and the viewer MIME dispatch — lives in DocumentKind.classify.
 */

/** The folded-corner document page — Material `Filled.InsertDriveFile`, built from core. */
private val DocumentPageGlyph: ImageVector by lazy {
    materialIcon(name = "Vault.Document") {
        materialPath {
            moveTo(6.0f, 2.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(4.0f, 20.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
            horizontalLineTo(18.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            lineToRelative(-6.0f, -6.0f)
            horizontalLineTo(6.0f)
            close()
            moveTo(13.0f, 9.0f)
            verticalLineTo(3.5f)
            lineTo(18.5f, 9.0f)
            horizontalLineTo(13.0f)
            close()
        }
    }
}

/** The type-icon glyph for a document format (the page; the badge is drawn over it). */
fun DocumentKind.icon(): ImageVector = DocumentPageGlyph

/** The format family's accent hue as a Compose [Color] (from [DocumentKind.accent]). */
fun DocumentKind.color(): Color = Color(accent)

/**
 * The Documents grid/list type-icon: the folded-corner page tinted by the format accent
 * with the short [DocumentKind.badge] laid over the page face. No blob is ever decrypted to
 * render it — the whole point of the Documents surface (spec §3). [size] sizes the page; the
 * badge scales with it so a small list-leading icon and a larger grid tile both stay legible.
 */
@Composable
fun DocumentTypeIcon(
    kind: DocumentKind,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val accent = kind.color()
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        Icon(
            imageVector = kind.icon(),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size),
        )
        // Badge text sits over the lower page face; sized as a fraction of the page height so
        // "PDF"/"XLS" stay inside the glyph at any icon size. White ink reads on every accent.
        Text(
            text = kind.badge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            fontSize = TextUnit(size.value * 0.22f, TextUnitType.Sp),
            modifier = Modifier.padding(top = size * 0.28f),
        )
    }
}
