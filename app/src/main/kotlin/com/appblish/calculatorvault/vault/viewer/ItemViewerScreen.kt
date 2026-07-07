package com.appblish.calculatorvault.vault.viewer

import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import java.io.File

/**
 * Single-item viewer, dispatched by category: full-bleed image (Photos/Files preview),
 * video player, audio player, or contact card. Each stage renders the **decrypted blob**
 * ([bytes]) streamed back through
 * [com.appblish.calculatorvault.vault.crypto.VaultCrypto]; while decryption is in flight
 * (or if the blob is missing) it shows a neutral placeholder. A shared bottom bar offers
 * the W1-E2 single-photo actions — Unhide · Move · Property · Delete — each routed to its
 * design §5–§9 dialog by the caller (see [PhotoActionsHost]).
 */
@Composable
fun ItemViewerScreen(
    item: VaultItem,
    bytes: ByteArray?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onUnhide: () -> Unit = {},
    onMove: () -> Unit = {},
    onProperty: () -> Unit = {},
) {
    val colors = VaultTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = item.originalName, subtitle = item.dateLabel, onBack = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (item.category) {
                VaultCategory.VIDEOS -> MediaPlayerStage(item, bytes, "mp4")
                VaultCategory.AUDIOS -> MediaPlayerStage(item, bytes, "m4a")
                VaultCategory.CONTACTS -> ContactStage(item, bytes)
                else -> ImageStage(item, bytes) // Photos + Files preview
            }
        }
        ViewerActionBar(onUnhide = onUnhide, onMove = onMove, onDelete = onDelete, onProperty = onProperty)
    }
}

/** Writes decrypted [bytes] to a private cache file once so a player/decoder can read it. */
@Composable
private fun rememberDecryptedFile(
    bytes: ByteArray?,
    id: String,
    suffix: String,
): File? {
    val context = LocalContext.current
    return remember(id, bytes) {
        if (bytes == null) {
            null
        } else {
            File(context.cacheDir, "view_$id.$suffix").apply { writeBytes(bytes) }
        }
    }
}

@Composable
private fun ImageStage(
    item: VaultItem,
    bytes: ByteArray?,
) {
    val colors = VaultTheme.colors
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(VaultTheme.spacing.lg)
                .aspectRatio(0.75f)
                .clip(VaultTheme.shapes.card)
                .background(colors.surfaceVariant),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.originalName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = item.category.icon(),
                contentDescription = null,
                tint = item.category.color(),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/** Plays a decrypted video/audio blob from a cache file via a VideoView + MediaController. */
@Composable
private fun MediaPlayerStage(
    item: VaultItem,
    bytes: ByteArray?,
    suffix: String,
) {
    val colors = VaultTheme.colors
    val file = rememberDecryptedFile(bytes, item.id, suffix)
    if (file == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(VaultTheme.spacing.lg)
                    .aspectRatio(16f / 9f)
                    .clip(VaultTheme.shapes.card)
                    .background(colors.surfaceVariant),
        ) {
            Icon(
                imageVector = item.category.icon(),
                contentDescription = null,
                tint = item.category.color(),
                modifier = Modifier.size(48.dp),
            )
        }
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.fillMaxWidth().padding(VaultTheme.spacing.lg),
    ) {
        Text(item.originalName, style = VaultTheme.typography.titleMedium, color = colors.textPrimary)
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(VaultTheme.shapes.card),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.absolutePath)
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { it.isLooping = false }
                    start()
                }
            },
        )
    }
}

@Composable
private fun ContactStage(
    item: VaultItem,
    bytes: ByteArray?,
) {
    val colors = VaultTheme.colors
    val vcard = remember(bytes) { bytes?.toString(Charsets.UTF_8) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.padding(VaultTheme.spacing.xxl),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp).clip(CircleShape).background(item.category.color().copy(alpha = 0.2f)),
        ) {
            Icon(
                imageVector = item.category.icon(),
                contentDescription = null,
                tint = item.category.color(),
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = item.originalName,
            style = VaultTheme.typography.headlineSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = vcard
                ?.lineSequence()
                ?.take(6)
                ?.joinToString("\n")
                ?.ifBlank { "Hidden contact" } ?: "Hidden contact",
            style = VaultTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ViewerActionBar(
    onUnhide: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onProperty: () -> Unit,
) {
    val colors = VaultTheme.colors
    // Design §4 bottom bar: Unhide · Delete · Move, with Property on the info affordance.
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = VaultTheme.spacing.sm),
    ) {
        // Un-hide: decrypt back to public storage so it returns to the gallery.
        IconButton(onClick = onUnhide) {
            Icon(Icons.Filled.Refresh, contentDescription = "Unhide", tint = colors.textPrimary)
        }
        IconButton(onClick = onMove) {
            Icon(Icons.Filled.List, contentDescription = "Move", tint = colors.textPrimary)
        }
        IconButton(onClick = onProperty) {
            Icon(Icons.Filled.Info, contentDescription = "Property", tint = colors.textPrimary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = colors.destructive)
        }
    }
}
