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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.media.VaultThumbnails
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * The shared in-vault hide/import flow, at xlock parity:
 * **folder-thumbnail grid → date-grouped multi-select → Hide Now**.
 *
 * The folder grid leads with a "Recent" tile aggregating every bucket (xlock's default
 * public / All-Files folder) so the picker is never empty; each device folder shows a
 * cover thumbnail + name + count. Tapping a folder opens its items in a date-grouped grid
 * with a sort menu (Added time / Last modified / Name / Size) and per-item circular
 * checkboxes. **Hide Now** encrypts the chosen items into the vault, then launches a
 * MediaStore delete-request so the public originals are removed (consent dialog on R+).
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
                subtitle = state.selectedAlbum?.name,
                // Inside a folder, Back returns to the folder grid; at the grid it exits.
                onBack = { if (state.selectedAlbumId != null) viewModel.clearAlbum() else onBack() },
            )

            if (state.albums.isEmpty() && state.selectedAlbumId == null) {
                EmptyPickerState(granted = state.permissionGranted, modifier = Modifier.weight(1f))
            } else if (state.selectedAlbumId == null) {
                FolderGrid(
                    category = state.category,
                    albums = state.albums,
                    onOpen = viewModel::selectAlbum,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ItemPickerGrid(
                    state = state,
                    viewModel = viewModel,
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

/** xlock-style 2-column folder grid: cover thumbnail + name + count, "Recent" first. */
@Composable
private fun FolderGrid(
    category: VaultCategory,
    albums: List<SourceAlbum>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = VaultTheme.spacing
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.lg),
    ) {
        items(albums, key = { it.id }) { album ->
            FolderTile(
                album = album,
                categoryIcon = { category.icon() },
                categoryColor = { category.color() },
                onClick = { onOpen(album.id) },
                loadCover = {
                    album.coverUri?.let { uri ->
                        VaultThumbnails.forSource(
                            context,
                            SourceItem(
                                id = album.id,
                                name = album.name,
                                dateLabel = "",
                                sortKey = 0L,
                                albumId = album.id,
                                albumName = album.name,
                                contentUri = uri,
                                mimeType = album.coverMime,
                            ),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun FolderTile(
    album: SourceAlbum,
    categoryIcon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    categoryColor: () -> androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    loadCover: suspend () -> ImageBitmap?,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val cover: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, album.id, album.coverUri) {
        value = loadCover()
    }
    Column(
        modifier = Modifier.padding(spacing.xs).clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(VaultTheme.shapes.thumbnail)
                    .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            cover?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Icon(
                imageVector = categoryIcon(),
                contentDescription = null,
                tint = categoryColor(),
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = album.name,
            style = VaultTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Text(
            text = "${album.count} items",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
    }
}

/** The item grid inside a folder: sort menu + select-all header, then the multi-select grid. */
@Composable
private fun ItemPickerGrid(
    state: HideImportState,
    viewModel: HideImportViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val context = LocalContext.current
    val allSelected =
        state.sources.isNotEmpty() && state.selectedIds.containsAll(state.sources.map { it.id }.toSet())

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortMenu(current = state.sort, onSelect = viewModel::setSort)
            TextButton(onClick = viewModel::toggleAll) {
                Text(
                    text = if (allSelected) "Clear all" else "Select all",
                    color = colors.accent,
                    style = VaultTheme.typography.labelLarge,
                )
            }
        }
        // Re-key the grid by the active sort so alternate orders (name/size/modified)
        // collapse the date sections and reorder; ADDED_TIME keeps real date grouping.
        val gridItems =
            remember(state.sources, state.sort) {
                PickerSort.grid(state.sources, state.sort).map { (id, label, key) ->
                    MediaItem(id = id, dateLabel = label, sortKey = key)
                }
            }
        val sourcesById = remember(state.sources) { state.sources.associateBy { it.id } }
        DateGroupedMediaGrid(
            items = gridItems,
            selectionMode = true,
            selectedIds = state.selectedIds,
            checkIcon = Icons.Filled.Check,
            onItemClick = { viewModel.toggle(it.id) },
            onItemLongPress = { viewModel.toggle(it.id) },
            modifier = Modifier.weight(1f),
            loadThumbnail = { media ->
                sourcesById[media.id]?.let { VaultThumbnails.forSource(context, it) }
            },
        )
    }
}

/** xlock sort menu: an overflow trigger showing the active sort's label. */
@Composable
private fun SortMenu(
    current: PickerSort,
    onSelect: (PickerSort) -> Unit,
) {
    val colors = VaultTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Sort",
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = current.label,
                color = colors.textSecondary,
                style = VaultTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PickerSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = sort.label,
                            color = if (sort == current) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    },
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
        // Contacts access was dropped per board scope refinement (APP-207): All Files Access
        // is the only mandatory vault permission, so the Contacts category requests nothing
        // and its source query returns empty.
        VaultCategory.CONTACTS -> emptyArray()
        // Photos/Videos/Audios/Files all enumerate shared-storage originals under All Files
        // Access (MANAGE_EXTERNAL_STORAGE) on API 30+, which already grants full read to every
        // media category — so on TIRAMISU+ no granular READ_MEDIA_* runtime permission is
        // requested (board directive APP-219 / APP-203, xlock parity). Only pre-Tiramisu
        // devices without All Files Access fall back to READ_EXTERNAL_STORAGE.
        VaultCategory.PHOTOS,
        VaultCategory.VIDEOS,
        VaultCategory.AUDIOS,
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
