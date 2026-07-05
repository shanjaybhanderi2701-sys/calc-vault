package com.appblish.calculatorvault.vault.viewer

import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appblish.calculatorvault.ui.components.DeleteChoiceDialog
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import java.io.File

/**
 * Single-item viewer, dispatched by category: full-bleed image (Photos/Files preview),
 * Media3/ExoPlayer stage for video/audio, or contact card. Images/contacts render the
 * in-memory decrypted blob ([bytes]); video/audio play from [mediaFile], a temp file in
 * the app-private cache dir that the player stage deletes on dispose (spec §7 — decrypted
 * bytes never touch public storage in cleartext). The bottom bar offers **Restore** (back
 * to public storage, spec §8 vocabulary) and **Delete**, which routes through the shared
 * delete-choice modal (design call D-4) — no Share/export surface in Phase 1 (S18).
 */
@Composable
fun ItemViewerScreen(
    item: VaultItem,
    bytes: ByteArray?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    mediaFile: File? = null,
    onRestore: () -> Unit = {},
    onMoveToRecycleBin: () -> Unit = {},
    onDeletePermanently: () -> Unit = {},
) {
    val colors = VaultTheme.colors
    var showDeleteChoice by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = item.originalName, subtitle = item.dateLabel, onBack = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (item.category) {
                VaultCategory.VIDEOS, VaultCategory.AUDIOS -> MediaPlayerStage(item, mediaFile)
                VaultCategory.CONTACTS -> ContactStage(item, bytes)
                else -> ImageStage(item, bytes) // Photos + Files preview
            }
        }
        ViewerActionBar(onRestore = onRestore, onDelete = { showDeleteChoice = true })
    }
    if (showDeleteChoice) {
        DeleteChoiceDialog(
            itemCount = 1,
            onMoveToRecycleBin = {
                showDeleteChoice = false
                onMoveToRecycleBin()
            },
            onDeletePermanently = {
                showDeleteChoice = false
                onDeletePermanently()
            },
            onDismiss = { showDeleteChoice = false },
        )
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

/**
 * Plays a decrypted video/audio blob from [mediaFile] (app-private cache) via
 * Media3/ExoPlayer + [PlayerView] (spec §7 hard requirement). The player is released and
 * the cleartext temp file deleted when this stage leaves composition; the ViewModel's
 * onCleared() is the backstop for paths where the stage never composed.
 */
@Composable
private fun MediaPlayerStage(
    item: VaultItem,
    mediaFile: File?,
) {
    val colors = VaultTheme.colors
    if (mediaFile == null) {
        // Decrypt-to-cache still in flight (or blob missing): neutral placeholder.
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
    val context = LocalContext.current
    val player =
        remember(mediaFile) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(mediaFile)))
                prepare()
                playWhenReady = true
            }
        }
    DisposableEffect(mediaFile) {
        onDispose {
            player.release()
            // Remove the cleartext temp copy as soon as playback UI goes away.
            mediaFile.delete()
        }
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
                // Stable PlayerView surface only (customization setters are @UnstableApi
                // and would trip the UnsafeOptInUsageError lint gate).
                PlayerView(ctx).apply {
                    this.player = player
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
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

/**
 * Bottom action bar: Restore + Delete only (Share removed per S18 — decrypt-and-export is
 * out of Phase 1). Each action shows its one-word label under the icon so the spec §8
 * vocabulary ("Restore", never "unhide") is visible, not just an a11y description.
 */
@Composable
private fun ViewerActionBar(
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = VaultTheme.colors
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = VaultTheme.spacing.sm),
    ) {
        // Restore: decrypt back to public storage so it returns to the gallery.
        ViewerAction(label = "Restore", tint = colors.textPrimary, onClick = onRestore) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restore", tint = colors.textPrimary)
        }
        ViewerAction(label = "Delete", tint = colors.destructive, onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = colors.destructive)
        }
    }
}

@Composable
private fun ViewerAction(
    label: String,
    tint: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { icon() }
        Text(text = label, style = VaultTheme.typography.labelMedium, color = tint)
    }
}
