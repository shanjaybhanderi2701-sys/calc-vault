package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.media.BulkOpProgress
import com.appblish.calculatorvault.vault.media.MediaSource
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Board-added Phase-1 check #4 (APP-237/APP-241): **operation feedback appears** — result
 * summaries and bulk progress (P2-3). Proven at the real seams:
 *
 *  1. A genuine end-to-end hide through [HideImportViewModel] + [MediaSource] (real
 *     MediaStore fixture → real encrypted vault) publishes the "1 hidden" summary the
 *     category screen renders, and — under All Files Access (APP-248) — removes the public
 *     original directly (no delete-consent staging: [HideImportState.pendingDeleteUris]
 *     stays empty and the MediaStore row is gone).
 *  2. A bulk (2-item) repository hide publishes live [BulkOpProgress] — the flow behind
 *     both the picker's inline progress bar and the foreground-service notification.
 *  3. The composed [CategoryScreen] actually renders a pending summary to the user (the
 *     snackbar surface), so the feedback is visible, not just published.
 */
@RunWith(AndroidJUnit4::class)
class OpFeedbackDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_ops"
    private val bucket = "CalcVaultDoDOps"
    private val relativePath = "Pictures/$bucket/"
    private val nameA = "calcvault_dod_ops_a_${System.nanoTime()}.jpg"
    private val nameB = "calcvault_dod_ops_b_${System.nanoTime()}.jpg"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
        HideImportViewModel.consumeHideSummary()
    }

    @After
    fun cleanUp() {
        HideImportViewModel.consumeHideSummary()
        DoDTestSupport.deleteImageRows(context, nameA)
        DoDTestSupport.deleteImageRows(context, nameB)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun endToEndHidePublishesResultSummaryAndDeletesOriginalDirectly() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)
            DoDTestSupport.insertPublicImage(context, nameA, relativePath, DoDTestSupport.sampleJpegBytes())
            assertThat(DoDTestSupport.imageRowCount(context, nameA)).isEqualTo(1)

            val viewModel =
                HideImportViewModel(VaultCategory.PHOTOS, repository = repo, mediaSource = MediaSource(context))
            viewModel.onPermissionResult(true)
            val album =
                withTimeout(15_000) {
                    viewModel.state
                        .first { state -> state.albums.any { it.name == bucket } }
                        .albums
                        .first { it.name == bucket }
                }
            viewModel.selectAlbum(album.id)
            val source =
                withTimeout(15_000) {
                    viewModel.state
                        .first { state -> state.sources.any { it.name == nameA } }
                        .sources
                        .first { it.name == nameA }
                }
            viewModel.toggle(source.id)
            viewModel.hideNow()

            // P2-3 result summary: published for the category screen the user pops back to.
            val summary = withTimeout(15_000) { HideImportViewModel.hideSummary.filterNotNull().first() }
            assertThat(summary).isEqualTo("1 hidden")
            // APP-248: with All Files Access the original is removed directly — the flow
            // completes (done) with NO delete-consent staging (empty pendingDeleteUris) and
            // the public MediaStore row is already gone (no dialog needed).
            val settled = withTimeout(15_000) { viewModel.state.first { it.done } }
            assertThat(settled.pendingDeleteUris).isEmpty()
            assertThat(DoDTestSupport.imageRowCount(context, nameA)).isEqualTo(0)
        }

    @Test
    fun bulkHidePublishesLiveProgress() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)
            val uriA = DoDTestSupport.insertPublicImage(context, nameA, relativePath, DoDTestSupport.sampleJpegBytes())
            val uriB = DoDTestSupport.insertPublicImage(context, nameB, relativePath, DoDTestSupport.sampleJpegBytes())

            // Subscribe BEFORE the batch so the first published state can't be missed.
            val firstProgress =
                async(Dispatchers.Default) {
                    withTimeout(15_000) { BulkOpProgress.progress.filterNotNull().first() }
                }
            delay(100)
            repo.hide(
                listOf(
                    staged("staged-a", nameA, uriA.toString()),
                    staged("staged-b", nameB, uriB.toString()),
                ),
            )
            val progress = firstProgress.await()
            assertThat(progress.total).isEqualTo(2)
            assertThat(progress.label).isNotEmpty()
        }

    @Test
    fun categoryScreenRendersPendingSummaryToTheUser() {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        HideImportViewModel.publishHideSummary("2 hidden")

        val viewModel = CategoryViewModel(VaultCategory.PHOTOS, repository = repo)
        compose.setContent {
            CalculatorVaultTheme {
                CategoryScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenItem = {},
                    onHide = {},
                )
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("2 hidden", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun staged(
        id: String,
        name: String,
        uri: String,
    ): VaultItem =
        VaultItem(
            id = id,
            category = VaultCategory.PHOTOS,
            originalName = name,
            dateLabel = "Today",
            sortKey = System.currentTimeMillis(),
            sourceUri = uri,
            mimeType = "image/jpeg",
            relativePath = relativePath,
        )
}
