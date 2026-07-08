package com.appblish.calculatorvault.vault

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.viewer.PageContent
import com.appblish.calculatorvault.vault.viewer.PagerViewerScreen
import com.appblish.calculatorvault.vault.viewer.PagerViewerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W3-E DoD at the Compose layer (design W3-D §4–§7), driven over the in-memory
 * repository like the shipped [AlbumMultiSelectDoDTest]:
 *
 * 1. **Pin** — N=1 overflow shows the state-aware "Pin album"/"Unpin album" item; pinning
 *    adds the badge and moves the tile to the head of the grid (two-cluster ordering).
 * 2. **Sort sheet** — the header `⇅` opens "Sort by"; a key tap applies live (grid
 *    reorders, choice lands in the repository) with no confirm button.
 * 3. **Choose-cover picker** — album overflow → full-screen picker → select + confirm
 *    writes the index pointer and returns to the grid; back cancels without a write.
 * 4. **Photo-selection Set as cover** — the selection bar's ⋯ overflow at N=1 writes the
 *    pointer for the open album and surfaces the "Set as album cover." snackbar.
 */
@RunWith(AndroidJUnit4::class)
class OrganizationUiDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        BulkOps.consume()
        HideImportViewModel.consumeHideSummary()
    }

    @After
    fun cleanUp() {
        BulkOps.consume()
        HideImportViewModel.consumeHideSummary()
    }

    private fun staged(
        id: String,
        sortKey: Long,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
        sizeBytes = sortKey * 10,
    )

    private data class Seed(
        val repo: InMemoryVaultContentRepository,
        val alphaId: String,
        val zuluId: String,
        val zuluItemIds: List<String>,
    )

    /** Albums "Alpha" (empty) and "Zulu" (2 photos) on a seedless repo. */
    private fun seed(): Seed =
        runBlocking {
            val repo = InMemoryVaultContentRepository(seed = false)
            val alpha = repo.createFolder(VaultCategory.PHOTOS, "Alpha")
            val zulu = repo.createFolder(VaultCategory.PHOTOS, "Zulu")
            val items = repo.hide(listOf(staged("old", 1L), staged("new", 2L)))
            repo.moveToFolder(items.mapTo(mutableSetOf()) { it.id }, zulu.id)
            Seed(repo, alpha.id, zulu.id, items.map { it.id })
        }

    private fun setScreen(vm: CategoryViewModel) {
        compose.setContent {
            CalculatorVaultTheme {
                CategoryScreen(viewModel = vm, onBack = {}, onOpenItem = {}, onHide = {})
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess
        }
    }

    /** Wait for [text] to exist (any count) — popup/menu content composes a frame late. */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Open the selection-bar `⋯` overflow and click [label]. The Material3 DropdownMenu
     * renders into a Popup whose content composes a frame after the anchor click, so the
     * item must be awaited before it can be clicked (a slow emulator loses the race that
     * `performClick`'s implicit `waitForIdle` would otherwise cover).
     */
    private fun clickOverflowItem(label: String) {
        compose.onNodeWithContentDescription("More options").performClick()
        awaitText(label)
        compose.onNodeWithText(label).performClick()
    }

    // ---------------------------------------------------------------------------------
    // 1 · Pin album via the N=1 overflow: badge + two-cluster reorder, state-aware label
    // ---------------------------------------------------------------------------------

    @Test
    fun pinAlbumFromOverflowBadgesAndReordersTheGrid() {
        val (repo, _, zuluId, _) = seed()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)
        setScreen(vm)
        awaitTag("album-tile-$zuluId")

        compose.onNodeWithTag("album-tile-$zuluId").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        clickOverflowItem("Pin album")

        // The badge + reposition IS the confirmation (no snackbar): pinned Zulu now
        // leads the grid despite Name·Ascending, and the index bit is set.
        awaitTag("pin-badge-$zuluId")
        compose.waitUntil(5_000) {
            vm.state.value.folderTiles
                .firstOrNull()
                ?.id == zuluId
        }
        runBlocking {
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == zuluId }
                    .pinned,
            ).isTrue()
        }

        // State-aware overflow: the pinned album offers only "Unpin album".
        compose.onNodeWithTag("album-tile-$zuluId").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("More options").performClick()
        awaitText("Unpin album")
        compose.onNodeWithText("Unpin album").assertExists()
    }

    // ---------------------------------------------------------------------------------
    // 2 · Sort sheet: live application from the header ⇅, persisted in the repository
    // ---------------------------------------------------------------------------------

    @Test
    fun sortSheetAppliesLiveAndPersistsTheAlbumChoice() {
        val (repo, alphaId, zuluId, _) = seed()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)
        setScreen(vm)
        awaitTag("album-tile-$zuluId")

        // Default Name·Ascending → Alpha before Zulu.
        assertThat(
            vm.state.value.folderTiles
                .map { it.id }
        ).containsExactly(alphaId, zuluId).inOrder()

        compose.onNodeWithTag("sort-button").performClick()
        compose.onNodeWithText("Sort by").assertExists()
        // Albums offer exactly the three album keys — never "Date taken" (G-7).
        compose.onNodeWithTag("sort-key-${SortKey.SIZE.name}").assertExists()
        runCatching {
            compose.onNodeWithTag("sort-key-${SortKey.DATE_TAKEN.name}").fetchSemanticsNode()
        }.let { assertThat(it.isFailure).isTrue() }

        // Live apply, no confirm: Size (Zulu holds the bytes) reorders behind the sheet.
        compose.onNodeWithTag("sort-key-${SortKey.SIZE.name}").performClick()
        compose.onNodeWithTag("sort-direction-${SortDirection.DESCENDING.name}").performClick()
        compose.waitUntil(5_000) {
            vm.state.value.folderTiles
                .firstOrNull()
                ?.id == zuluId
        }
        runBlocking {
            assertThat(repo.sortPrefs().first().albumSort)
                .isEqualTo(GridSort(SortKey.SIZE, SortDirection.DESCENDING))
        }
    }

    // ---------------------------------------------------------------------------------
    // 3 · Choose-cover picker: select + confirm writes the pointer; back cancels
    // ---------------------------------------------------------------------------------

    @Test
    fun chooseCoverPickerSetsThePointerAndBackCancels() {
        val (repo, _, zuluId, itemIds) = seed()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)
        setScreen(vm)
        awaitTag("album-tile-$zuluId")

        // Open the picker from the album overflow.
        compose.onNodeWithTag("album-tile-$zuluId").performTouchInput { longClick() }
        clickOverflowItem("Set as cover")
        awaitText("Choose cover")
        compose.onNodeWithText("Choose cover").assertExists()

        // The current (fallback) cover — the newest photo — carries the "Current" chip.
        awaitTag("cover-current-chip")
        compose.onNodeWithTag("cover-current-chip").assertExists()

        // Cancel first: back writes nothing.
        compose.onNodeWithContentDescription("Back").performClick()
        awaitTag("album-tile-$zuluId")
        runBlocking {
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == zuluId }
                    .coverItemId,
            ).isNull()
        }

        // Re-open, pick the OLDER photo, confirm → pointer written, back on the grid.
        compose.onNodeWithTag("album-tile-$zuluId").performTouchInput { longClick() }
        clickOverflowItem("Set as cover")
        val older = itemIds.first()
        awaitTag("cover-tile-$older")
        compose.onNodeWithTag("cover-tile-$older").performClick()
        compose.onNodeWithTag("choose-cover-confirm").performClick()
        compose.waitUntil(5_000) {
            runBlocking {
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == zuluId }
                    .coverItemId == older
            }
        }
        awaitTag("album-tile-$zuluId")
    }

    // ---------------------------------------------------------------------------------
    // 4 · Photo-selection Set as cover (N=1): pointer write + required snackbar
    // ---------------------------------------------------------------------------------

    @Test
    fun photoSelectionSetAsCoverWritesPointerAndShowsSnackbar() {
        val (repo, _, zuluId, itemIds) = seed()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)
        setScreen(vm)
        awaitTag("album-tile-$zuluId")

        compose.onNodeWithTag("album-tile-$zuluId").performClick()
        val older = itemIds.first()
        awaitTag("media-tile-$older")
        compose.onNodeWithTag("media-tile-$older").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()

        clickOverflowItem("Set as cover")

        compose.waitUntil(5_000) {
            runBlocking {
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == zuluId }
                    .coverItemId == older
            }
        }
        // The changed home tile is off-screen, so the snackbar is required (§5).
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Set as album cover.").fetchSemanticsNode() }.isSuccess
        }
    }

    // ---------------------------------------------------------------------------------
    // 5 · Viewer: ⟳ commits the net orientation (debounce) · ⋯ More sets the cover
    // ---------------------------------------------------------------------------------

    @Test
    fun viewerRotateCommitsNetOrientationAndMoreMenuSetsCover() {
        val (base, _, zuluId, itemIds) = seed()
        val older = itemIds.first()
        // Serve a REAL decodable JPEG for the viewer so the page renders an actual image
        // (not the empty-bytes ErrorPage): the rotate button gates on the settled page
        // being decoded, so the render must genuinely land before the tap acts.
        val repo =
            object : VaultContentRepository by base {
                override suspend fun openDecrypted(itemId: String): ByteArray? = DoDTestSupport.sampleJpegBytes()
            }
        val vm = PagerViewerViewModel(older, VaultCategory.PHOTOS, zuluId, repository = repo)
        compose.setContent {
            CalculatorVaultTheme {
                PagerViewerScreen(viewModel = vm, onBack = {})
            }
        }
        // Wait for the decoded image to actually render (the `[ n / total ]` position
        // chrome proves the pager loaded a page and the settled content resolved).
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithContentDescription("Rotate").fetchSemanticsNode() }.isSuccess
        }
        compose.waitUntil(10_000) { vm.activePage.value?.content is PageContent.Bytes }
        compose.waitForIdle()

        // Two taps = net 180°, committed after the 500ms idle debounce (W3-D §8). A
        // waitForIdle between taps lets the collected activePage/rotation state settle so
        // the second tap reads the first tap's result.
        compose.onNodeWithContentDescription("Rotate").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Rotate").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            runBlocking {
                repo
                    .allItems()
                    .first()
                    .first { it.id == older }
                    .rotationDegrees == 180
            }
        }

        // ⋯ More → Set as cover: the photo's own album is implicit (§5). The bottom-bar
        // overflow uses the "More" content description (vs the selection bar's "More
        // options"); the popup item composes a frame late, so await it before clicking.
        compose.onNodeWithContentDescription("More").performClick()
        awaitText("Set as cover")
        compose.onNodeWithText("Set as cover").performClick()
        compose.waitUntil(10_000) {
            runBlocking {
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == zuluId }
                    .coverItemId == older
            }
        }
    }
}
