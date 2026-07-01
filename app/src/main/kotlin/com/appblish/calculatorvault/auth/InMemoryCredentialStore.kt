package com.appblish.calculatorvault.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * A [BaseCredentialStore] backed by a plain in-memory map. Used by unit tests (the whole
 * hashing/decoy/resolution surface runs off-device) and by Compose previews. Seeded state
 * can be passed in so a preview can render the "returning user" case.
 */
class InMemoryCredentialStore(
    initial: Map<String, String> = emptyMap(),
) : BaseCredentialStore() {
    private val values = ConcurrentHashMap<String, String>().apply { putAll(initial) }

    override suspend fun getValue(key: String): String? = values[key]

    override suspend fun setValue(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun removeValue(key: String) {
        values.remove(key)
    }

    override suspend fun clearValues() {
        values.clear()
    }
}
