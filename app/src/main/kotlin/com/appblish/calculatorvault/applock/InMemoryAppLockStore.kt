package com.appblish.calculatorvault.applock

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [AppLockStore] for unit tests and Compose `@Preview`. Same [BaseAppLockStore]
 * behaviour as the encrypted store, backed by a plain map — no Android, no keystore.
 */
class InMemoryAppLockStore : BaseAppLockStore() {
    private val values = ConcurrentHashMap<String, String>()

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
