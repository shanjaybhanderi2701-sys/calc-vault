package com.appblish.playerkit

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The **shared, app-agnostic video-player surface** (APP-408) extracted from the origin app's video
 * player: an ExoPlayer/`SurfaceView` host wired onto the tested [VideoGestureMath] / [VideoScaleMath]
 * / [VideoZoomMath] cores (APP-402). It knows nothing about where bytes live — the caller hands it a
 * [createMediaSource] built from a [PlaybackSource] (a plain-file source for the gallery app, a
 * decode-on-read source for the secure-media app) — and nothing about either app's model: poster and
 * error chrome are [poster] / [errorOverlay] slots and the brand [accentColor] is a parameter.
 *
 * Scrub preview is optional and equally decoupled: pass a [storyboardSource] and the seekbar shows a
 * live thumbnail while dragging (APP-438); pass `null` and it simply shows the time-code bubble. The
 * kit acquires + decodes + caches the strip ([VideoStoryboardCache]); it never learns the byte origin.
 *
 * Controls are Compose, not `PlayerView`, so gesture ownership is deterministic (design §3): the
 * scrub [Slider] consumes its own drags, taps toggle chrome, double-taps seek ±10s by half
 * ([VideoGestureMath]), pinch zooms 1–5× and pans ([VideoZoomMath]), and the ⤢ button toggles
 * Fit⇄Fill ([VideoScaleMath]). Leaving the page ([isActive]=false) releases the decoder back to the
 * poster; a decode error tears the half-open player down and surfaces [errorOverlay].
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerSurface(
    pageKey: Any,
    createMediaSource: () -> MediaSource,
    isActive: Boolean,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    initialAspect: Float,
    initialDurationMs: Long,
    accentColor: Color,
    modifier: Modifier = Modifier,
    storyboardSource: StoryboardSource? = null,
    onError: (PlaybackException) -> Unit = {},
    poster: @Composable () -> Unit,
    errorOverlay: @Composable (PlaybackException) -> Unit,
) {
    val context = LocalContext.current
    var player by remember(pageKey) { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember(pageKey) { mutableStateOf(false) }
    var ended by remember(pageKey) { mutableStateOf(false) }
    var firstFrameRendered by remember(pageKey) { mutableStateOf(false) }
    var aspect by remember(pageKey) { mutableFloatStateOf(initialAspect) }
    var durationMs by remember(pageKey) { mutableLongStateOf(initialDurationMs) }
    var positionMs by remember(pageKey) { mutableLongStateOf(0L) }
    var scrubFraction by remember(pageKey) { mutableStateOf<Float?>(null) }
    var videoError by remember(pageKey) { mutableStateOf<PlaybackException?>(null) }
    var aspectMode by remember(pageKey) { mutableStateOf(VideoScaleMath.AspectMode.FIT) }
    var storyboard by remember(pageKey) { mutableStateOf<VideoStoryboard.Strip?>(null) }
    val zoomState = remember(pageKey) { VideoZoomState() }
    val currentChromeVisible by rememberUpdatedState(chromeVisible)

    fun startPlayback() {
        ended = false
        val existing = player
        if (existing != null) {
            existing.play()
            return
        }
        player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true, // explicit play with sound — take focus like a video app
            )
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            ended = true
                            onChromeVisibleChange(true)
                        }
                        if (state == Player.STATE_READY) {
                            duration.takeIf { it > 0 }?.let { durationMs = it }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrameRendered = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        videoError = error
                        onError(error)
                    }
                })
                setMediaSource(createMediaSource())
                prepare()
                playWhenReady = true
            }
    }

    // Swiped off this page → release the decoder and fall back to the poster. Also clears any codec
    // error and any zoom, so swiping back re-attempts fresh at 1× (the page's implicit retry).
    LaunchedEffect(isActive) {
        if (!isActive) {
            player?.release()
            player = null
            isPlaying = false
            firstFrameRendered = false
            positionMs = 0L
            videoError = null
            zoomState.reset()
        }
    }
    // A codec/decode error came in → tear the half-open player down (outside the listener callback)
    // so the page settles on poster + error overlay and never keeps a broken decoder around.
    LaunchedEffect(videoError) {
        if (videoError != null) {
            player?.release()
            player = null
            isPlaying = false
            firstFrameRendered = false
        }
    }
    // App leaves the foreground → pause (audio must not keep playing over other apps).
    LifecycleStartEffect(pageKey) {
        onStopOrDispose { player?.pause() }
    }
    DisposableEffect(pageKey) {
        onDispose {
            player?.release()
            player = null
        }
    }
    // Progress ticker for the scrub bar — cheap poll, only while a player exists.
    LaunchedEffect(player) {
        while (isActive && player != null) {
            player?.let { positionMs = it.currentPosition.coerceAtLeast(0L) }
            delay(POSITION_POLL_MS)
        }
    }
    // Controls auto-hide after 3s of uninterrupted playback (not mid-scrub).
    LaunchedEffect(chromeVisible, isPlaying, scrubFraction) {
        if (chromeVisible && isPlaying && scrubFraction == null) {
            delay(CONTROLS_AUTO_HIDE_MS)
            onChromeVisibleChange(false)
        }
    }
    // Lazily fetch the scrub-preview strip the first time the user grabs the seekbar — a source may
    // do real work (extract frames / read a sealed strip), so we don't pay for it on pages that are
    // never scrubbed. The kit's cache decodes once and re-serves for the whole drag. Keyed by
    // pageKey so each item's strip is cached independently.
    LaunchedEffect(pageKey, storyboardSource, scrubFraction != null) {
        val source = storyboardSource
        if (source != null && scrubFraction != null && storyboard == null) {
            storyboard = VideoStoryboardCache.load(pageKey.toString(), source)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .videoPlayerGestures(
                state = zoomState,
                onToggleChrome = { onChromeVisibleChange(!currentChromeVisible) },
                onDoubleTapSeek = { zone ->
                    val p = player ?: return@videoPlayerGestures
                    val delta = VideoGestureMath.seekDeltaMs(zone, tapCount = 1)
                    p.seekTo(VideoGestureMath.seekTo(p.currentPosition, delta, durationMs))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        player?.let { activePlayer ->
            val fillScale =
                if (aspectMode == VideoScaleMath.AspectMode.FILL) {
                    coverScale(aspect, zoomState.containerWidth, zoomState.containerHeight)
                } else {
                    1f
                }
            AndroidView(
                factory = { SurfaceView(it) },
                update = { surface -> activePlayer.setVideoSurfaceView(surface) },
                modifier = Modifier
                    .aspectRatio(if (aspect > 0f) aspect else DEFAULT_ASPECT)
                    .graphicsLayer {
                        val s = zoomState.scale * fillScale
                        scaleX = s
                        scaleY = s
                        translationX = zoomState.panX
                        translationY = zoomState.panY
                    },
            )
        }
        if (player == null || !firstFrameRendered) {
            poster()
        }

        val showPlayOverlay =
            videoError == null && (player == null || ended || (chromeVisible && !ended))
        if (showPlayOverlay) {
            IconButton(
                onClick = {
                    when {
                        player == null || ended -> {
                            if (ended) player?.seekTo(0)
                            startPlayback()
                        }
                        isPlaying -> player?.pause()
                        else -> player?.play()
                    }
                },
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .testTag("player_play_pause"),
            ) {
                Icon(
                    imageVector = when {
                        ended -> Icons.Filled.Replay
                        player != null && isPlaying -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = if (player != null && isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        if (videoError == null && player != null && chromeVisible) {
            // Fit⇄Fill quick toggle (VideoScaleMath.nextDisplayMode); pinch-zoom composes on top.
            IconButton(
                onClick = { aspectMode = VideoScaleMath.nextDisplayMode(aspectMode) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .testTag("player_aspect_toggle"),
            ) {
                Icon(
                    imageVector = Icons.Filled.AspectRatio,
                    contentDescription = "Aspect: ${aspectMode.label}",
                    tint = Color.White,
                )
            }
            VideoPlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                scrubFraction = scrubFraction,
                onScrub = { scrubFraction = it },
                onScrubFinished = {
                    val duration = durationMs
                    scrubFraction?.let { fraction ->
                        if (duration > 0) player?.seekTo((fraction * duration).toLong())
                    }
                    scrubFraction = null
                },
                color = accentColor,
                storyboard = storyboard,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
            )
        }

        // Graceful §8 fallback: an undecodable clip shows the poster behind the app's error chrome.
        videoError?.let { error -> errorOverlay(error) }
    }
}

/**
 * Scale that turns the letterboxed FIT surface into a crop-to-cover FILL: the larger of the two
 * axis ratios between the video's [aspect] and the container. 1× when either dimension is unknown.
 */
internal fun coverScale(aspect: Float, containerW: Float, containerH: Float): Float {
    if (aspect <= 0f || containerW <= 0f || containerH <= 0f) return 1f
    val containerAspect = containerW / containerH
    return maxOf(aspect / containerAspect, containerAspect / aspect)
}

private const val POSITION_POLL_MS = 250L
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val DEFAULT_ASPECT = 16f / 9f
