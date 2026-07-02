package com.appblish.calculatorvault.intruder

/**
 * In-memory [IntruderLogStore] for unit tests and previews. Photos are not written to disk;
 * [persistPhoto] returns a synthetic path so the index round-trips a non-null [photoPath].
 */
class InMemoryIntruderLogStore : BaseIntruderLogStore() {
    private var index: String? = null

    override suspend fun getIndex(): String? = index

    override suspend fun setIndex(value: String) {
        index = value
    }

    override suspend fun clearIndex() {
        index = null
    }

    override suspend fun persistPhoto(
        id: String,
        bytes: ByteArray,
    ): String? = "mem://intruder/$id.jpg"

    override suspend fun deleteAllPhotos() = Unit
}
