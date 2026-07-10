package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private val MiniWidth = 220.dp
private val MiniHeight = 148.dp // 16:9 video (~124dp) + a control strip.
private val VideoStripHeight = 124.dp

/**
 * CalcVault Phase B · Wave 4 · APP-351 — the **in-app Mini Player** overlay (spec §5c).
 * Rendered by `VaultNavHost` in a `Box` *above* the `NavHost` (Option A, APP-374) so it floats
 * over whichever Video Vault surface the user is on — and, being an ordinary Compose overlay in
 * the app's own window, it can never float over the home screen or other apps (no
 * `SYSTEM_ALERT_WINDOW`; the §5c privacy rule holds by construction). Drag is clamped by
 * [MiniPlayerSession]/[MiniPlayerLayout] so the window can never leave the app content bounds.
 *
 * Supports the full §5c mini set: **Drag** (whole window), **Play/Pause**, **Previous/Next**
 * (through the current-folder playlist via [PlaylistEngine]), **Expand** (back to the full
 * player), **Close** (stop + dismiss). Renders only while [MiniPlayerSession.mode] is MINI.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MiniPlayerWindow(
    session: MiniPlayerSession,
    modifier: Modifier = Modifier,
) {
    if (session.mode != MiniPlayerLayout.Mode.MINI) return
    val player = session.boundPlayer ?: return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val playerWpx = with(density) { MiniWidth.toPx() }
        val playerHpx = with(density) { MiniHeight.toPx() }

        // Seed the resting corner once we know the surface size (bottom-end, spec §5c).
        LaunchedEffect(containerW, containerH) {
            session.placeInitial(containerW, containerH, playerWpx, playerHpx)
        }

        val offset = session.offset
        Box(
            modifier =
                Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(MiniWidth, MiniHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    // Drag the whole window; the session re-clamps every step so it stays on-screen.
                    .pointerInput(containerW, containerH) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            session.drag(containerW, containerH, playerWpx, playerHpx, dragAmount.x, dragAmount.y)
                        }
                    },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(VideoStripHeight),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        setBackgroundColor(AndroidColor.BLACK)
                    }
                },
                update = { view -> view.player = player },
            )

            // Transport strip: Prev · Play/Pause · Next · Expand · Close.
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(MiniHeight - VideoStripHeight)
                        .padding(horizontal = 2.dp),
            ) {
                MiniControl(VaultActionIcons.SkipPrevious, "Previous") { session.previous() }
                MiniControl(
                    if (session.isPlaying) VaultActionIcons.Pause else Icons.Filled.PlayArrow,
                    if (session.isPlaying) "Pause" else "Play",
                ) { session.togglePlayPause() }
                MiniControl(VaultActionIcons.SkipNext, "Next") { session.next() }
                MiniControl(VaultActionIcons.Fullscreen, "Expand") { session.expand() }
                MiniControl(Icons.Filled.Close, "Close") { session.close() }
            }
        }
    }
}

@Composable
private fun MiniControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
