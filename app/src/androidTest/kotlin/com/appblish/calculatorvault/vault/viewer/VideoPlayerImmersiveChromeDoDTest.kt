package com.appblish.calculatorvault.vault.viewer

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.DoDTestSupport
import com.appblish.calculatorvault.vault.EncryptedVaultContentRepository
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-379 DoD — the **immersive video player contract**: a video/audio
 * page is a clean, distraction-free player (MX Player / YouTube style), NOT the photo viewer
 * wrapped in file-management chrome. A real video is hidden into the encrypted vault, the
 * actual [PagerViewerScreen] is composed on it, and:
 *
 *  - the photo-viewer's permanent **Unhide/Delete/Move/Share** bottom bar is **absent** — no
 *    file-management bar is displayed wrapped around the video by default (requirement 1/4);
 *  - the player carries a **Back** affordance and a **⋯ file-actions** overflow in its
 *    temporary top bar (requirement 4 — file actions reachable, but only in the overflow);
 *  - opening the overflow reveals the vault actions (Info/Share/Move/Unhide/Delete), so
 *    nothing is lost — they simply moved out of a permanent bar into the ⋯ menu.
 *
 * The photo page's floating chrome contract (which this must NOT regress) is proven in
 * [PhotoViewerChromeDoDTest]; encrypted streaming/seek in [VideoPlaybackDoDTest].
 */
@RunWith(AndroidJUnit4::class)
class VideoPlayerImmersiveChromeDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_video_immersive"
    private val videoName = "calcvault_dod_immersive_${System.nanoTime()}.mp4"
    private val relativePath = "Movies/CalcVaultDoD/"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteVideoRows(context, videoName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun videoPageIsImmersiveWithNoFileChromeBarAndActionsInOverflow() {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        val uri =
            DoDTestSupport.insertPublicVideo(
                context,
                videoName,
                relativePath,
                DoDTestSupport.synthesizeMp4Bytes(context),
            )
        val stored =
            runBlocking {
                repo.hide(
                    listOf(
                        VaultItem(
                            id = "staged-video",
                            category = VaultCategory.VIDEOS,
                            originalName = videoName,
                            dateLabel = "Today",
                            sortKey = 1_000,
                            sourceUri = uri.toString(),
                            mimeType = "video/mp4",
                            relativePath = relativePath,
                        ),
                    ),
                )
            }
        assertThat(stored).hasSize(1)
        val startId = stored.single().id

        val viewModel =
            PagerViewerViewModel(startId, VaultCategory.VIDEOS, folderId = null, context = context, repository = repo)
        compose.setContent {
            CalculatorVaultTheme {
                PagerViewerScreen(viewModel = viewModel, onBack = {})
            }
        }

        // The immersive player's temporary top bar is shown by default (Back + ⋯ overflow).
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("File actions").assertIsDisplayed()

        // Requirement 1/4: NO permanent file-management bar wraps the video — the photo
        // viewer's Unhide/Delete/Move/Share bottom bar is NOT present while the ⋯ is closed.
        assertThat(compose.onAllNodesWithText("Unhide").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Delete").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Move").fetchSemanticsNodes()).isEmpty()

        // Requirement 4: the file actions still exist — they moved into the ⋯ overflow.
        compose.onNodeWithContentDescription("File actions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Unhide").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Move").assertIsDisplayed()
        compose.onNodeWithText("Info").assertIsDisplayed()
    }
}
