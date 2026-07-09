package com.appblish.calculatorvault.vault.crypto

import java.io.File
import java.io.IOException

/** One secret's brute-force counter: how many consecutive failures and when the last one was. */
data class RecoveryAttempt(
    val failures: Int,
    val lastFailureAtMillis: Long,
) {
    companion object {
        val NONE = RecoveryAttempt(failures = 0, lastFailureAtMillis = 0L)
    }
}

/**
 * Persistence for the recovery-entry backoff counters that [RecoveryBackoff] scores (PIN
 * Recovery spec §1.6). One counter **per method** (security answer / recovery code) so a
 * spree on one path never eats into the other's grace. The store owns only the two numbers;
 * the lockout *policy* stays in the pure [RecoveryBackoff]. Kept behind an interface so the
 * recovery ViewModel is unit-testable against an in-memory fake.
 */
interface RecoveryBackoffStore {
    /** The current counter for [method] (never null — an unseen method reads as [RecoveryAttempt.NONE]). */
    fun read(method: RecoveryMethod): RecoveryAttempt

    /** Bump [method]'s failure count by one and stamp [nowMillis] as its last failure. */
    fun recordFailure(
        method: RecoveryMethod,
        nowMillis: Long,
    )

    /** Forget [method]'s counter — called after a successful unlock so the grace resets. */
    fun clear(method: RecoveryMethod)
}

/**
 * File-backed [RecoveryBackoffStore] living beside the key file in the hidden public
 * `.CalcVault/` folder, so the counters **survive uninstall** exactly like the vault they
 * guard (spec §1.6: a reinstall must not reset the lockout). Line-oriented ASCII, one line
 * per method: `<id>:<failures>:<lastFailureMillis>`. A malformed or missing file reads as
 * "no failures yet" — the backoff is defence in depth, never a lockout that strands the user.
 *
 * Pure `java.io.File`, so the whole read/record/clear cycle is unit-testable off-device.
 */
class FileRecoveryBackoffStore(
    private val file: File,
) : RecoveryBackoffStore {
    override fun read(method: RecoveryMethod): RecoveryAttempt = readAll()[method] ?: RecoveryAttempt.NONE

    override fun recordFailure(
        method: RecoveryMethod,
        nowMillis: Long,
    ) {
        val all = readAll().toMutableMap()
        val current = all[method] ?: RecoveryAttempt.NONE
        all[method] = RecoveryAttempt(current.failures + 1, nowMillis)
        writeAll(all)
    }

    override fun clear(method: RecoveryMethod) {
        val all = readAll().toMutableMap()
        if (all.remove(method) != null) writeAll(all)
    }

    private fun readAll(): Map<RecoveryMethod, RecoveryAttempt> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file
                .readLines()
                .mapNotNull { line ->
                    val parts = line.split(SEPARATOR)
                    if (parts.size != 3) return@mapNotNull null
                    val method = RecoveryMethod.entries.firstOrNull { it.id == parts[0] } ?: return@mapNotNull null
                    val failures = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val last = parts[2].toLongOrNull() ?: return@mapNotNull null
                    method to RecoveryAttempt(failures, last)
                }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(all: Map<RecoveryMethod, RecoveryAttempt>) {
        file.parentFile?.mkdirs()
        val body =
            RecoveryMethod.entries
                .mapNotNull { method ->
                    all[method]?.let { "${method.id}$SEPARATOR${it.failures}$SEPARATOR${it.lastFailureAtMillis}" }
                }.joinToString("\n")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            // Keep the prior counter file intact rather than risk a torn in-place write: a crash mid
            // `writeText` could truncate the file so it reads back empty and silently resets the
            // lockout to zero (APP-331 O2). Fail closed like VaultKeyFile.writeSlots (throw, don't
            // half-write) — the recording caller already treats a write loss as a fail-closed dead-end.
            tmp.delete()
            throw IOException("Could not atomically replace the recovery backoff file")
        }
    }

    private companion object {
        const val SEPARATOR = ":"
    }
}

/** In-memory [RecoveryBackoffStore] for previews / unit tests — never touches disk. */
class InMemoryRecoveryBackoffStore : RecoveryBackoffStore {
    private val counters = mutableMapOf<RecoveryMethod, RecoveryAttempt>()

    override fun read(method: RecoveryMethod): RecoveryAttempt = counters[method] ?: RecoveryAttempt.NONE

    override fun recordFailure(
        method: RecoveryMethod,
        nowMillis: Long,
    ) {
        val current = counters[method] ?: RecoveryAttempt.NONE
        counters[method] = RecoveryAttempt(current.failures + 1, nowMillis)
    }

    override fun clear(method: RecoveryMethod) {
        counters.remove(method)
    }
}
