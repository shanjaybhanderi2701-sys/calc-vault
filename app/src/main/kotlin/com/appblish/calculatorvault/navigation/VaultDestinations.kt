package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.explore.ExploreTool
import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * Route table for the whole app. The disguise boundary (calculator → PIN → vault) stays
 * shallow; inside the vault the shell hosts the tabs, and category / hide / viewer /
 * slideshow / recycle-bin are pushed on top. Category is carried as an enum name arg.
 */
internal object VaultDestinations {
    const val CALCULATOR = "calculator"
    const val PIN = "pin"

    /** Post-auth landing: the Vault/AppLock/Explore shell. */
    const val VAULT_SHELL = "vault_shell"

    const val ARG_CATEGORY = "category"
    const val ARG_ITEM_ID = "itemId"

    private const val CATEGORY_BASE = "category"
    private const val HIDE_BASE = "hide"
    private const val VIEWER_BASE = "viewer"
    private const val SLIDESHOW_BASE = "slideshow"

    const val CATEGORY = "$CATEGORY_BASE/{$ARG_CATEGORY}"
    const val HIDE = "$HIDE_BASE/{$ARG_CATEGORY}"
    const val VIEWER = "$VIEWER_BASE/{$ARG_ITEM_ID}"
    const val SLIDESHOW = "$SLIDESHOW_BASE/{$ARG_CATEGORY}"
    const val RECYCLE_BIN = "recyclebin"

    // --- Explore / Quick Tools (Phase 4) ----------------------------------
    const val ARG_NOTE_ID = "noteId"

    /** Sentinel note id for the editor route meaning "create a fresh note". */
    const val NEW_NOTE = "new"

    const val EXPLORE_JUNK = "explore/junk"
    const val EXPLORE_BROWSER = "explore/browser"
    const val EXPLORE_BLOCKER = "explore/blocker"
    const val EXPLORE_NOTES = "explore/notes"
    const val EXPLORE_NOTIFICATION = "explore/notification"
    const val EXPLORE_FAKE_PASSWORD = "explore/fakepassword"

    private const val NOTE_EDITOR_BASE = "explore/note"
    const val NOTE_EDITOR = "$NOTE_EDITOR_BASE/{$ARG_NOTE_ID}"

    fun category(category: VaultCategory) = "$CATEGORY_BASE/${category.name}"

    fun hide(category: VaultCategory) = "$HIDE_BASE/${category.name}"

    fun viewer(itemId: String) = "$VIEWER_BASE/$itemId"

    fun slideshow(category: VaultCategory) = "$SLIDESHOW_BASE/${category.name}"

    fun noteEditor(noteId: String) = "$NOTE_EDITOR_BASE/$noteId"

    /** The top-level route for a tapped Explore tool. Notes lands on its list. */
    fun exploreRoute(tool: ExploreTool): String =
        when (tool) {
            ExploreTool.JunkCleaner -> EXPLORE_JUNK
            ExploreTool.PrivateBrowser -> EXPLORE_BROWSER
            ExploreTool.WebsiteBlocker -> EXPLORE_BLOCKER
            ExploreTool.Notes -> EXPLORE_NOTES
            ExploreTool.HideNotification -> EXPLORE_NOTIFICATION
            ExploreTool.FakePassword -> EXPLORE_FAKE_PASSWORD
        }
}
