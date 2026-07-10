package com.appblish.calculatorvault.vault.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * CalcVault Phase B · Wave 4 · APP-351 — the **in-app Mini Player** session (spec §5c),
 * Architect-signed-off Option A (APP-374). It lifts `ExoPlayer` ownership *above* the
 * `NavHost` for the brief span the player floats over the vault, so playback survives the
 * `VIEWER → vault` nav transition and follows the user as they browse — WITHOUT a system
 * overlay (no `SYSTEM_ALERT_WINDOW`; the window is a pure in-app Compose overlay clamped to
 * the app content bounds by [MiniPlayerLayout]).
 *
 * **Holder = activity-scoped `ViewModel`, never a `@Singleton`/`object` (APP-374 constraint #1).**
 * The session holds *decrypted playback*; its lifetime must be ≤ the vault Activity window. A
 * ViewModel survives config change (the only lifetime Option A needs) and is torn down in
 * [onCleared] on finish — it can never dangle a live decrypting player after the vault Activity
 * is gone. Obtain it via `viewModel(viewModelStoreOwner = activity)` so every in-vault
 * destination shares the one instance.
 *
 * **Relock seam (APP-374 constraint #2).** The session registers its own
 * `ProcessLifecycleOwner` `ON_STOP` observer that **synchronously releases the player and sets
 * [MiniPlayerLayout.Mode.CLOSED]** the instant the app backgrounds — so decrypted playback can
 * never continue behind the calculator disguise. `VaultNavHost` additionally calls
 * [releaseAndClose] in its relock branch for deterministic ordering before the CALCULATOR
 * navigation. There is **no auto-resume**: playback does not survive backgrounding, by design.
 *
 * **Zero persistence (APP-374 constraint #4).** Mode / offset / playlist index / itemId live
 * only in memory (mirroring the vault key); nothing is written to `SavedStateHandle`,
 * preferences, DataStore, or disk. On process death the session dies with the process and
 * `VaultNavHost.requiresLockOnColdRestore` forces the calculator lock.
 *
 * The mini window's Next/Prev reuse the JVM-tested [PlaylistEngine]; the same `ExoPlayer`
 * instance is reused across minimize/expand and across track switches (APP-374 constraint #6 —
 * no new player, no re-decrypt from zero).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MiniPlayerSession : ViewModel() {
    /** Full (in-viewer) ⇄ Mini (floating overlay) ⇄ Closed. Compose-observable. */
    var mode by mutableStateOf(MiniPlayerLayout.Mode.FULL)
        private set

    /** The floating window's top-left position within the app content area (clamped). */
    var offset by mutableStateOf(MiniPlayerLayout.Offset(0f, 0f))
        private set

    /** Mirrors the adopted player's play/pause state for the mini window's toggle. */
    var isPlaying by mutableStateOf(false)
        private set

    /** The video currently bound to the session (mini window / pending expand target). */
    var currentItemId by mutableStateOf<String?>(null)
        private set

    /** The category/folder the mini item belongs to — the Expand navigation target route. */
    var expandCategory: VaultCategory? = null
        private set
    var expandFolderId: String? = null
        private set

    private var player: ExoPlayer? = null
    private var videoIds: List<String> = emptyList()
    private var index: Int = 0
    private var orderMode: OrderMode = OrderMode.ORDER
    private var placed = false

    /** The live player for the overlay's `PlayerView`; null when no session player is held. */
    val boundPlayer: ExoPlayer? get() = player

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Mini-mode auto-advance mirrors §5d order-mode semantics via the tested engine.
                if (playbackState ==
                    Player.STATE_ENDED
                ) {
                    advance(PlaylistEngine.onCompletion(videoIds.size, index, orderMode))
                }
            }
        }

    // The security seam: background → release + CLOSED, synchronously, before the disguise shows.
    private val stopObserver =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) releaseAndClose()
        }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(stopObserver)
    }

    /**
     * Minimize the full player into the floating window: adopt the *live* [exoPlayer] (no new
     * instance, no re-decrypt), snapshot the current-folder video playlist, and flip to MINI.
     * The viewer must NOT release a player once handed here (it checks [owns]).
     */
    fun minimize(
        exoPlayer: ExoPlayer,
        itemId: String,
        category: VaultCategory,
        folderId: String?,
        playlistVideoIds: List<String>,
        currentIndex: Int,
        order: OrderMode,
    ) {
        player?.takeIf { it !== exoPlayer }?.let { it.removeListener(listener) }
        player = exoPlayer
        videoIds = playlistVideoIds
        index = currentIndex.coerceIn(0, (playlistVideoIds.size - 1).coerceAtLeast(0))
        orderMode = order
        currentItemId = itemId
        expandCategory = category
        expandFolderId = folderId
        placed = false
        exoPlayer.addListener(listener)
        isPlaying = exoPlayer.isPlaying
        mode = MiniPlayerLayout.Mode.MINI
    }

    /** True while the mini window is expanding back into the viewer for [itemId] (start playing). */
    fun isExpandingInto(itemId: String): Boolean =
        mode == MiniPlayerLayout.Mode.FULL && player != null && currentItemId == itemId

    /**
     * Hand the adopted player back to the viewer on Expand (same instance) and yield ownership.
     * Returns null when this page is not the expand target, so the viewer builds its own player.
     */
    fun consumePlayerForExpand(itemId: String): ExoPlayer? {
        val p = player ?: return null
        if (mode == MiniPlayerLayout.Mode.FULL && currentItemId == itemId) {
            p.removeListener(listener)
            player = null
            return p
        }
        return null
    }

    /** True when [exoPlayer] is the very instance the session owns (viewer skips releasing it). */
    fun owns(exoPlayer: ExoPlayer): Boolean = exoPlayer === player

    /** Expand: MINI → FULL. The player is kept until the re-entered viewer consumes it. */
    fun expand() {
        if (mode == MiniPlayerLayout.Mode.MINI) mode = MiniPlayerLayout.Mode.FULL
    }

    /** Mini window Play/Pause. */
    fun togglePlayPause() {
        player?.let { it.playWhenReady = !it.playWhenReady }
    }

    /** Mini window Next — always advances one, wrapping (spec §5d manual Next). */
    fun next() = advance(PlaylistEngine.manualNext(videoIds.size, index))

    /** Mini window Previous — always steps back one, wrapping (spec §5d manual Prev). */
    fun previous() = advance(PlaylistEngine.manualPrev(videoIds.size, index))

    private fun advance(to: Int?) {
        val target = to ?: return
        val id = videoIds.getOrNull(target) ?: return
        index = target
        currentItemId = id
        player?.apply {
            setMediaSource(buildVaultVideoSource(id))
            prepare()
            playWhenReady = true
        }
    }

    /** Seed the resting corner once per mini session; the overlay reports its container size. */
    fun placeInitial(
        containerW: Float,
        containerH: Float,
        playerW: Float,
        playerH: Float,
    ) {
        if (placed) return
        offset = MiniPlayerLayout.initialOffset(containerW, containerH, playerW, playerH)
        placed = true
    }

    /** Apply a drag delta from the overlay; clamped to stay fully in-window (privacy invariant). */
    fun drag(
        containerW: Float,
        containerH: Float,
        playerW: Float,
        playerH: Float,
        dx: Float,
        dy: Float,
    ) {
        offset = MiniPlayerLayout.drag(containerW, containerH, playerW, playerH, offset, dx, dy)
    }

    /** Mini window Close: stop + dismiss. */
    fun close() = releaseAndClose()

    /**
     * Release the adopted `ExoPlayer` and reset to CLOSED. Idempotent; safe to call from the
     * relock seam, the mini window's Close, and [onCleared]. Synchronous so nothing decoded
     * outlives it.
     */
    fun releaseAndClose() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        videoIds = emptyList()
        index = 0
        currentItemId = null
        expandCategory = null
        expandFolderId = null
        isPlaying = false
        placed = false
        mode = MiniPlayerLayout.Mode.CLOSED
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(stopObserver)
        releaseAndClose()
    }
}

/**
 * Build the plain (subtitle-less) seekable-decrypting media source for a vault video — the
 * mini window's Next/Prev source swap (APP-347 `EncryptedVaultDataSource`: decrypt-on-demand,
 * no plaintext temp). The item id → blob/key resolution stays inside the factory hook.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun buildVaultVideoSource(itemId: String): MediaSource =
    ProgressiveMediaSource
        .Factory(EncryptedVaultDataSource.Factory { id -> VaultGraph.contentRepository.openBlobReader(id) })
        .createMediaSource(MediaItem.fromUri(EncryptedVaultDataSource.vaultMediaUri(itemId)))
