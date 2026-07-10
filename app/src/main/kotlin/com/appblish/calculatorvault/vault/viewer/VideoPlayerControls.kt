package com.appblish.calculatorvault.vault.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import kotlinx.coroutines.delay

private val ControlScrim = Color(0xCC000000)
private val BottomGradient = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color(0xCC000000)),
)
private val TopGradient = Brush.verticalGradient(
    colors = listOf(Color(0xCC000000), Color.Transparent),
)
private const val AUTO_HIDE_MS = 3_500L
private const val UNLOCK_PILL_HIDE_MS = 2_000L

/**
 * CalcVault Phase B · Wave 3 · APP-350 — the controls overlay that sits above
 * [VideoPlayerSurface] (spec §5c / §6). Renders the scrim gradient, quick-control row,
 * ⋯ menu with track/aspect/speed submenus, speed chip dialog, playlist bottom-sheet, and the
 * center play/pause button. Auto-hides after [AUTO_HIDE_MS] ms of inactivity; any
 * interaction resets the timer (resetAutoHide increments the LaunchedEffect key).
 *
 * The **lock overlay** ([VideoPlayerLockOverlay]) is a separate top-most composable — it must
 * intercept all pointer events *before* [VideoPlayerSurface]'s gesture layer sees them, so it
 * lives above this composable in the caller's Box stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerControlsOverlay(
    player: Player,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    locked: Boolean,
    onLockChanged: (Boolean) -> Unit,
    speed: Float,
    onSpeedChanged: (Float) -> Unit,
    aspectMode: VideoScaleMath.AspectMode,
    onAspectModeChanged: (VideoScaleMath.AspectMode) -> Unit,
    rotationDegrees: Int,
    onRotationChanged: (Int) -> Unit,
    muted: Boolean,
    onMutedChanged: (Boolean) -> Unit,
    // APP-371 F1–F3: the §5d playlist (current-folder videos, order modes, Next/Prev/tap-switch).
    playlist: VideoPlaylistController,
    // APP-371 F4: side-loaded external subtitle state + loaders (device SAF / vault-hidden).
    currentSubtitleLabel: String?,
    onLoadDeviceSubtitle: () -> Unit,
    onLoadVaultSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    // APP-351 (Wave 4): minimize into the in-app floating mini player (§5c).
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-hide: bump nonce to restart the 3.5-second idle timer.
    var autoHideNonce by remember { mutableIntStateOf(0) }

    fun resetAutoHide() {
        autoHideNonce++
    }

    LaunchedEffect(autoHideNonce, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        onToggleControls()
    }

    // Track ExoPlayer playing state for the center play/pause affordance.
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Track available embedded subtitle + audio tracks for the ⋯ submenus.
    var tracks by remember { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Local UI state for menus / dialogs / sheets.
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var subtitlesMenuExpanded by remember { mutableStateOf(false) }
    var audioMenuExpanded by remember { mutableStateOf(false) }
    var aspectMenuExpanded by remember { mutableStateOf(false) }
    var speedDialogVisible by remember { mutableStateOf(false) }
    var playlistSheetVisible by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // Bottom scrim gradient (sits behind quick-row).
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(BottomGradient),
            )
        }

        // Top scrim gradient (sits behind ⋯ menu / speed badge).
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(TopGradient),
            )
        }

        // ---- §5c quick-control row ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 72.dp, start = 4.dp, end = 4.dp),
            ) {
                IconButton(onClick = {
                    // §5d Previous: PlaylistEngine.manualPrev (always wraps) → pager switch.
                    playlist.onPrevious()
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                    )
                }
                // Lock screen: activates the lock overlay layer above this composable.
                IconButton(onClick = {
                    onLockChanged(true)
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.Unhide,
                        contentDescription = "Lock screen",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    onRotationChanged(VideoScaleMath.nextRotation(rotationDegrees))
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.ScreenRotation,
                        contentDescription = "Rotate",
                        tint = Color.White,
                    )
                }
                // Display-mode: quick Fit ⇄ Fill toggle (§5c).
                IconButton(onClick = {
                    onAspectModeChanged(VideoScaleMath.nextDisplayMode(aspectMode))
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.AspectRatio,
                        contentDescription = "Display mode",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    playlistSheetVisible = true
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Playlist",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    onMutedChanged(!muted)
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = if (muted) VaultActionIcons.VolumeOff else VaultActionIcons.VolumeOn,
                        contentDescription = if (muted) "Unmute" else "Mute",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    // §5d Next: PlaylistEngine.manualNext (always wraps) → pager switch.
                    playlist.onNext()
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                    )
                }
            }
        }

        // ---- Top-right: speed badge + ⋯ menu ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            ) {
                // §5c Mini Player: minimize into the in-app floating window (APP-351 / Wave 4).
                IconButton(onClick = {
                    onMinimize()
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.PictureInPicture,
                        contentDescription = "Mini player",
                        tint = Color.White,
                    )
                }
                if (!PlaybackSpeeds.isDefault(speed)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ControlScrim)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = PlaybackSpeeds.label(speed),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box {
                    IconButton(onClick = {
                        moreMenuExpanded = true
                        resetAutoHide()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Subtitles") },
                            leadingIcon = {
                                Icon(VaultActionIcons.Subtitles, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                subtitlesMenuExpanded = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Audio track") },
                            onClick = {
                                moreMenuExpanded = false
                                audioMenuExpanded = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Aspect ratio") },
                            leadingIcon = {
                                Icon(VaultActionIcons.AspectRatio, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                aspectMenuExpanded = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Playback speed") },
                            onClick = {
                                moreMenuExpanded = false
                                speedDialogVisible = true
                            },
                        )
                    }
                    SubtitleTrackMenu(
                        expanded = subtitlesMenuExpanded,
                        tracks = tracks,
                        player = player,
                        currentSubtitleLabel = currentSubtitleLabel,
                        onLoadDeviceSubtitle = {
                            subtitlesMenuExpanded = false
                            onLoadDeviceSubtitle()
                        },
                        onLoadVaultSubtitle = {
                            subtitlesMenuExpanded = false
                            onLoadVaultSubtitle()
                        },
                        onClearSubtitle = {
                            subtitlesMenuExpanded = false
                            onClearSubtitle()
                        },
                        onDismiss = { subtitlesMenuExpanded = false },
                    )
                    AudioTrackMenu(
                        expanded = audioMenuExpanded,
                        tracks = tracks,
                        player = player,
                        onDismiss = { audioMenuExpanded = false },
                    )
                    AspectRatioMenu(
                        expanded = aspectMenuExpanded,
                        current = aspectMode,
                        onSelected = {
                            onAspectModeChanged(it)
                            aspectMenuExpanded = false
                        },
                        onDismiss = { aspectMenuExpanded = false },
                    )
                }
            }
        }

        // Center play/pause: shown when controls are visible (replaces built-in controller).
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(ControlScrim)
                    .clickable {
                        if (player.isPlaying) player.pause() else player.play()
                        resetAutoHide()
                    },
            ) {
                Icon(
                    imageVector = if (isPlaying) VaultActionIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // ---- Speed dialog: 5-chip segmented selector (spec §5c) ----
        if (speedDialogVisible) {
            AlertDialog(
                onDismissRequest = { speedDialogVisible = false },
                title = { Text("Playback speed") },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PlaybackSpeeds.OPTIONS.forEach { option ->
                            FilterChip(
                                selected = PlaybackSpeeds.nearest(speed) == option,
                                onClick = {
                                    onSpeedChanged(option)
                                    speedDialogVisible = false
                                    resetAutoHide()
                                },
                                label = { Text(PlaybackSpeeds.label(option)) },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { speedDialogVisible = false }) { Text("Close") }
                },
            )
        }

        // ---- Playlist bottom sheet (~70% height, over the paused video) ----
        if (playlistSheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { playlistSheetVisible = false },
                sheetState = rememberModalBottomSheetState(),
            ) {
                Text(
                    text = "Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // F1 — the current-folder videos. Tap any row to switch playback to it (the
                // pager settles on it and its player takes over); the playing row is marked.
                Text(
                    text = "Videos (${playlist.items.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    itemsIndexed(playlist.items) { index, item ->
                        val isPlaying = index == playlist.currentIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = item.originalName,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            },
                            leadingContent = {
                                if (isPlaying) {
                                    Icon(VaultActionIcons.Pause, contentDescription = "Now playing")
                                } else {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                }
                            },
                            modifier = Modifier.clickable {
                                playlist.onSelect(index)
                                playlistSheetVisible = false
                                resetAutoHide()
                            },
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    text = "Order mode",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OrderMode.entries.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(mode.label) },
                        leadingContent = {
                            RadioButton(
                                selected = playlist.orderMode == mode,
                                onClick = { playlist.onOrderModeChanged(mode) },
                            )
                        },
                        modifier = Modifier.clickable { playlist.onOrderModeChanged(mode) },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Lock overlay — a separate full-screen layer that must be placed **above**
 * [VideoPlayerControlsOverlay] and [VideoPlayerSurface] in the caller's Box, so it
 * intercepts all pointer events before the gesture layers see them (spec §5c / §6).
 *
 * On any touch while locked, the unlock pill re-appears for [UNLOCK_PILL_HIDE_MS] ms.
 * Tapping the pill calls [onUnlock].
 */
