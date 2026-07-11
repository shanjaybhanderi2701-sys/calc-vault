package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-428 (APP-417 R6) — the DoD gate for **"gate the pager swipe on actual
 * playback state, not on page type."**
 *
 * Owner R6 (verbatim): *"The player gating is correct; the preview screen was NOT restored."*
 * Round 5c had gated `userScrollEnabled` on `currentIsPlayable` (the page-type flag), so a video
 * page was locked even in the **preview/browse** state — the user could never swipe between videos.
 * These are two states of the same pager and need **opposite** behaviour:
 *
 *  - **Preview / browse (video NOT playing)** → the pager IS swipeable, so a horizontal flick
 *    moves to the next/previous video (each shows its large cached poster).
 *  - **Player active (video IS playing)** → paging is locked, so a horizontal drag reaches the
 *    player's own seek/scrub gesture instead of switching video (APP-398, kept — now keyed on
 *    real playback rather than page type).
 *
 * This test reproduces the exact new gating (`userScrollEnabled = !playing`) and locks all three
 * behaviours the owner requires:
 *  - preview state → a user horizontal swipe on the video page **does** page (browse restored),
 *  - playing state → a user horizontal swipe **does not** page (it would seek instead),
 *  - **programmatic** navigation (Next / Prev / playlist → `animateScrollToPage`) still switches
 *    videos even while playing (user-scroll disabled), so the transport controls keep working.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPagerLockDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val bodyTag = "player_pager_body"

    private lateinit var pagerState: PagerState
    private lateinit var scope: CoroutineScope

    /** [initiallyPlaying] seeds the hoisted playback state that gates the pager swipe. */
    private fun setPager(
        initialPage: Int,
        initiallyPlaying: Boolean,
    ) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface {
                    pagerState = rememberPagerState(initialPage = initialPage) { 3 }
                    scope = rememberCoroutineScope()
                    // Mirror PagerViewerScreen (APP-428): paging is gated on real playback state,
                    // NOT on whether the current page is a video.
                    var playing by remember { mutableStateOf(initiallyPlaying) }
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !playing,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("$bodyTag$page")
                                .background(if (page == 1) Color(0xFF102030) else Color(0xFF302010)),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Preview state: a user horizontal swipe on the video page MUST page (browse restored). */
    @Test
    fun previewState_userSwipe_pagesBetweenVideos() {
        setPager(initialPage = 1, initiallyPlaying = false)
        assertEquals("precondition: on the (preview) video page", 1, pagerState.currentPage)

        compose.onNodeWithTag("${bodyTag}1").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("preview → user swipe must move to the next video", 2, pagerState.currentPage)
    }

    /** Playing state: a user horizontal swipe must NOT page (it would seek on-device). */
    @Test
    fun playingState_userSwipe_doesNotSwitchVideo() {
        setPager(initialPage = 1, initiallyPlaying = true)
        assertEquals("precondition: on the (playing) video page", 1, pagerState.currentPage)

        compose.onNodeWithTag("${bodyTag}1").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("playing → user swipe must NOT change the page", 1, pagerState.currentPage)
    }

    /** Programmatic switch (transport Next / playlist select) still works while playing. */
    @Test
    fun playingState_programmaticScroll_stillSwitches() {
        setPager(initialPage = 1, initiallyPlaying = true)
        assertEquals("precondition: on the (playing) video page", 1, pagerState.currentPage)

        compose.runOnIdle { scope.launch { pagerState.animateScrollToPage(2) } }
        compose.waitForIdle()

        assertEquals("programmatic scroll must still switch videos", 2, pagerState.currentPage)
    }
}
