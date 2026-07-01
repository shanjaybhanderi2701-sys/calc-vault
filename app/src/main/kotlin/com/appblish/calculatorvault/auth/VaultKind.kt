package com.appblish.calculatorvault.auth

/**
 * Which vault a resolved PIN opens. The disguise calculator resolves a typed code to one
 * of these; a code that matches nothing returns `null` and is treated as ordinary
 * arithmetic.
 *
 * [storageId] is the storage namespace for that vault's hidden items. It is the seam that
 * makes the **Fake Password** feature real: the [Real] vault and every [Decoy] slot get a
 * distinct, non-overlapping id, so a duress PIN can never surface the owner's true
 * content — each fake PIN opens its own separate space.
 */
sealed interface VaultKind {
    val storageId: String

    /** The owner's real vault. */
    data object Real : VaultKind {
        override val storageId: String = "vault_real"
    }

    /** A decoy vault opened by a fake/duress PIN. [slot] is stable per configured decoy. */
    data class Decoy(
        val slot: Int,
    ) : VaultKind {
        override val storageId: String get() = "vault_decoy_$slot"
    }
}
