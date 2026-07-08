package com.appblish.calculatorvault.vault

import android.Manifest
import android.content.Context
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.FastScrollbar
import com.appblish.calculatorvault.ui.components.GridDragSelectCallbacks
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.gridDragSelect
import com.appblish.calculatorvault.ui.components.groupMediaByDate
import com.appblish.calculatorvault.ui.components.pinchColumns
import com.appblish.calculatorvault.ui.components.rememberPinchColumnsState
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.media.BulkOpProgress
import com.appblish.calculatorvault.vault.media.VaultThumbnails
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest edge for the full-screen pre-hide preview decode (bounded memory, no Coil dep). */
private const val PREVIEW_PX = 1440

/**
 * The shared in-vault hide/import flow per the Phase-1 design sign-off (APP-224 S14–S16):
 * **device-folder multi-select grid → date-sectioned multi-select → Hide Now**.
 *
 * S14: a 3-column grid of device folders led by the "Recent" aggregate (xlock's default
 * public / All-Files folder, so the picker is never empty); each real folder carries a
 * selection circle so whole folders can be hidden without opening them, and the "All"
 * toggle at the top-right stages/unstages every real folder at once. S15: opening a folder
 * shows its items grouped into date sections ("Today", "Yesterday", dates), each with its
 * own select-all circle; every cell has an expand affordance (top-left) opening a
 * full-screen pre-hide preview and a selection circle (top-right); the title is the live
 * "Selected - N" count. **Hide Now** encrypts the chosen items into the vault — landing
 * each in a vault folder named after its source bucket (S16) — then launches a MediaStore
 * delete-request so the public originals are removed (consent dialog on R+).
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
                title = state.pickerTitle,
                subtitle = state.selectedAlbum?.name,
                // Inside a folder, Back returns to the folder grid; at the grid it exits.
                onBack = { if (state.selectedAlbumId != null) viewModel.clearAlbum() else onBack() },
            )

            if (state.albums.isEmpty() && state.selectedAlbumId == null) {
                EmptyPickerState(granted = state.permissionGranted, modifier = Modifier.weight(1f))
            } else {
                val onFolderStep = state.selectedAlbumId == null
                // Shared control strip: the sort menu plus the "All" toggle, which stages
                // every real folder on S14 and selects every visible item on S15.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SortMenu(current = state.sort, onSelect = viewModel::setSort)
                    AllToggle(
                        active = if (onFolderStep) state.allFoldersSelected else state.allItemsSelected,
                        onClick = if (onFolderStep) viewModel::toggleAllFolders else viewModel::toggleAll,
                    )
                }
                if (onFolderStep) {
                    FolderGrid(
                        category = state.category,
                        albums = state.albums,
                        selectedFolderIds = state.selectedFolderIds,
                        onOpen = viewModel::selectAlbum,
                        onToggle = viewModel::toggleFolder,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SectionedItemGrid(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
                }
                // P2-3: in-UI mirror of the foreground-service "Processing N of M"
                // notification while a bulk (>1 item) operation runs.
                val bulkProgress by viewModel.bulkProgress.collectAsStateWithLifecycle()
                bulkProgress?.takeIf { it.total > 1 }?.let { progress ->
                    BulkProgressBar(
                        progress = progress,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                    )
                }
                val hideCount = if (onFolderStep) state.selectedFolderIds.size else state.selectedIds.size
                PillButton(
                    text =
                        when {
                            state.hiding -> "Hiding…"
                            hideCount == 0 -> "Hide Now"
                            else -> "Hide Now ($hideCount)"
                        },
                    onClick = viewModel::hideNow,
                    enabled = state.hideEnabled,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }

        // S15 expand affordance target: full-screen pre-hide preview over a dark scrim.
        state.previewItem?.let { preview ->
            PreviewOverlay(source = preview, onDismiss = viewModel::closePreview)
        }
    }
}

/**
 * The S14 "All" control: a compact label + selection circle at the top-right that toggles
 * select-all/none (folders on S14, items on S15) — replaces the old "Select all"/"Clear
 * all" text button per the sign-off redline.
 */
@Composable
private fun AllToggle(
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    TextButton(onClick = onClick) {
        Text(
            text = "All",
            color = if (active) colors.accent else colors.textSecondary,
            style = VaultTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 6.dp),
        )
        SelectionCircle(selected = active, onClick = onClick)
    }
}

/**
 * S14 device-folder grid: 3 columns of cover-thumbnail tiles ("Recent" aggregate first),
 * each real folder with a selection circle so whole folders can be staged for hiding. The
 * Recent tile has no circle — it *is* every bucket, so the "All" toggle covers that intent
 * without double-counting.
 */
