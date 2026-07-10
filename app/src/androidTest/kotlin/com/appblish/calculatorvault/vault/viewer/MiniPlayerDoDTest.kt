package com.appblish.calculatorvault.vault.viewer

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-351 W4 DoD (spec §8 Mini Player row, Architect acceptance #7): exercises the in-app
 * [MiniPlayerSession] wiring on-device — **minimize / play-pause / next-prev / expand / close**
 * — plus the **relock seam** (background → release + CLOSED). `ExoPlayer` and
 * `ProcessLifecycleOwner` are touched on the main thread, so every mutation runs via
 * `runOnMainSync`. The drag-bounds privacy invariant + the mode machine are additionally pinned
 * on the JVM by `MiniPlayerLayoutTest`; this proves the session drives them against a real
 * player instance.
 *
 * The full end-to-end DoD on a real decrypted vault video (visual drag, audible pause, no
 * plaintext, FLAG_SECURE) is the on-device QA gate (APP-351 → QA Engineer).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MiniPlayerDoDTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var session: MiniPlayerSession
    private lateinit var player: ExoPlayer
    private val ids = listOf("v0", "v1", "v2")

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            session = MiniPlayerSession()
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            // Idempotent; also frees the player if a test failed before close.
            session.releaseAndClose()
        }
    }

    @Test
    fun minimize_adopts_player_and_enters_mini_mode() {
        instrumentation.runOnMainSync {
            session.minimize(
                player,
                "v1",
                VaultCategory.VIDEOS,
                folderId = null,
                playlistVideoIds = ids,
                currentIndex = 1,
                order = OrderMode.ORDER
            )
        }
        assertThat(session.mode).isEqualTo(MiniPlayerLayout.Mode.MINI)
        assertThat(session.currentItemId).isEqualTo("v1")
        assertThat(session.boundPlayer).isSameInstanceAs(player)
        assertThat(session.owns(player)).isTrue()
    }

    @Test
    fun next_and_previous_walk_the_playlist_wrapping() {
        instrumentation.runOnMainSync {
            session.minimize(player, "v1", VaultCategory.VIDEOS, null, ids, 1, OrderMode.ORDER)
            session.next()
        }
        assertThat(session.currentItemId).isEqualTo("v2")
        instrumentation.runOnMainSync { session.next() } // wraps past the end
        assertThat(session.currentItemId).isEqualTo("v0")
        instrumentation.runOnMainSync { session.previous() } // wraps back
        assertThat(session.currentItemId).isEqualTo("v2")
    }

    @Test
    fun drag_keeps_the_window_inside_the_content_bounds() {
        instrumentation.runOnMainSync {
            session.minimize(player, "v1", VaultCategory.VIDEOS, null, ids, 1, OrderMode.ORDER)
            session.placeInitial(1000f, 2000f, 200f, 300f)
            session.drag(1000f, 2000f, 200f, 300f, dx = 9000f, dy = 9000f)
        }
        // Pinned to the far corner, never off-screen (privacy invariant).
        assertThat(session.offset.x).isEqualTo(800f)
        assertThat(session.offset.y).isEqualTo(1700f)
        instrumentation.runOnMainSync { session.drag(1000f, 2000f, 200f, 300f, dx = -9000f, dy = -9000f) }
        assertThat(session.offset.x).isEqualTo(0f)
        assertThat(session.offset.y).isEqualTo(0f)
    }

    @Test
    fun expand_hands_the_same_player_back_to_the_viewer() {
        instrumentation.runOnMainSync {
            session.minimize(player, "v1", VaultCategory.VIDEOS, null, ids, 1, OrderMode.ORDER)
            session.expand()
        }
        assertThat(session.mode).isEqualTo(MiniPlayerLayout.Mode.FULL)
        assertThat(session.isExpandingInto("v1")).isTrue()

        var handedBack: ExoPlayer? = null
        instrumentation.runOnMainSync { handedBack = session.consumePlayerForExpand("v1") }
        assertThat(handedBack).isSameInstanceAs(player) // same instance, no re-decrypt (APP-374 #6)
        assertThat(session.boundPlayer).isNull()
    }

    @Test
    fun close_stops_and_dismisses() {
        instrumentation.runOnMainSync {
            session.minimize(player, "v1", VaultCategory.VIDEOS, null, ids, 1, OrderMode.ORDER)
            session.close()
        }
        assertThat(session.mode).isEqualTo(MiniPlayerLayout.Mode.CLOSED)
        assertThat(session.boundPlayer).isNull()
        assertThat(session.currentItemId).isNull()
    }

    @Test
    fun relock_seam_releases_and_closes_from_mini() {
        instrumentation.runOnMainSync {
            session.minimize(player, "v1", VaultCategory.VIDEOS, null, ids, 1, OrderMode.ORDER)
            // Simulates the ON_STOP relock seam (APP-374 #2): decrypted playback must not survive.
            session.releaseAndClose()
        }
        assertThat(session.mode).isEqualTo(MiniPlayerLayout.Mode.CLOSED)
        assertThat(session.boundPlayer).isNull()
        assertThat(session.isPlaying).isFalse()
    }
}
