package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.explore.ExploreTool
import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * Route table for the whole app, kept in one place so the disguise boundary is easy to
 * audit. Flow (Phase 6 unified spine):
 *
 *   gate → (first run) onboarding → calculator  |  (returning) calculator
 *   calculator → [resolved code] → storage_primer? → vault_shell
 *   calculator → [long-press] → forgot_password
 *   vault_shell → category / hide / viewer / slideshow / recyclebin
 *
 * The PIN is entered on the calculator itself (stronger disguise), so there is no separate
 * PIN screen. The post-unlock landing is the Phase-2 vault shell, not a stub. Category is
 * carried as an enum name arg.
 */
internal object VaultDestinations {
    // --- Disguise / auth spine (Phase 1) ---
    const val GATE = "gate"
    const val ONBOARDING = "onboarding"
    const val CALCULATOR = "calculator"
    const val FORGOT_PASSWORD = "forgot_password"
    const val FAKE_PASSWORD = "fake_password"

    // --- Settings (Phase 5) ---
    const val SETTINGS = "settings"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_CHANGE_PIN = "settings/change_pin"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_BACKUP = "settings/backup"

    // --- Vault spine (Phase 2) ---

    /** Point-of-need All Files Access primer, gating entry to the public-storage vault. */
    const val STORAGE_PRIMER = "storage_primer"

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

    /**
     * Storage namespace for the vault a code opened. The real vault uses the root
     * `.CalcVault/` layout (blank id) so it stays byte-compatible with the approved
     * survive-uninstall gate (APP-169); each decoy gets its own isolated subdirectory so a
     * decoy passphrase can never read real content. See [VaultSession].
     */
    fun storageId(kind: VaultKind): String =
        when (kind) {
            VaultKind.Real -> ""
            is VaultKind.Decoy -> "decoy_${kind.slot}"
        }
}