@Composable
private fun FolderGrid(
    category: VaultCategory,
    albums: List<SourceAlbum>,
    selectedFolderIds: Set<String>,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = VaultTheme.spacing
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    // APP-293 item 10: two-finger pinch rescales the folder grid fluidly (2–4 columns).
    val pinch = rememberPinchColumnsState(initialColumns = 3, minColumns = 2, maxColumns = 4)
    Box(modifier = modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(pinch.columns),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.lg).pinchColumns(pinch),
        ) {
            items(albums, key = { it.id }) { album ->
                FolderTile(
                    album = album,
                    selected = if (album.id == SourceAlbum.RECENT_ID) null else album.id in selectedFolderIds,
                    categoryIcon = { category.icon() },
                    categoryColor = { category.color() },
                    onClick = { onOpen(album.id) },
                    onToggle = { onToggle(album.id) },
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
        // P2-2: draggable fast-scroll thumb (renders only once the grid tops ~30 cells).
        FastScrollbar(
            state = gridState,
            modifier = Modifier.align(Alignment.CenterEnd),
            labelForIndex = { index -> albums.getOrNull(index)?.name },
        )
    }
}

/** One S14 folder tile: cover, selection circle (null [selected] = none), name + count. */
@Composable
private fun FolderTile(
    album: SourceAlbum,
    selected: Boolean?,
    categoryIcon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    categoryColor: () -> androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onToggle: () -> Unit,
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
            if (selected != null) {
                SelectionCircle(
                    selected = selected,
                    onClick = onToggle,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
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

/**
 * S15 item grid: date sections ("Today", "Yesterday", dates — collapsed under non-time
 * sorts), each header with its own select-all circle; every cell carries the expand
 * affordance (top-left → full-screen preview) and a selection circle (top-right). Tapping
 * the cell body toggles selection, matching the docx picker interaction.
 */
@Composable
private fun SectionedItemGrid(
    state: HideImportState,
    viewModel: HideImportViewModel,
    modifier: Modifier = Modifier,
) {
    val spacing = VaultTheme.spacing
    val context = LocalContext.current

    // Re-key the grid by the active sort so alternate orders (name/size/modified)
    // collapse the date sections and reorder; ADDED_TIME keeps real date grouping.
    val groups =
        remember(state.sources, state.sort) {
            groupMediaByDate(
                PickerSort.grid(state.sources, state.sort).map { (id, label, key) ->
                    MediaItem(id = id, dateLabel = label, sortKey = key)
                },
            )
        }
    val sourcesById = remember(state.sources) { state.sources.associateBy { it.id } }

    val gridState = rememberLazyGridState()
    // APP-293 item 10: pinch-to-change-columns, same fluid contract as the hidden grid.
    val pinch = rememberPinchColumnsState(initialColumns = 3, minColumns = 2, maxColumns = 5)
    Box(modifier = modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(pinch.columns),
            state = gridState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.lg)
                    .pinchColumns(pinch)
                    // APP-293 item 7: the same long-press-drag range select as the hidden
                    // grid — cells keep their tap-to-toggle; the grid detector owns the
                    // long-press+drag sweep (headers/gutters are ignored by the VM).
                    .gridDragSelect(
                        gridState,
                        GridDragSelectCallbacks(
                            onDragStart = viewModel::beginDragSelect,
                            onDragOver = viewModel::dragSelectOver,
                            onDragEnd = viewModel::endDragSelect,
                        ),
                    ),
        ) {
            groups.forEach { group ->
                val sectionIds = group.items.map { it.id }
                item(key = "header_${group.dateLabel}", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        label = group.dateLabel,
                        allSelected = sectionIds.isNotEmpty() && state.selectedIds.containsAll(sectionIds),
                        onToggle = { viewModel.toggleSection(sectionIds) },
                    )
                }
                items(group.items, key = { it.id }) { item ->
                    PickerCell(
                        selected = item.id in state.selectedIds,
                        onToggle = { viewModel.toggle(item.id) },
                        onExpand = { viewModel.openPreview(item.id) },
                        loadThumbnail = {
                            sourcesById[item.id]?.let { VaultThumbnails.forSource(context, it) }
                        },
                        thumbnailKey = item.id,
                    )
                }
            }
        }
        // P2-2: draggable fast-scroll thumb (renders only once the grid tops ~30 cells).
        // Item 14 bubble: grid indices include the section headers, so the label table
        // repeats each section's date once for its header row and once per item.
        val indexLabels =
            remember(groups) {
                groups.flatMap { group -> List(group.items.size + 1) { group.dateLabel } }
            }
        FastScrollbar(
            state = gridState,
            modifier = Modifier.align(Alignment.CenterEnd),
            labelForIndex = { index -> indexLabels.getOrNull(index) },
        )
    }
}

/**
 * P2-3: the in-UI mirror of [com.appblish.calculatorvault.vault.media.BulkOpService]'s
 * foreground "Processing N of M" notification — a thin accent progress bar + label pinned
 * under the picker while a bulk (>1 item) hide/restore batch runs.
 */
@Composable
private fun BulkProgressBar(
    progress: BulkOpProgress.Progress,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
            color = colors.accent,
            trackColor = colors.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Processing ${progress.done} of ${progress.total}",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = VaultTheme.spacing.xs),
        )
    }
}

