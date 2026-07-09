package com.appblish.calculatorvault.vault.crypto

import java.security.SecureRandom
import java.util.Locale

/**
 * The two recovery secrets that derive Wrap B and Wrap C (spec §2.1–2.2, APP-322): the
 * **security answer** and the **recovery code**. This holds only the pure transforms —
 * normalization and code generation — so the exact bytes fed to [SecretKeyWrap] are
 * deterministic and unit-testable. No secret is stored here; callers wrap the DEK with the
 * normalized secret and discard it.
 */
object RecoverySecrets {
    /**
     * Normalize a typed security [answer] into the canonical form that derives Wrap B.
     *
     * The user re-types the answer months later on the recovery screen, so the normalization
     * must be forgiving yet **stable across time and devices**: trim the ends, collapse every
     * internal whitespace run to a single space, and lowercase with [Locale.ROOT] (never the
     * device locale — a Turkish-locale `I`→`ı` would otherwise make the same answer derive a
     * different KEK and lock the user out). Casing and stray spacing are the differences we
     * forgive; nothing else is altered.
     */
    fun normalizeAnswer(answer: String): String = answer.trim().replace(WHITESPACE_RUN, " ").lowercase(Locale.ROOT)

    /**
     * Normalize a typed recovery [code] into the canonical form that derives Wrap C. The
     * displayed code is grouped with dashes for readability (`7K9F-2XQP-…`); on entry we drop
     * every non-alphanumeric character (dashes, spaces the user may add) and uppercase, so
     * `7k9f 2xqp` and `7K9F-2XQP` both unwrap.
     */
    fun normalizeCode(code: String): String = code.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

    /**
     * Generate a fresh random recovery code — [GROUPS] groups of [GROUP_LEN] characters from
     * an unambiguous alphabet, joined with dashes (e.g. `7K9F-2XQP-4MRT-8WVN`). Drawn from
     * [random] (a CSPRNG); the alphabet omits easily-confused glyphs (`0/O`, `1/I/L`) so a
     * hand-copied code round-trips. This is what the user saves once at setup (§2.2); only its
     * derived Wrap C is ever persisted.
     *
     * With a 30-character alphabet and 16 characters the code carries ~78 bits of entropy —
     * far beyond brute-force reach, which (with backoff, [RecoveryThrottle]) is why it can be
     * an independent unlock path without weakening the vault.
     */
    fun generateCode(random: SecureRandom = SecureRandom()): String =
        (0 until GROUPS).joinToString("-") {
            buildString {
                repeat(GROUP_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
        }

    /** Unambiguous alphabet: digits 2–9 and A–Z minus the confusable `I`, `L`, `O`. */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val GROUPS = 4
    private const val GROUP_LEN = 4
    private val WHITESPACE_RUN = Regex("\\s+")
}
