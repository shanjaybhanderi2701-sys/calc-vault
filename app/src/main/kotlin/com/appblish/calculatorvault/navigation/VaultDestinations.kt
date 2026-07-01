package com.appblish.calculatorvault.navigation

/** Route table for the calculator -> PIN -> vault flow. Kept in one place so the
 *  disguise boundary is easy to audit. */
internal object VaultDestinations {
    const val CALCULATOR = "calculator"
    const val PIN = "pin"
    const val VAULT_HOME = "vault_home"
}
