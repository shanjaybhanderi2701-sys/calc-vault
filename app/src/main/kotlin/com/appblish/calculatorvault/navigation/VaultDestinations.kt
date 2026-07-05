package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * Route table for the whole app, kept in one place so the disguise boundary is easy to
 * audit. Flow (Phase 1 re-scope, APP-225):
 *
 *   gate → (first run) onboarding → calculator  |  (returning) calculator
 *   calculator → [resolved code] → vault_home
 *   vault_home → category / hide / viewer / slideshow / recyclebin / search / settings
 *
 * The PIN is entered on the calculator itself (stronger disguise), so there is no separate
 * PIN screen. The post-unlock landing is the vault home directly — no bottom-nav shell
 * (design call D-1 on APP-224: App Lock and Explore are hidden entirely this phase, not
 * teased). All Files Access is primed contextually by a bottom sheet on the first
 * content-surface tap inside the vault (spec §5), so there is no full-screen primer route.
 * There is no forgot-password route: recovery is out of Phase 1 (spec §0). Category is
 * carried as an enum name arg.
 */
internal object VaultDestinations {
    // --- Disguise / auth spine ---
    const val GATE = "gate"
    const val ONBOARDING = "onboarding"
    const val CALCULATOR = "calculator"

    // --- Settings (minimal Phase-1 set, S22) ---
    const val SETTINGS = "settings"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_CHANGE_PIN = "settings/change_pin"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_LANGUAGE = "settings/language"

    // --- Vault spine ---

    /** Post-auth landing: the vault home dashboard (root — no shell/tabs in Phase 1). */
    const val VAULT_HOME = "vault_home"

    /** Simple name-filter search over hidden items (docx home header, spec §3). */
    const val SEARCH = "search"

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

    fun category(category: VaultCategory) = "$CATEGORY_BASE/${category.name}"

    fun hide(category: VaultCategory) = "$HIDE_BASE/${category.name}"

    fun viewer(itemId: String) = "$VIEWER_BASE/$itemId"

    fun slideshow(category: VaultCategory) = "$SLIDESHOW_BASE/${category.name}"

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