@Composable
internal fun VideoPlayerLockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pillVisible by remember { mutableStateOf(true) }
    var touchNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(touchNonce) {
        delay(UNLOCK_PILL_HIDE_MS)
        pillVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Consume ALL events at the Initial pass before any child or sibling sees them.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(pass = PointerEventPass.Initial)
                        currentEvent.changes.forEach { it.consume() }
                        pillVisible = true
                        touchNonce++
                    }
                }
            },
    ) {
        AnimatedVisibility(
            visible = pillVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TextButton(
                onClick = onUnlock,
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ControlScrim),
            ) {
                Icon(
                    imageVector = VaultActionIcons.LockOpen,
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text("Tap to unlock", color = Color.White)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SubtitleTrackMenu(
    expanded: Boolean,
    tracks: Tracks,
    player: Player,
    currentSubtitleLabel: String?,
    onLoadDeviceSubtitle: () -> Unit,
    onLoadVaultSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // F4 — side-load an external subtitle. Both paths build the APP-370-mandated
        // MergingMediaSource + SingleSampleMediaSource (see MediaPlayerPage.buildMediaSource);
        // a vault-hidden sub streams through EncryptedVaultDataSource — no plaintext temp.
        DropdownMenuItem(
            text = { Text("Load from device…") },
            leadingIcon = { Icon(VaultActionIcons.MoveTo, contentDescription = null) },
            onClick = onLoadDeviceSubtitle,
        )
        DropdownMenuItem(
            text = { Text("Load from vault…") },
            leadingIcon = { Icon(VaultActionIcons.Unhide, contentDescription = null) },
            onClick = onLoadVaultSubtitle,
        )
        if (currentSubtitleLabel != null) {
            DropdownMenuItem(
                text = { Text("Remove: $currentSubtitleLabel") },
                onClick = onClearSubtitle,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Off") },
            onClick = {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setIgnoredTextSelectionFlags(
                        C.SELECTION_FLAG_DEFAULT or
                            C.SELECTION_FLAG_FORCED or
                            C.SELECTION_FLAG_AUTOSELECT,
                    ).build()
                onDismiss()
            },
        )
        if (textGroups.isEmpty()) {
            DropdownMenuItem(
                text = { Text("No embedded subtitles") },
                onClick = onDismiss,
                enabled = false,
            )
        } else {
            textGroups.forEachIndexed { i, group ->
                val lang = if (group.mediaTrackGroup.length > 0) {
                    group.mediaTrackGroup.getFormat(0).language
                } else {
                    null
                }
                val label = lang ?: "Track ${i + 1}"
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setIgnoredTextSelectionFlags(0)
                            .setPreferredTextLanguage(lang)
                            .build()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun AudioTrackMenu(
    expanded: Boolean,
    tracks: Tracks,
    player: Player,
    onDismiss: () -> Unit,
) {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (audioGroups.isEmpty()) {
            DropdownMenuItem(
                text = { Text("Single audio track") },
                onClick = onDismiss,
                enabled = false,
            )
        } else {
            audioGroups.forEachIndexed { i, group ->
                val lang = if (group.mediaTrackGroup.length > 0) {
                    group.mediaTrackGroup.getFormat(0).language
                } else {
                    null
                }
                val label = lang ?: "Audio ${i + 1}"
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setPreferredAudioLanguage(lang)
                            .build()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun AspectRatioMenu(
    expanded: Boolean,
    current: VideoScaleMath.AspectMode,
    onSelected: (VideoScaleMath.AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        VideoScaleMath.AspectMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                onClick = { onSelected(mode) },
            )
        }
    }
}
