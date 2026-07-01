package com.appblish.calculatorvault.auth

/**
 * The vault's authentication seam: it owns the real PIN, any decoy PINs, the recovery
 * material, and the onboarding flag. It never exposes a stored secret — callers can only
 * *set*, *verify*, or *resolve* against them.
 *
 * The single most important method is [resolve]: given a code typed on the disguise
 * calculator it returns which vault to open ([VaultKind.Real], a [VaultKind.Decoy], or
 * `null` for "not a vault code, treat as arithmetic"). All methods are `suspend` so an
 * implementation can hash off the main thread and back onto encrypted storage.
 */
interface CredentialStore {
    /** True once onboarding has completed (real PIN set + recovery captured). */
    suspend fun isOnboarded(): Boolean

    /** Mark onboarding finished. Call after the real PIN and recovery have been saved. */
    suspend fun completeOnboarding()

    /** Set (or replace) the owner's real PIN. */
    suspend fun setRealPin(pin: String)

    /**
     * Resolve a typed [pin] to the vault it opens, or `null` if it matches nothing. The
     * real PIN wins over decoys, and a decoy that collides with the real PIN is never
     * reachable (see [addDecoyPin]).
     */
    suspend fun resolve(pin: String): VaultKind?

    /**
     * Register a decoy PIN and return its stable [VaultKind.Decoy.slot]. Rejects a [pin]
     * that already resolves to the real vault or an existing decoy (returns `null`) so two
     * spaces can never share a code.
     */
    suspend fun addDecoyPin(pin: String): Int?

    /** The slots of all configured decoy PINs, ascending. */
    suspend fun decoySlots(): List<Int>

    /** Remove the decoy PIN at [slot] (its decoy vault content is orphaned, not this layer's concern). */
    suspend fun removeDecoy(slot: Int)

    /** Persist the recovery material, hashing the security answer. */
    suspend fun setRecovery(setup: RecoverySetup)

    /** The non-secret recovery material to show on forgot-password, or `null` if unset. */
    suspend fun recoveryInfo(): RecoveryInfo?

    /** Verify a typed security [answer] against the stored hash (case/space-insensitive). */
    suspend fun verifyRecoveryAnswer(answer: String): Boolean

    /** Wipe every credential and flag — used by a full reset / decoy self-destruct. */
    suspend fun clearAll()

    /**
     * The raw stored key/value pairs (already-hashed PIN tokens, decoy list, recovery
     * material). Consumed only by the encrypted backup
     * ([com.appblish.calculatorvault.settings.BackupManager]); the values are opaque
     * hashed tokens, never plaintext PINs.
     */
    suspend fun exportRaw(): Map<String, String>

    /** Replace all credential state from a restored [values] map (backup restore). */
    suspend fun importRaw(values: Map<String, String>)
}

/**
 * Shared [CredentialStore] behaviour on top of three tiny primitives ([getValue],
 * [setValue], [removeValue]). Concrete stores only implement raw string persistence; all
 * hashing, decoy bookkeeping, and resolution live here so the logic is identical between
 * the encrypted on-device store and the in-memory store used by unit tests.
 */
abstract class BaseCredentialStore : CredentialStore {
    protected abstract suspend fun getValue(key: String): String?

    protected abstract suspend fun setValue(
        key: String,
        value: String,
    )

    protected abstract suspend fun removeValue(key: String)

    protected abstract suspend fun clearValues()

    override suspend fun isOnboarded(): Boolean = getValue(KEY_ONBOARDED) == "true"

    override suspend fun completeOnboarding() = setValue(KEY_ONBOARDED, "true")

    override suspend fun setRealPin(pin: String) = setValue(KEY_REAL_PIN, PinHasher.hash(pin))

    override suspend fun resolve(pin: String): VaultKind? {
        val realToken = getValue(KEY_REAL_PIN)
        if (realToken != null && PinHasher.verify(pin, realToken)) return VaultKind.Real
        for (slot in decoySlots()) {
            val token = getValue(decoyKey(slot)) ?: continue
            if (PinHasher.verify(pin, token)) return VaultKind.Decoy(slot)
        }
        return null
    }

    override suspend fun addDecoyPin(pin: String): Int? {
        // A decoy must be unique across the real PIN and all existing decoys.
        if (resolve(pin) != null) return null
        val slots = decoySlots()
        val slot = (slots.maxOrNull() ?: -1) + 1
        setValue(decoyKey(slot), PinHasher.hash(pin))
        setValue(KEY_DECOY_SLOTS, (slots + slot).joinToString(","))
        return slot
    }

    override suspend fun decoySlots(): List<Int> =
        getValue(KEY_DECOY_SLOTS)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.sorted()
            ?: emptyList()

    override suspend fun removeDecoy(slot: Int) {
        removeValue(decoyKey(slot))
        val remaining = decoySlots().filter { it != slot }
        if (remaining.isEmpty()) {
            removeValue(KEY_DECOY_SLOTS)
        } else {
            setValue(KEY_DECOY_SLOTS, remaining.joinToString(","))
        }
    }

    override suspend fun setRecovery(setup: RecoverySetup) {
        setValue(KEY_REC_QUESTION, setup.question.name)
        setValue(KEY_REC_ANSWER, PinHasher.hash(normalizeAnswer(setup.answer)))
        setValue(KEY_REC_EMAIL, setup.recoveryEmail)
        setValue(KEY_REC_HINT, setup.hint)
    }

    override suspend fun recoveryInfo(): RecoveryInfo? {
        val question = SecurityQuestion.fromNameOrNull(getValue(KEY_REC_QUESTION)) ?: return null
        return RecoveryInfo(
            question = question,
            recoveryEmail = getValue(KEY_REC_EMAIL).orEmpty(),
            hint = getValue(KEY_REC_HINT).orEmpty(),
        )
    }

    override suspend fun verifyRecoveryAnswer(answer: String): Boolean {
        val token = getValue(KEY_REC_ANSWER) ?: return false
        return PinHasher.verify(normalizeAnswer(answer), token)
    }

    override suspend fun clearAll() = clearValues()

    override suspend fun exportRaw(): Map<String, String> {
        val keys =
            mutableListOf(
                KEY_ONBOARDED,
                KEY_REAL_PIN,
                KEY_DECOY_SLOTS,
                KEY_REC_QUESTION,
                KEY_REC_ANSWER,
                KEY_REC_EMAIL,
                KEY_REC_HINT,
            )
        decoySlots().forEach { keys += decoyKey(it) }
        return keys.mapNotNull { key -> getValue(key)?.let { key to it } }.toMap()
    }

    override suspend fun importRaw(values: Map<String, String>) {
        clearValues()
        values.forEach { (key, value) -> setValue(key, value) }
    }

    private fun normalizeAnswer(answer: String): String = answer.trim().lowercase()

    private fun decoyKey(slot: Int): String = "$KEY_DECOY_PIN_PREFIX$slot"

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_REAL_PIN = "real_pin"
        const val KEY_DECOY_SLOTS = "decoy_slots"
        const val KEY_DECOY_PIN_PREFIX = "decoy_pin_"
        const val KEY_REC_QUESTION = "recovery_question"
        const val KEY_REC_ANSWER = "recovery_answer"
        const val KEY_REC_EMAIL = "recovery_email"
        const val KEY_REC_HINT = "recovery_hint"
    }
}
