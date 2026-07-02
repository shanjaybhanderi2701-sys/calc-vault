package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.auth.VaultKind
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
