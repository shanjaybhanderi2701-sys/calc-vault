package com.appblish.calculatorvault.settings

import java.util.concurrent.ConcurrentHashMap

/**
 * A non-persistent [SettingsStore] for unit tests and Compose previews. Backed by a plain
 * concurrent map; the encode/decode contract is inherited from [BaseSettingsStore] so it
 * exercises exactly the same load/save/backup logic as the encrypted store.
 */
class InMemorySettingsStore : BaseSettingsStore() {
    private val values = ConcurrentHashMap<String, String>()

    override suspend fun getValue(key: String): String? = values[key]

    override suspend fun setValue(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun allValues(): Map<String, String> = values.toMap()

    override suspend fun replaceAll(values: Map<String, String>) {
        this.values.clear()
        this.values.putAll(values)
    }
}
