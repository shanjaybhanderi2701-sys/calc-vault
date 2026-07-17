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

    /** Settings → Appearance: theme mode + accent color grid (APP-525 §1). */
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_CHANGE_PIN = "settings/change_pin"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_LANGUAGE = "settings/language"
    const val SETTINGS_PIN_RECOVERY = "settings/pin_recovery"

    // --- PIN Recovery (APP-321) ---

    /** The recovery setup flow (W2, W0 02–06): security answer + recovery code → Wrap B/C. */
    const val RECOVERY_SETUP = "recovery/setup"

    /** The recovery-entry landing (W2, W0 08) both doorways open — opens only, resets nothing. */
    const val RECOVERY_ENTRY = "recovery/entry"

    const val ARG_RECOVERY_METHOD = "method"

    /**
     * The recovery-unlock seam (W0 09/10 → 11). W2 registers a placeholder here; W3 replaces
     * it with the real unlock + set-new-PIN flow (unwrap via Wrap B/C, then re-wrap Wrap A).
     * [method] is `answer` or `code`.
     */
    const val RECOVERY_UNLOCK = "recovery/unlock/{$ARG_RECOVERY_METHOD}"

    fun recoveryUnlock(method: String) = "recovery/unlock/$method"

    // --- Vault spine ---

    /** Post-auth landing: the vault home dashboard (root — no shell/tabs in Phase 1). */
    const val VAULT_HOME = "vault_home"

    /** Simple name-filter search over hidden items (docx home header, spec §3). */
    const val SEARCH = "search"

    const val ARG_CATEGORY = "category"
    const val ARG_ITEM_ID = "itemId"
    const val ARG_FOLDER_ID = "folderId"

    private const val CATEGORY_BASE = "category"
    private const val HIDE_BASE = "hide"
    private const val VIEWER_BASE = "viewer"
    private const val SLIDESHOW_BASE = "slideshow"

    const val CATEGORY = "$CATEGORY_BASE/{$ARG_CATEGORY}"
    const val HIDE = "$HIDE_BASE/{$ARG_CATEGORY}?$ARG_FOLDER_ID={$ARG_FOLDER_ID}"
    const val VIEWER = "$VIEWER_BASE/{$ARG_CATEGORY}/{$ARG_ITEM_ID}?$ARG_FOLDER_ID={$ARG_FOLDER_ID}"
    const val SLIDESHOW = "$SLIDESHOW_BASE/{$ARG_CATEGORY}"
    const val RECYCLE_BIN = "recyclebin"

    fun category(category: VaultCategory) = "$CATEGORY_BASE/${category.name}"

    /**
     * The hide/import flow for [category]. [destinationFolderId] is the **launch context**
     * (APP-299 P1-3): when the flow is opened from inside an open vault album, every picked
     * item lands flat in that album regardless of its source device bucket; null (launched
     * from vault home / the album grid) keeps the S16 source-bucket folder mapping.
     */
    fun hide(
        category: VaultCategory,
        destinationFolderId: String? = null,
    ) = "$HIDE_BASE/${category.name}" +
        (destinationFolderId?.let { "?$ARG_FOLDER_ID=$it" } ?: "")

    /**
     * Pager viewer over one grid's page set (APP-235 P0): the [category]'s items filtered
     * to [folderId] — null = the category root ("Recent" pseudo-folder). The tapped
     * [itemId] is the start page.
     */
    fun viewer(
        itemId: String,
        category: VaultCategory,
        folderId: String? = null,
    ) = "$VIEWER_BASE/${category.name}/$itemId" +
        (folderId?.let { "?$ARG_FOLDER_ID=$it" } ?: "")

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
