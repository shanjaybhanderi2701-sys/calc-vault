package com.appblish.calculatorvault.recovery

import android.content.Context
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.storage.VaultStorage
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable, per-[RecoveryMethod] record of **consecutive** wrong-secret attempts and the time
 * of the last one, so the brute-force backoff ([com.appblish.calculatorvault.vault.crypto.RecoveryBackoff])
 * survives process death *and* app uninstall (spec §1.6 — a reinstall must not hand an
 * attacker a fresh unbounded guessing budget). It stores only the counter and a timestamp,
 * never a secret, so the plaintext location is harmless.
 */
interface RecoveryAttemptStore {
    /** Consecutive failed attempts recorded for [method]. */
    fun failedAttempts(method: RecoveryMethod): Int

    /** Wall-clock time (ms) of the most recent failure for [method], or 0 if none. */
    fun lastFailureAtMillis(method: RecoveryMethod): Long

    /** Record one more failure for [method] at [nowMillis]. */
    fun recordFailure(
        method: RecoveryMethod,
        nowMillis: Long,
    )

    /** Clear the failure streak for [method] (a correct secret / successful reset). */
    fun clear(method: RecoveryMethod)
}

/** Non-persistent store for unit tests and previews. */
class InMemoryRecoveryAttemptStore : RecoveryAttemptStore {
    private data class Entry(
        val count: Int,
        val lastAt: Long,
    )

    private val entries = ConcurrentHashMap<RecoveryMethod, Entry>()

    override fun failedAttempts(method: RecoveryMethod): Int = entries[method]?.count ?: 0

    override fun lastFailureAtMillis(method: RecoveryMethod): Long = entries[method]?.lastAt ?: 0L

    override fun recordFailure(
        method: RecoveryMethod,
        nowMillis: Long,
    ) {
        val current = entries[method]?.count ?: 0
        entries[method] = Entry(current + 1, nowMillis)
    }

    override fun clear(method: RecoveryMethod) {
        entries.remove(method)
    }
}

/**
 * File-backed [RecoveryAttemptStore] persisting one line per method as
 * `<methodName>:<count>:<lastFailureMillis>` in a small text file. Pure JVM (a plain [File])
 * so the whole backoff persistence is unit-testable off-device; the device factory
 * ([RecoveryAttemptStores.device]) points it at the survive-uninstall vault directory.
 */
class FileRecoveryAttemptStore(
    private val file: File,
) : RecoveryAttemptStore {
    private fun read(): MutableMap<RecoveryMethod, Pair<Int, Long>> {
        val out = LinkedHashMap<RecoveryMethod, Pair<Int, Long>>()
        if (!file.exists()) return out
        file.readLines().forEach { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size == 3) {
                val method = RecoveryMethod.entries.firstOrNull { it.name == parts[0] }
                val count = parts[1].toIntOrNull()
                val last = parts[2].toLongOrNull()
                if (method != null && count != null && last != null) {
                    out[method] = count to last
                }
            }
        }
        return out
    }

    private fun write(map: Map<RecoveryMethod, Pair<Int, Long>>) {
        file.parentFile?.mkdirs()
        val body = map.entries.joinToString("\n") { (m, v) -> "${m.name}$SEPARATOR${v.first}$SEPARATOR${v.second}" }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            // Fail closed, do NOT fall back to a direct `file.writeText`: that overwrite is
            // non-atomic and an interrupted write can truncate `.recovery_attempts` so it reads
            // back as zero failures and silently resets the survive-uninstall lockout to 0,
            // handing an attacker a fresh unbounded guessing budget (spec §1.6, APP-331 O2).
            // Throw like VaultKeyFile.writeSlots; the ViewModel catches and surfaces the honest
            // error while the counter on disk stays intact.
            tmp.delete()
            throw IOException("Could not atomically replace the recovery attempt file")
        }
    }

    override fun failedAttempts(method: RecoveryMethod): Int = read()[method]?.first ?: 0

    override fun lastFailureAtMillis(method: RecoveryMethod): Long = read()[method]?.second ?: 0L

    override fun recordFailure(
        method: RecoveryMethod,
        nowMillis: Long,
    ) {
        val map = read()
        val current = map[method]?.first ?: 0
        map[method] = (current + 1) to nowMillis
        write(map)
    }

    override fun clear(method: RecoveryMethod) {
        val map = read()
        if (map.remove(method) != null) write(map)
    }

    private companion object {
        const val SEPARATOR = ":"
    }
}

/** Resolves the device-backed attempt store, co-located with the survive-uninstall key file. */
object RecoveryAttemptStores {
    /** A store persisted next to `.vaultkey` in [namespace]'s vault dir (survive-uninstall). */
    fun device(
        context: Context,
        namespace: String = "",
    ): RecoveryAttemptStore =
        FileRecoveryAttemptStore(VaultStorage.blobFile(context.applicationContext, ATTEMPT_FILE_NAME, namespace))

    private const val ATTEMPT_FILE_NAME = ".recovery_attempts"
}
