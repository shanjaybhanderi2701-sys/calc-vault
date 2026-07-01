package com.appblish.calculatorvault.auth

/**
 * The security questions offered on the onboarding "Set security question" modal and in
 * Settings. The default matches the board's deck ("What is your mobile No.?"); the rest
 * are the usual recovery prompts. Stored by [SecurityQuestion.name] so re-ordering the
 * enum never re-maps a saved answer.
 */
enum class SecurityQuestion(
    val prompt: String,
) {
    MOBILE_NUMBER("What is your mobile No.?"),
    FIRST_SCHOOL("What was the name of your first school?"),
    PET_NAME("What was your first pet's name?"),
    BIRTH_CITY("In which city were you born?"),
    MOTHERS_MAIDEN("What is your mother's maiden name?"),
    ;

    companion object {
        val DEFAULT = MOBILE_NUMBER

        fun fromNameOrNull(name: String?): SecurityQuestion? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Everything captured on the recovery step. [answer] is the plaintext the user typed; the
 * store hashes it and never persists it in the clear. [recoveryEmail] and [hint] are
 * stored as-is so they can be shown back on the forgot-password flow.
 */
data class RecoverySetup(
    val question: SecurityQuestion,
    val answer: String,
    val recoveryEmail: String,
    val hint: String,
)

/**
 * The recoverable, non-secret parts of a [RecoverySetup] — safe to read back and display
 * on the forgot-password screen. The answer is deliberately absent: it is verify-only.
 */
data class RecoveryInfo(
    val question: SecurityQuestion,
    val recoveryEmail: String,
    val hint: String,
)
