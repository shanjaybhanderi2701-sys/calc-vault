package com.appblish.calculatorvault.vault.viewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.icon
import kotlinx.coroutines.delay

/**
 * CalcVault · APP-526 — the in-vault **audio** now-playing screen. Stream 2 of the JD finishing
 * work: audio reuses the **exact** encrypted playback layer the video player owns —
 * [EncryptedVaultDataSource] (seekable, decrypting, no plaintext on disk), [VideoSeekbar]
 * (seek-on-release, exactly one seek per drag), and the [PlaylistEngine]/[VideoPlaylistController]
 * order modes — **minus the video surface**, rendered as a title / album-art / transport UI
 * instead (spec §5d order modes, spec §7–§8 encrypted stream).
 *
 * **No reinventing:** encrypted-seek and the seekbar are consumed verbatim from the video player.
 * This file adds only the now-playing composition + the audio-specific lifecycle hardening.
 *
 * **NO BACKGROUND PLAYBACK (privacy, spec):** the player is **page-owned** — built here, released
 * on dispose, and paused the instant the app/screen leaves the foreground (`ON_STOP`, which fires
 * on both backgrounding and a screen lock). It mirrors the video in-app-only rule:
 *  - No [MediaSession]/[PlayerNotificationManager] is ever created → **no** media notification and
 *    **no** lock-screen transport controls.
 *  - No mini-player minimize path (audio is foreground-only; only the video player floats).
 *  - The vault itself also relocks + pops on `ON_STOP` (VaultNavHost), disposing this page and
 *    releasing the player — the [LocalLifecycleOwner] observer here is the synchronous belt-and-
 *    suspenders so audio can never be heard for even a frame behind the calculator disguise.
 *
 * Album art comes from the **in-stream** embedded picture ([MediaMetadata.artworkData]) that
 * ExoPlayer parses from the encrypted [EncryptedVaultDataSource] in memory — so art never touches
 * plaintext disk either. Files with no embedded art fall back to a music-note placeholder.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun AudioPlayerPage(
    item: VaultItem,
    fileActions: ViewerFileActions,
    playlist: VideoPlaylistController,
    onSeekbarDraggingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val repository = VaultGraph.contentRepository
    val itemId = item.id
    var playbackError by remember(itemId) { mutableStateOf<PlaybackException?>(null) }
    // Read the latest controller through this so a mid-session order-mode change is honoured by the
    // STATE_ENDED auto-advance (mirrors the video player's F3 wiring).
    val currentPlaylist by rememberUpdatedState(playlist)

    // The page-owned ExoPlayer. Same encrypted source and PREVIOUS_SYNC seek behaviour as the video
    // player (PagerViewerScreen.MediaPlayerPage), just without the subtitle-merge / mini-session
    // handoff (audio never floats or side-loads subs).
    val player =
        remember(itemId) {
            ExoPlayer.Builder(context).build().apply {
                setSeekParameters(SeekParameters.PREVIOUS_SYNC)
                val vaultFactory = EncryptedVaultDataSource.Factory { id -> repository.openBlobReader(id) }
                val source =
                    ProgressiveMediaSource
                        .Factory(vaultFactory)
                        .createMediaSource(MediaItem.fromUri(EncryptedVaultDataSource.vaultMediaUri(itemId)))
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
        }

    // Now-playing state, all driven off the single page-owned player.
    var isPlaying by remember(itemId) { mutableStateOf(player.isPlaying) }
    var artwork by remember(itemId) { mutableStateOf<ImageBitmap?>(null) }
    // Prefer the stream's own tag title; fall back to the vault file name (always present).
    var title by remember(itemId) { mutableStateOf(displayTitle(player.mediaMetadata, item.originalName)) }

    val listener =
        remember(itemId) {
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = error
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Order-mode auto-advance: the track finished on its own → PlaylistEngine decides
                    // where playback goes (or to stop). REPEAT_CURRENT loops in place via repeatMode.
                    if (playbackState == Player.STATE_ENDED) currentPlaylist.onCompleted()
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    title = displayTitle(mediaMetadata, item.originalName)
                    artwork = decodeArtwork(mediaMetadata.artworkData)
                }
            }
        }
    DisposableEffect(itemId, player) {
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Page-owned: always release when this page leaves composition (Back, page switch, or the
            // ON_STOP relock+pop). No mini-session ever owns an audio player, so there is no handoff.
            player.release()
        }
    }

    // Restore the playhead + play/pause across a genuine activity teardown+rebuild (reused from video).
    RetainedPlaybackEffect(player = player, itemId = itemId)

    // Order mode → repeatMode: REPEAT_CURRENT loops the single track in place (never reaches
    // STATE_ENDED); every other mode advances across pages, so the player must not auto-repeat.
    LaunchedEffect(playlist.orderMode) {
        player.repeatMode =
            if (playlist.orderMode == OrderMode.REPEAT_CURRENT) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
    }

    // NO BACKGROUND PLAYBACK — pause the instant the app/screen leaves the foreground. ON_STOP fires
    // on backgrounding AND on a device lock, so this covers both privacy rules. Synchronous, ahead of
    // any nav teardown, so audio is never audible behind the disguise.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.playWhenReady = false
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Playhead poll ~2 Hz, paused while the seekbar owns the finger (so it never fights a drag —
    // the seekbar renders its own internal drag position). Same cadence as the video overlay.
    var positionMs by remember(itemId) { mutableLongStateOf(0L) }
    var durationMs by remember(itemId) { mutableLongStateOf(0L) }
    var seekbarDragging by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            if (!seekbarDragging) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.let { if (it > 0L) it else 0L }
            }
            delay(500)
        }
    }

    val error = playbackError
    if (error != null) {
        // Graceful failure (spec §6) — an unsupported audio codec/container reads as "format isn't
        // supported", never a crash or a silent screen. Reused verbatim from the video path.
        UnsupportedMediaPage(error)
        return
    }

    AudioNowPlayingContent(
        title = title,
        artwork = artwork,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        playlist = playlist,
        fileActions = fileActions,
        loadThumbnail = { thumbItem -> VaultThumbnailPipeline.load(context, thumbItem, repository) },
        onPlayPause = {
            // Toggle via playWhenReady so a paused track can resume without re-preparing the source.
            player.playWhenReady = !player.playWhenReady
        },
        onSeek = { ms ->
            player.seekTo(ms)
            positionMs = ms
        },
        onSeekbarDraggingChanged = { dragging ->
            seekbarDragging = dragging
            onSeekbarDraggingChanged(dragging)
        },
    )
}

/**
 * The stateless now-playing composition — title, album art (or music-note placeholder),
 * elapsed/total, the reused [VideoSeekbar], the transport row (Prev / Play-Pause / Next), and the
 * playlist + order-mode sheet. Holds no [Player] reference, so it renders from plain state and is
 * fully instrumentable in a Compose test (the APP-526 DoD surface).
 */
