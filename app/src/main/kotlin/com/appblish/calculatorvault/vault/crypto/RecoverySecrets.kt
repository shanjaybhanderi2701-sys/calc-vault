package com.appblish.calculatorvault.vault.crypto

import java.security.SecureRandom
import java.util.Locale

/**
 * Pure helpers for the two **recovery secrets** (PIN Recovery, spec §1/§2): normalization
 * of the security answer and recovery code before they are fed into PBKDF2 key derivation
 * ([VaultKeyFile]), plus generation of a fresh recovery code.
 *
 * Normalization lives in ONE place on purpose. A secret only unwraps its slot ([VaultKeyFile]
 * Wrap B / Wrap C) if the exact same bytes that were used to *wrap* the DEK are reproduced at
 * *unwrap* time. If setup and recovery normalized differently — even a stray trailing space or
 * a capital letter — the GCM tag would fail and the vault would look permanently lost. So the
 * crypto boundary ([VaultKeyFile.setUpRecovery] / [VaultKeyFile.unlockWithAnswer] /
 * [VaultKeyFile.unlockWithRecoveryCode]) always routes raw user input through these functions;
 * callers never derive keys from raw strings themselves.
 *
 * Pure JVM (no Android APIs) so the whole recovery model is unit-testable off-device, matching
 * [VaultKeyFile] / [VaultCrypto].
 */
object RecoverySecrets {
    /**
     * Normalize a **security answer** for stable Wrap-B derivation (spec §2.1: "answer is
     * normalized (trim/lowercase)"). Trims, lowercases (locale-independent), and collapses any
     * internal whitespace run to a single space, so "  Fluffy  The Cat " and "fluffy the cat"
     * derive the same key. Case- and spacing-insensitive matching is a deliberate usability
     * choice for a secret the user types months later; it costs a little answer entropy, which
     * is why the recovery **code** (high-entropy, generated) is the stronger path.
     */
    fun normalizeAnswer(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(WHITESPACE_RUN, " ")

    /**
     * Normalize a **recovery code** for stable Wrap-C derivation. Uppercases and strips every
     * non-alphanumeric character, so the displayed `7K9F-2XQP-4MRT-8WVN` unwraps whether the
     * user re-enters it with dashes, without dashes, with spaces, or in lower case. Only the
     * separators are dropped — actual alphanumerics are preserved verbatim (never silently
     * removed), so a wrong code stays wrong rather than collapsing toward a match.
     */
    fun normalizeRecoveryCode(raw: String): String =
        raw.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }

    /**
     * Generate a fresh recovery code (spec §2.2): [GROUPS] groups of [GROUP_LEN] characters
     * from an unambiguous alphabet (no `0/O/1/I/L`), dash-separated for readability, e.g.
     * `7K9F-2XQP-4MRT-8WVN`. The raw material is drawn from a CSPRNG. The returned string is
     * the display form; feed it through [normalizeRecoveryCode] before deriving a key.
     */
    fun generateRecoveryCode(random: SecureRandom = SecureRandom()): String {
        val groups =
            (0 until GROUPS).map {
                buildString {
                    repeat(GROUP_LEN) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
                }
            }
        return groups.joinToString("-")
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /** Unambiguous code alphabet: digits 2-9 and A-Z minus the look-alikes 0/O/1/I/L. */
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val GROUPS = 4
    private const val GROUP_LEN = 4
}
