package com.appblish.calculatorvault.vault

import android.Manifest
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * The shared hide/import flow: **album picker → date-grouped multi-select → Hide Now**.
 * Requests the runtime media/contacts permission on entry, loads the real album/date
 * groups from public storage, and on **Hide Now** encrypts the chosen items into the
 * vault, then launches a MediaStore delete-request so the public originals are removed
 * (with user consent on API 30+).
 */
@Composable
fun HideImportScreen(
    viewModel: HideImportViewModel,
    onBack: () -> Unit,
    onHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val context = LocalContext.current

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            viewModel.onPermissionResult(grants.values.all { it })
        }
    LaunchedEffect(state.category) {
        val perms = requiredPermissions(state.category)
        if (perms.isEmpty()) viewModel.onPermissionResult(true) else permissionLauncher.launch(perms)
    }

    // Delete the public originals once a hide succeeds (consent dialog on API 30+).
    val deleteLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.onOriginalsRemoved()
        }
    LaunchedEffect(state.pendingDeleteUris) {
        val uris = state.pendingDeleteUris
        if (uris.isEmpty()) return@LaunchedEffect
        val sender = deleteRequest(context, uris)
        if (sender != null) {
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            // Pre-R (or nothing to consent): originals already removed directly.
            viewModel.onOriginalsRemoved()
        }
    }

    LaunchedEffect(state.done) {
        if (state.done) onHidden()
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultTopBar(
                title = "Hide ${state.category.label.lowercase()}",
                subtitle = state.albums.firstOrNull { it.id == state.selectedAlbumId }?.name,
                onBack = onBack,
            )

            if (state.albums.isEmpty() && state.selectedAlbumId == null) {
                EmptyPickerState(granted = state.permissionGranted, modifier = Modifier.weight(1f))
            } else if (state.selectedAlbumId == null) {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(state.albums, key = { it.id }) { album ->
                        ListRow(
                            title = album.name,
                            subtitle = "${album.count} items",
                            leadingIcon = state.category.icon(),
                            leadingChipColor = state.category.color(),
                            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
                            onClick = { viewModel.selectAlbum(album.id) },
                        )
                    }
                }
            } else {
                val allSelected =
                    state.sources.isNotEmpty() && state.selectedIds.containsAll(state.sources.map { it.id }.toSet())
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = viewModel::toggleAll, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(
                            text = if (allSelected) "Clear all" else "Select all",
                            color = colors.accent,
                            style = VaultTheme.typography.labelLarge,
                        )
                    }
                }
                DateGroupedMediaGrid(
                    items = state.sources.map { MediaItem(id = it.id, dateLabel = it.dateLabel, sortKey = it.sortKey) },
                    selectionMode = true,
                    selectedIds = state.selectedIds,
                    checkIcon = Icons.Filled.Check,
                    onItemClick = { viewModel.toggle(it.id) },
                    onItemLongPress = { viewModel.toggle(it.id) },
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = if (state.selectedIds.isEmpty()) "Hide Now" else "Hide Now (${state.selectedIds.size})",
                    onClick = viewModel::hideNow,
                    enabled = state.hideEnabled,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun EmptyPickerState(
    granted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Box(modifier = modifier.fillMaxWidth().padding(VaultTheme.spacing.xxl), contentAlignment = Alignment.Center) {
        Text(
            text =
                if (granted) {
                    "Nothing here to hide yet."
                } else {
                    "Allow access to your media to hide items into the vault."
                },
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Runtime permissions needed to enumerate [category]'s public-storage originals. */
private fun requiredPermissions(category: VaultCategory): Array<String> =
    when (category) {
        VaultCategory.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        VaultCategory.PHOTOS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        VaultCategory.VIDEOS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        VaultCategory.AUDIOS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        VaultCategory.FILES ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                emptyArray()
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
    }

/**
 * Build a delete-request [IntentSender] for [uris]. On API 30+ MediaStore surfaces a
 * system consent dialog; below R (or for non-MediaStore uris) it deletes directly and
 * returns null so the caller can proceed without a launcher.
 */
private fun deleteRequest(
    context: Context,
    uris: List<String>,
): IntentSender? {
    val parsed = uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    if (parsed.isEmpty()) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        MediaStore.createDeleteRequest(context.contentResolver, parsed).intentSender
    } else {
        parsed.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        null
    }
}
