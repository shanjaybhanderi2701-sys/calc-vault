package com.appblish.calculatorvault.vault.crypto

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * The vault's **recovery wraps** — "Wrap B ← security answer" and "Wrap C ← recovery code"
 * (spec §1.2, APP-322) — persisted in `.CalcVault/.vaultrecovery`, next to the PIN's
 * `.vaultkey`, so they survive uninstall the same way.
 *
 * This is the sibling of [VaultKeyFile]. Both wrap the **same immutable DEK** with the shared
 * [SecretKeyWrap] primitive; they differ only in which secret derives the KEK. That is the
 * whole point of the envelope model:
 *
 * - **Any one secret unwraps the same DEK.** PIN → [VaultKeyFile]; security answer or recovery
 *   code → here. All three seal the identical DEK bytes, so recovery is `unwrap + re-wrap`,
 *   never a bulk re-encrypt (§1.3): a recovery path unwraps the DEK here, then [VaultKeyFile.
 *   writePinWrap] re-creates Wrap A under a new PIN — the files are never touched.
 * - **A PIN change cannot invalidate recovery.** [VaultKeyFile.rewrap] only rewrites
 *   `.vaultkey`; this file is left alone and still wraps the same unchanged DEK (§7 DoD).
 * - **No plaintext secret is ever stored.** Only PBKDF2/GCM wrap output lands on disk; the
 *   normalized answer and the recovery code are used to derive a KEK and then discarded.
 * - **No master backdoor** (§1.5): there is no fourth wrap, no escrow. Lose the PIN, the
 *   answer, and the code, and the DEK is unrecoverable by construction.
 *
 * **On-disk format** (ASCII, one line each): a `version` line, then up to one line per slot
 * `SLOT:iterations:saltHex:ivHex:wrappedHex`. Slots are independent — the security answer and
 * the recovery code can be set (or later regenerated) in any order, and setting one preserves
 * the other. Writes are atomic (temp + rename) so a crash never leaves a half-written wrap.
 */
class RecoveryEnvelope(
    private val file: File,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The two recovery slots. Persisted by [Slot.name] so re-ordering never re-maps a wrap. */
    enum class Slot {
        SECURITY_ANSWER,
        RECOVERY_CODE,
    }

    /** Thrown by the unlock paths when the requested recovery slot was never configured. */
    class SlotNotConfiguredException(
        slot: Slot,
    ) : GeneralSecurityException("Recovery slot not configured: $slot")

    /** True if any recovery wrap exists — i.e. the user has set up at least one recovery path. */
    fun exists(): Boolean = file.exists() && read().isNotEmpty()

    /** True if the security-answer wrap (Wrap B) is configured. */
    fun hasSecurityAnswer(): Boolean = has(Slot.SECURITY_ANSWER)

    /** True if the recovery-code wrap (Wrap C) is configured. */
    fun hasRecoveryCode(): Boolean = has(Slot.RECOVERY_CODE)

    /**
     * Recovery is "set up" (§4 banner disappears) once **both** paths exist: the setup flow
     * configures the security question and the recovery code together, and the DoD requires
     * both independent paths. A vault with only one is still mid-setup.
     */
    fun isFullyConfigured(): Boolean = hasSecurityAnswer() && hasRecoveryCode()

    /**
     * Wrap [dek] under the normalized security [answer] and persist it as Wrap B, preserving
     * any existing recovery-code wrap. Re-calling replaces Wrap B (used when the user changes
     * their security question/answer, §6b).
     */
    fun setSecurityAnswer(
        dek: SecretKey,
        answer: String,
    ) = putSlot(Slot.SECURITY_ANSWER, dek, RecoverySecrets.normalizeAnswer(answer))

    /**
     * Wrap [dek] under the normalized recovery [code] and persist it as Wrap C, preserving any
     * existing security-answer wrap. Re-calling replaces Wrap C (used when the user regenerates
     * the recovery code, §6b).
     */
    fun setRecoveryCode(
        dek: SecretKey,
        code: String,
    ) = putSlot(Slot.RECOVERY_CODE, dek, RecoverySecrets.normalizeCode(code))

    /**
     * Unwrap the DEK from Wrap B using the typed security [answer]. Throws
     * [SecretKeyWrap.WrongSecretException] on a wrong answer and [SlotNotConfiguredException]
     * if the security answer was never set up.
     */
    fun unlockWithSecurityAnswer(answer: String): SecretKey =
        unlock(Slot.SECURITY_ANSWER, RecoverySecrets.normalizeAnswer(answer))

    /**
     * Unwrap the DEK from Wrap C using the typed recovery [code]. Throws
     * [SecretKeyWrap.WrongSecretException] on a wrong code and [SlotNotConfiguredException]
     * if no recovery code was configured.
     */
    fun unlockWithRecoveryCode(code: String): SecretKey =
        unlock(Slot.RECOVERY_CODE, RecoverySecrets.normalizeCode(code))

    private fun has(slot: Slot): Boolean = file.exists() && read().containsKey(slot)

    private fun unlock(
        slot: Slot,
        normalizedSecret: String,
    ): SecretKey {
        val payload = read()[slot] ?: throw SlotNotConfiguredException(slot)
        return SecretKeyWrap.unwrap(payload, normalizedSecret)
    }

    private fun putSlot(
        slot: Slot,
        dek: SecretKey,
        normalizedSecret: String,
    ) {
        val slots = if (file.exists()) read().toMutableMap() else mutableMapOf()
        slots[slot] = SecretKeyWrap.wrap(dek, normalizedSecret, random)
        write(slots)
    }

    /** Parse the file into a slot→payload map, ignoring unknown slot labels and blank lines. */
    private fun read(): Map<Slot, String> {
        val lines =
            file
                .readText()
                .trim()
                .split("\n")
                .map(String::trim)
                .filter(String::isNotEmpty)
        require(lines.isNotEmpty() && lines[0] == VERSION.toString()) { "Malformed recovery envelope" }
        val out = linkedMapOf<Slot, String>()
        for (line in lines.drop(1)) {
            val label = line.substringBefore(SEPARATOR)
            val payload = line.substringAfter(SEPARATOR)
            val slot = Slot.entries.firstOrNull { it.name == label } ?: continue
            out[slot] = payload
        }
        return out
    }

    /** Atomically write [slots] as the versioned file (temp + rename); no half-written wrap. */
    private fun write(slots: Map<Slot, String>) {
        file.parentFile?.mkdirs()
        val body =
            buildString {
                append(VERSION)
                for (slot in Slot.entries) {
                    val payload = slots[slot] ?: continue
                    append('\n').append(slot.name).append(SEPARATOR).append(payload)
                }
            }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not atomically replace the recovery envelope file")
        }
    }

    private companion object {
        const val VERSION = 1

        /** Separates a slot label from its [SecretKeyWrap] payload; the payload has no `:` at
         * position 0, and [String.substringAfter] keeps the payload's own internal colons. */
        const val SEPARATOR = ":"
    }
}
