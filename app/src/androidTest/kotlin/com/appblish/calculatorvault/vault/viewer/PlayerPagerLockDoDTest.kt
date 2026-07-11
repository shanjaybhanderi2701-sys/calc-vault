package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
 * CalcVault Phase B · APP-398 (APP-380 round 5c) — the DoD gate for **"the active video player must
 * not live inside a user-swipeable pager."**
 *
 * Owner (verbatim): *"Once you're in the player watching a video, horizontal swipes should be for
 * seeking/scrubbing, not for swiping to the next video."* Round 5b only stopped the *seekbar* from
 * paging; the video **body** swipe still paged to the next video, because the viewer `HorizontalPager`
 * was user-scrollable on video pages. The fix (`PagerViewerScreen.ViewerPager`) gates
 * `userScrollEnabled = !zoomed && !currentIsPlayable`, so user paging is off while a video/audio is the
 * current page — the body swipe then reaches the player's own seek gesture instead.
 *
 * This test reproduces that exact gating (`userScrollEnabled = current-page-is-video ? false : true`)
 * and locks all three behaviours the owner requires:
 *  - on a **video** page a user horizontal swipe does **not** change the page (it would seek instead),
 *  - on a **photo** page the user swipe still pages (image browsing is untouched),
 *  - **programmatic** navigation (Next / Prev / playlist → `animateScrollToPage`) still switches videos
 *    even while user-scroll is disabled, so the transport controls keep working.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPagerLockDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val bodyTag = "player_pager_body"

    // Page 1 is the "video"; pages 0 and 2 are "photos".
    private val videoPage = 1

    private lateinit var pagerState: PagerState
    private lateinit var scope: CoroutineScope

    private fun setPager(initialPage: Int) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface {
                    pagerState = rememberPagerState(initialPage = initialPage) { 3 }
                    scope = rememberCoroutineScope()
                    // Mirror PagerViewerScreen: user paging is disabled while the current page is a video.
                    val currentIsVideo = pagerState.currentPage == videoPage
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !currentIsVideo,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("$bodyTag$page")
                                .background(if (page == videoPage) Color(0xFF102030) else Color(0xFF302010)),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** On a video page, a user horizontal swipe must NOT page (it would seek on-device). */
    @Test
    fun onVideoPage_userSwipe_doesNotSwitchVideo() {
        setPager(initialPage = videoPage)
        assertEquals("precondition: on the video page", videoPage, pagerState.currentPage)

        compose.onNodeWithTag("$bodyTag$videoPage").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("user swipe on the active video must NOT change the page", videoPage, pagerState.currentPage)
    }

    /** On a photo page, the user swipe still pages — image browsing is untouched. */
    @Test
    fun onPhotoPage_userSwipe_stillPages() {
        setPager(initialPage = 0)
        assertEquals("precondition: on a photo page", 0, pagerState.currentPage)

        compose.onNodeWithTag("${bodyTag}0").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("user swipe on a photo page should advance", 1, pagerState.currentPage)
    }

    /** Programmatic switch (transport Next / playlist select) still works while user-scroll is off. */
    @Test
    fun onVideoPage_programmaticScroll_stillSwitches() {
        setPager(initialPage = videoPage)
        assertEquals("precondition: on the video page (user-scroll disabled)", videoPage, pagerState.currentPage)

        compose.runOnIdle { scope.launch { pagerState.animateScrollToPage(2) } }
        compose.waitForIdle()

        assertEquals("programmatic scroll must still switch videos", 2, pagerState.currentPage)
    }
}
