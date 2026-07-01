package com.appblish.calculatorvault.calculator

/**
 * Decides whether the current calculator input is the secret "open the vault"
 * trigger rather than a real sum. This is the single seam between the disguise and
 * the vault, so it is pure and unit-tested: given the displayed input and the
 * configured secret, [isUnlockTrigger] says whether to route to PIN/vault entry.
 *
 * The concrete convention (e.g. "a code ending in '='", a specific operator gesture,
 * or a stored PIN compared after '=') is intentionally NOT locked here — the vault
 * schema and unlock UX are decided against the approved wireframe (APP-142). For the
 * scaffold we implement the simplest defensible rule: an exact match of the typed
 * expression against the configured secret code, evaluated when the user presses '='.
 */
class SecretCodeDetector(
    private val secretProvider: () -> String?,
) {
    /**
     * @param input the raw calculator expression as shown (before evaluation).
     * @return true if [input] equals the configured secret code and a secret is set.
     */
    fun isUnlockTrigger(input: String): Boolean {
        val secret = secretProvider()?.takeIf { it.isNotBlank() } ?: return false
        return input.trim() == secret.trim()
    }
}
