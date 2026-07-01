package com.appblish.calculatorvault.onboarding

/** The default "follow the system" language row shown selected on first run. */
const val DEFAULT_LANGUAGE: String = "Default"

/**
 * The App Language list from the board's onboarding frame. "Default" (system) is first
 * and pre-selected; the rest are the languages rendered in the deck. Phase 1 only records
 * the choice — actual string localisation lands with the wider i18n work.
 */
val SUPPORTED_LANGUAGES: List<String> =
    listOf(
        DEFAULT_LANGUAGE,
        "English",
        "Portuguese",
        "Spanish",
        "French",
        "Russian",
        "Arabic",
        "Hindi",
        "Japanese",
        "Indonesian",
        "Persian",
    )
