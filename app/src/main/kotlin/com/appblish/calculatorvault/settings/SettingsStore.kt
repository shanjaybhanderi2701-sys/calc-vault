package com.appblish.calculatorvault.settings

/**
 * Persistence seam for the non-secret [VaultSettings]. Mirrors the shape of
 * [com.appblish.calculatorvault.auth.CredentialStore]: concrete stores only implement raw
 * string persistence, and all encode/decode lives in [BaseSettingsStore] so the encrypted
 * on-device store and the in-memory test store behave identically.
 *
 * Settings are stored in the *same* encrypted preferences family as credentials so the
 * hardening switches (prevent-uninstall, disguise) cannot be read or flipped by another
 * app inspecting plaintext prefs.
 */
interface SettingsStore {
    /** Read the current settings snapshot, falling back to deck defaults for unset keys. */
    suspend fun load(): VaultSettings

    /** Persist [settings] wholesale. */
    suspend fun save(settings: VaultSettings)

    /** Restore raw key/value pairs (used by backup restore). Unknown keys are ignored on load. */
    suspend fun exportRaw(): Map<String, String>

    /** Replace all settings values from a restored map. */
    suspend fun importRaw(values: Map<String, String>)
}

/** Shared encode/decode on top of three tiny primitives. */
abstract class BaseSettingsStore : SettingsStore {
    protected abstract suspend fun getValue(key: String): String?

    protected abstract suspend fun setValue(
        key: String,
        value: String,
    )

    protected abstract suspend fun allValues(): Map<String, String>

    protected abstract suspend fun replaceAll(values: Map<String, String>)

    override suspend fun load(): VaultSettings =
        VaultSettings(
            keypadSkin = KeypadSkin.fromNameOrNull(getValue(KEY_SKIN)) ?: KeypadSkin.DEFAULT,
            unlockAnimation = UnlockAnimation.fromNameOrNull(getValue(KEY_ANIM)) ?: UnlockAnimation.DEFAULT,
            breakInAlertsEnabled = getBool(KEY_BREAKIN, default = true),
            fakePasswordEnabled = getBool(KEY_FAKE_PW, default = true),
            preventUninstallEnabled = getBool(KEY_PREVENT_UNINSTALL, default = false),
            disguiseIconEnabled = getBool(KEY_DISGUISE, default = false),
            relockOnBackgroundEnabled = getBool(KEY_RELOCK, default = true),
            shufflePinPadEnabled = getBool(KEY_SHUFFLE, default = false),
            incorrectVibrationEnabled = getBool(KEY_VIBRATION, default = true),
            hideFromRecentsEnabled = getBool(KEY_HIDE_FROM_RECENTS, default = false),
            appLanguage = getValue(KEY_LANGUAGE) ?: "Default",
        )

    override suspend fun save(settings: VaultSettings) {
        setValue(KEY_SKIN, settings.keypadSkin.name)
        setValue(KEY_ANIM, settings.unlockAnimation.name)
        setValue(KEY_BREAKIN, settings.breakInAlertsEnabled.toString())
        setValue(KEY_FAKE_PW, settings.fakePasswordEnabled.toString())
        setValue(KEY_PREVENT_UNINSTALL, settings.preventUninstallEnabled.toString())
        setValue(KEY_DISGUISE, settings.disguiseIconEnabled.toString())
        setValue(KEY_RELOCK, settings.relockOnBackgroundEnabled.toString())
        setValue(KEY_SHUFFLE, settings.shufflePinPadEnabled.toString())
        setValue(KEY_VIBRATION, settings.incorrectVibrationEnabled.toString())
        setValue(KEY_HIDE_FROM_RECENTS, settings.hideFromRecentsEnabled.toString())
        setValue(KEY_LANGUAGE, settings.appLanguage)
    }

    override suspend fun exportRaw(): Map<String, String> = allValues()

    override suspend fun importRaw(values: Map<String, String>) = replaceAll(values.filterKeys { it in KNOWN_KEYS })

    private suspend fun getBool(
        key: String,
        default: Boolean,
    ): Boolean = getValue(key)?.toBooleanStrictOrNull() ?: default

    private companion object {
        const val KEY_SKIN = "keypad_skin"
        const val KEY_ANIM = "unlock_animation"
        const val KEY_BREAKIN = "breakin_alerts"
        const val KEY_FAKE_PW = "fake_password"
        const val KEY_PREVENT_UNINSTALL = "prevent_uninstall"
        const val KEY_DISGUISE = "disguise_icon"
        const val KEY_RELOCK = "relock_on_background"
        const val KEY_SHUFFLE = "shuffle_pin_pad"
        const val KEY_VIBRATION = "incorrect_vibration"
        const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
        const val KEY_LANGUAGE = "app_language"

        val KNOWN_KEYS =
            setOf(
                KEY_SKIN,
                KEY_ANIM,
                KEY_BREAKIN,
                KEY_FAKE_PW,
                KEY_PREVENT_UNINSTALL,
                KEY_DISGUISE,
                KEY_RELOCK,
                KEY_SHUFFLE,
                KEY_VIBRATION,
                KEY_HIDE_FROM_RECENTS,
                KEY_LANGUAGE,
            )
    }
}
