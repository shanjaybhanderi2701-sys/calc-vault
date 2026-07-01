package com.appblish.calculatorvault.intruder

/**
 * One logged break-in attempt: a wrong-unlock event that crossed the intruder threshold.
 * [appLabel]/[packageName] are the "per-app badge" the log shows (which locked app the
 * intruder tried to open); [photoPath] is the front-camera capture, or null if the camera
 * was unavailable (permission revoked, no front lens) — the attempt is still logged.
 */
data class IntruderEvent(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val timestampMs: Long,
    val photoPath: String?,
)

/**
 * Persistence seam for the Intruder Selfie log. The event index is stored encrypted (it
 * reveals which apps an intruder probed and when); the JPEG captures live in app-private
 * storage referenced by [IntruderEvent.photoPath]. Interface + [BaseIntruderLogStore] split
 * mirrors the AppLock/credential stores so the encrypted and in-memory implementations
 * behave identically.
 */
interface IntruderLogStore {
    /** All logged attempts, newest first. */
    suspend fun events(): List<IntruderEvent>

    /**
     * Log a break-in attempt. [photoBytes] (a front-camera JPEG) is persisted if present;
     * the returned [IntruderEvent] carries the stored [IntruderEvent.photoPath].
     */
    suspend fun record(
        id: String,
        packageName: String,
        appLabel: String,
        timestampMs: Long,
        photoBytes: ByteArray?,
    ): IntruderEvent

    /** Clear the whole log (and its captured photos). */
    suspend fun clear()
}

abstract class BaseIntruderLogStore : IntruderLogStore {
    protected abstract suspend fun getIndex(): String?

    protected abstract suspend fun setIndex(value: String)

    protected abstract suspend fun clearIndex()

    /** Write a JPEG for [id], returning its stored path, or null if storage is unavailable. */
    protected abstract suspend fun persistPhoto(
        id: String,
        bytes: ByteArray,
    ): String?

    /** Delete all persisted photos (called by [clear]). */
    protected abstract suspend fun deleteAllPhotos()

    override suspend fun events(): List<IntruderEvent> =
        getIndex()
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull(::decode)
            ?.sortedByDescending { it.timestampMs }
            ?.toList()
            ?: emptyList()

    override suspend fun record(
        id: String,
        packageName: String,
        appLabel: String,
        timestampMs: Long,
        photoBytes: ByteArray?,
    ): IntruderEvent {
        val path = photoBytes?.let { persistPhoto(id, it) }
        val event = IntruderEvent(id, packageName, appLabel, timestampMs, path)
        val existing = getIndex().orEmpty()
        val prefix = if (existing.isBlank()) "" else existing.trimEnd('\n') + "\n"
        setIndex(prefix + encode(event))
        return event
    }

    override suspend fun clear() {
        clearIndex()
        deleteAllPhotos()
    }

    // One record per line, fields joined by a rare printable delimiter (never present in a
    // package name or sane app label): id | ts | package | label | photoPath. The label is
    // sanitized of the delimiter and newlines so it can never corrupt the line framing.
    private fun encode(e: IntruderEvent): String =
        listOf(
            e.id,
            e.timestampMs.toString(),
            e.packageName,
            sanitize(e.appLabel),
            e.photoPath.orEmpty(),
        ).joinToString(FIELD)

    private fun decode(line: String): IntruderEvent? {
        val parts = line.split(FIELD)
        if (parts.size < 5) return null
        val ts = parts[1].toLongOrNull() ?: return null
        return IntruderEvent(
            id = parts[0],
            timestampMs = ts,
            packageName = parts[2],
            appLabel = parts[3],
            photoPath = parts[4].ifBlank { null },
        )
    }

    private fun sanitize(s: String): String = s.replace(FIELD, " ").replace("\n", " ").replace("\r", " ")

    protected companion object {
        /** Rare printable field delimiter — will not appear in a package name or app label. */
        const val FIELD = "|::|"
    }
}
