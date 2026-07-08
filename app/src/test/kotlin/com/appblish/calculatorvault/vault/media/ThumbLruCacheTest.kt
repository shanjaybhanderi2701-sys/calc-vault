package com.appblish.calculatorvault.vault.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The APP-244 memory bound: the decoded-thumbnail LRU must never exceed its byte budget
 * (no OOM with a huge vault), must evict least-recently-used first (a revisited 30-item
 * grid stays fully cached), and must honor point eviction (delete/restore) and [clear]
 * (vault lock).
 */
class ThumbLruCacheTest {
    private fun cache(maxBytes: Long) = ThumbLruCache<String>(maxBytes) { it.length }

    @Test
    fun `stays within the byte budget by evicting oldest entries`() {
        val cache = cache(maxBytes = 10)
        cache.put("a", "12345")
        cache.put("b", "12345")
        assertThat(cache.snapshotBytes()).isEqualTo(10)
        cache.put("c", "12345")
        assertThat(cache.snapshotBytes()).isEqualTo(10)
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("b")).isEqualTo("12345")
        assertThat(cache.get("c")).isEqualTo("12345")
    }

    @Test
    fun `get refreshes recency so hot grid tiles survive`() {
        val cache = cache(maxBytes = 10)
        cache.put("a", "12345")
        cache.put("b", "12345")
        cache.get("a")
        cache.put("c", "12345")
        assertThat(cache.get("a")).isEqualTo("12345")
        assertThat(cache.get("b")).isNull()
    }

    @Test
    fun `replacing a key adjusts the accounted bytes`() {
        val cache = cache(maxBytes = 10)
        cache.put("a", "12345678")
        cache.put("a", "12")
        assertThat(cache.snapshotBytes()).isEqualTo(2)
    }

    @Test
    fun `an entry larger than the whole budget is refused not thrashed`() {
        val cache = cache(maxBytes = 4)
        cache.put("small", "123")
        cache.put("huge", "123456")
        assertThat(cache.get("huge")).isNull()
        assertThat(cache.get("small")).isEqualTo("123")
    }

    @Test
    fun `remove and clear drop entries and bytes`() {
        val cache = cache(maxBytes = 100)
        cache.put("a", "123")
        cache.put("b", "4567")
        cache.remove("a")
        assertThat(cache.get("a")).isNull()
        assertThat(cache.snapshotBytes()).isEqualTo(4)
        cache.clear()
        assertThat(cache.size()).isEqualTo(0)
        assertThat(cache.snapshotBytes()).isEqualTo(0)
    }
}
