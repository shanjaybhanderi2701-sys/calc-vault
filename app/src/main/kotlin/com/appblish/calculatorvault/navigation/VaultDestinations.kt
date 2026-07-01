package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * Route table for the whole app. The disguise boundary (calculator → vault) stays
 * shallow; the calculator is itself the PIN gate, so there is no separate PIN route.
 * Inside the vault the shell hosts the tabs, and category / hide / viewer / slideshow /
 * recycle-bin are pushed on top. Category is carried as an enum name arg.
 */
internal object VaultDestinations {
    const val CALCULATOR = "calculator"

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
}