/** A date-section header with its own select-all circle at the right (S15 redline). */
@Composable
private fun SectionHeader(
    label: String,
    allSelected: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = VaultTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = VaultTheme.typography.labelLarge,
            color = VaultTheme.colors.textSecondary,
        )
        SelectionCircle(selected = allSelected, onClick = onToggle)
    }
}

/** One S15 cell: thumbnail, expand badge top-left, selection circle top-right. */
@Composable
private fun PickerCell(
    selected: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    loadThumbnail: suspend () -> ImageBitmap?,
    thumbnailKey: String,
) {
    val colors = VaultTheme.colors
    val thumbnail: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, thumbnailKey) {
        value = loadThumbnail()
    }
    Box(
        modifier =
            Modifier
                .padding(2.dp)
                .aspectRatio(1f)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant)
                .clickable(onClick = onToggle),
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ExpandBadge(
            onClick = onExpand,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        SelectionCircle(
            selected = selected,
            onClick = onToggle,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(colors.accent.copy(alpha = 0.18f)),
            )
        }
    }
}

/**
 * The circular multi-select affordance from the docx frames: an outlined translucent disc
 * that fills with the green accent + check when selected. Shared by folder tiles, item
 * cells, section headers, and the "All" toggle so the selection language stays uniform.
 */
@Composable
private fun SelectionCircle(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else colors.canvas.copy(alpha = 0.4f))
                .border(1.dp, if (selected) colors.accent else Color.White.copy(alpha = 0.7f), CircleShape)
                .clickable(onClick = onClick),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = colors.onAccent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The S15 expand affordance: a small ↗ glyph on a translucent disc at the cell's top-left,
 * opening the full-screen pre-hide preview. material-icons-core has no open-in-full icon,
 * so the arrow is drawn as three stroked lines (diagonal + arrow-head) on a tiny canvas.
 */
@Composable
private fun ExpandBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(colors.canvas.copy(alpha = 0.4f))
                .clickable(onClick = onClick),
    ) {
        Canvas(modifier = Modifier.size(9.dp)) {
            val stroke = 1.5.dp.toPx()
            val w = size.width
            val h = size.height
            drawLine(Color.White, Offset(0f, h), Offset(w, 0f), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.4f, 0f), Offset(w, 0f), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(w, 0f), Offset(w, h * 0.6f), stroke, StrokeCap.Round)
        }
    }
}

/**
 * Full-screen pre-hide preview over a dark scrim: images decode from the public content
 * Uri, videos show their thumbnail frame (no playback pre-hide, per the sign-off). Tap
 * anywhere or system Back to dismiss. Decoding is bounded to [PREVIEW_PX] so a 100 MP
 * original can never blow the heap.
 */
@Composable
private fun PreviewOverlay(
    source: SourceItem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, source.id) {
        value = loadFullPreview(context, source)
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = source.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(VaultTheme.spacing.lg),
            )
        }
        Text(
            text = source.name,
            style = VaultTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = VaultTheme.spacing.lg, vertical = VaultTheme.spacing.xl),
        )
    }
}

/**
 * Decode a bounded full-screen preview for [source]: MediaStore's own decoder on Q+
 * (handles both images and video frames without touching raw bytes), falling back to a
 * plain stream decode for images. Best-effort — null keeps the scrim + name caption only.
 */
private suspend fun loadFullPreview(
    context: Context,
    source: SourceItem,
): ImageBitmap? {
    val raw = source.contentUri.takeIf { it.isNotBlank() } ?: return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    return withContext(Dispatchers.IO) {
        val fromStore =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    context.contentResolver
                        .loadThumbnail(uri, Size(PREVIEW_PX, PREVIEW_PX), null)
                        .asImageBitmap()
                }.getOrNull()
            } else {
                null
            }
        fromStore
            ?: runCatching {
                context.contentResolver
                    .openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it) }
                    ?.asImageBitmap()
            }.getOrNull()
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