@Composable
internal fun AudioNowPlayingContent(
    title: String,
    artwork: ImageBitmap?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playlist: VideoPlaylistController,
    fileActions: ViewerFileActions,
    loadThumbnail: (suspend (VaultItem) -> ImageBitmap?)?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekbarDraggingChanged: (Boolean) -> Unit,
) {
    var playlistSheetVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).testTag(AUDIO_PLAYER_TAG)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Temporary top bar (reused): Back + ⋯ file overflow (Info/Share/Move/Unhide/Delete).
            ImmersivePlayerTopBar(fileActions = fileActions, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(24.dp))

            // ---- Album art (in-stream embedded picture) or a music-note placeholder ----
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AudioArtScrim)
                        .testTag(AUDIO_ART_TAG),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = VaultCategory.AUDIOS.icon(),
                        contentDescription = null,
                        tint = AudioOnCanvasMuted,
                        modifier = Modifier.fillMaxSize(0.4f),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // ---- Title ----
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(AUDIO_TITLE_TAG),
            )

            Spacer(Modifier.height(24.dp))

            // ---- Seekbar (reused verbatim: seek-on-release, exactly one seek per drag) + total ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                VideoSeekbar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                    onDraggingChanged = onSeekbarDraggingChanged,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = VideoGestureMath.formatTime(durationMs),
                    color = AudioOnCanvasMuted,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Transport row: Previous · Play/Pause · Next · Playlist/order ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = { playlist.onPrevious() }, modifier = Modifier.testTag(AUDIO_PREV_TAG)) {
                    Icon(VaultActionIcons.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AudioAccent)
                            .clickable(onClick = onPlayPause)
                            .testTag(AUDIO_PLAY_PAUSE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) VaultActionIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { playlist.onNext() }, modifier = Modifier.testTag(AUDIO_NEXT_TAG)) {
                    Icon(VaultActionIcons.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                IconButton(
                    onClick = { playlistSheetVisible = true },
                    modifier = Modifier.testTag(AUDIO_PLAYLIST_TAG),
                ) {
                    Icon(VaultActionIcons.PlaylistPlay, "Playlist", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }

    // ---- Playlist + order-mode bottom sheet ----
    if (playlistSheetVisible) {
        AudioPlaylistSheet(
            playlist = playlist,
            loadThumbnail = loadThumbnail,
            onDismiss = { playlistSheetVisible = false },
        )
    }
}

/**
 * The audio playlist / order-mode sheet (spec §5d): the current-folder audio files (tap a row to
 * switch playback) and the five [OrderMode]s. Mirrors the video playlist sheet but scoped to audio
 * items so a mixed context never lists videos here.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlaylistSheet(
    playlist: VideoPlaylistController,
    loadThumbnail: (suspend (VaultItem) -> ImageBitmap?)?,
    onDismiss: () -> Unit,
) {
    // Page index → item, keeping only audio so onSelect always jumps to the right pager page.
    val audioRows =
        remember(playlist.items) {
            playlist.items.mapIndexedNotNull { index, vaultItem ->
                if (vaultItem.category == VaultCategory.AUDIOS) index to vaultItem else null
            }
        }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            text = "Playlist (${audioRows.size})",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            itemsIndexed(audioRows) { _, (pageIndex, audioItem) ->
                val isCurrent = pageIndex == playlist.currentIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = audioItem.originalName,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        if (audioItem.durationMs > 0L) {
                            Text(VideoGestureMath.formatTime(audioItem.durationMs))
                        }
                    },
                    leadingContent = {
                        AudioRowThumbnail(item = audioItem, isCurrent = isCurrent, loadThumbnail = loadThumbnail)
                    },
                    modifier =
                        Modifier
                            .testTag("$AUDIO_ROW_TAG_PREFIX$pageIndex")
                            .clickable {
                                playlist.onSelect(pageIndex)
                                onDismiss()
                            },
                )
            }
        }

        HorizontalDivider()
        Text(
            text = "Order mode",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                modifier =
                    Modifier
                        .testTag("$AUDIO_ORDER_TAG_PREFIX${mode.name}")
                        .clickable { playlist.onOrderModeChanged(mode) },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A playlist-row leading tile: the item's encrypted thumbnail (music-note art) with a now-playing badge. */
@Composable
private fun AudioRowThumbnail(
    item: VaultItem,
    isCurrent: Boolean,
    loadThumbnail: (suspend (VaultItem) -> ImageBitmap?)?,
) {
    var thumbnail by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { thumbnail = loadThumbnail?.invoke(item) }
    Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(AudioArtScrim),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = VaultCategory.AUDIOS.icon(),
                contentDescription = null,
                tint = AudioOnCanvasMuted,
                modifier = Modifier.size(24.dp),
            )
        }
        if (isCurrent) {
            Icon(
                imageVector = VaultActionIcons.Pause,
                contentDescription = "Now playing",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Prefer the stream tag title; fall back to the always-present vault file name. Blank tags are ignored. */
internal fun displayTitle(
    metadata: MediaMetadata,
    fallbackName: String,
): String = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: fallbackName

/**
 * Decode the in-stream embedded album art ([MediaMetadata.artworkData]) to an [ImageBitmap] in
 * memory — never a plaintext file on disk. Returns null (→ placeholder) when there is no art or the
 * bytes fail to decode.
 */
private fun decodeArtwork(data: ByteArray?): ImageBitmap? {
    if (data == null || data.isEmpty()) return null
    return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
}

// ---- Test tags (APP-526 DoD surface) ----
internal const val AUDIO_PLAYER_TAG = "audio.nowPlaying"
internal const val AUDIO_ART_TAG = "audio.art"
internal const val AUDIO_TITLE_TAG = "audio.title"
internal const val AUDIO_PLAY_PAUSE_TAG = "audio.playPause"
internal const val AUDIO_PREV_TAG = "audio.prev"
internal const val AUDIO_NEXT_TAG = "audio.next"
internal const val AUDIO_PLAYLIST_TAG = "audio.playlist"
internal const val AUDIO_ROW_TAG_PREFIX = "audio.row."
internal const val AUDIO_ORDER_TAG_PREFIX = "audio.order."

private val AudioArtScrim = Color(0xFF1C1C1E)
private val AudioAccent = Color(0xFFF59E0B)
private val AudioOnCanvasMuted = Color(0xB3FFFFFF)
