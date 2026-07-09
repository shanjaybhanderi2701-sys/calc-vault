package com.appblish.calculatorvault.vault.media

import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-299 P0-2 — the on-device gap Round 1 missed: `ChosenFolderTest` proved the docId →
 * RELATIVE_PATH string mapping, but the *write* preferred that reconstructed RELATIVE_PATH
 * over the exact SAF tree the user granted, so on a real device the file never reached the
 * chosen folder. This pins the corrected route order: a chosen folder that carries a tree
 * grant is written through that SAF tree FIRST.
 *
 * Failing-test-first: on `main @ 2e006c6` [writeStrategyOrder] did not exist and
 * [MediaSink.writeBack] tried RELATIVE_PATH before the tree, so the first assertion below
 * fails; after the fix SAF_TREE leads.
 */
class WriteStrategyOrderTest {
    @Test
    fun `chosen folder with a granted tree is served through the SAF tree first`() {
        val chosen =
            UnhideDestination.Chosen(
                relativePath = "Pictures/Vault/",
                treeUri = "content://tree/primary",
                label = "Vault",
            )
        assertThat(writeStrategyOrder(chosen).first()).isEqualTo(WriteStrategy.SAF_TREE)
        // The reconstructed RELATIVE_PATH stays available as a gallery-indexed fallback.
        assertThat(writeStrategyOrder(chosen))
            .containsExactly(
                WriteStrategy.SAF_TREE,
                WriteStrategy.RELATIVE_PATH,
                WriteStrategy.DOWNLOADS,
            ).inOrder()
    }

    @Test
    fun `chosen folder without a tree grant falls back to relative path then downloads`() {
        // A secondary-volume pick whose docId yielded no RELATIVE_PATH and (defensively) no
        // tree: there is nothing to write through the tree, so it is RELATIVE_PATH → Downloads.
        val chosen = UnhideDestination.Chosen(relativePath = null, treeUri = null, label = "SD card")
        assertThat(writeStrategyOrder(chosen))
            .containsExactly(
                WriteStrategy.RELATIVE_PATH,
                WriteStrategy.DOWNLOADS,
            ).inOrder()
    }

    @Test
    fun `original destination keeps the media-store relative path route`() {
        assertThat(writeStrategyOrder(UnhideDestination.Original))
            .containsExactly(
                WriteStrategy.RELATIVE_PATH,
                WriteStrategy.DOWNLOADS,
            ).inOrder()
    }
}
