package com.appblish.calculatorvault.ui.theme

/**
 * App-wide light/dark selection (APP-525, spec §1.1). Persisted through the settings store
 * and applied by [CalculatorVaultTheme] the instant it changes.
 *
 * The default is [DARK]: on first run the vault is dark so the calculator disguise and the
 * vault render on the same near-black canvas (the long-standing dark-first identity), but the
 * user can now opt into [LIGHT] or follow the OS with [SYSTEM].
 */
enum class ThemeMode(
    val displayName: String,
) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System default"),
    ;

    companion object {
        /** First-run default per spec §1.1 — the app is dark until the user changes it. */
        val DEFAULT = DARK

        fun fromNameOrNull(name: String?): ThemeMode? = entries.firstOrNull { it.name == name }
    }
}
