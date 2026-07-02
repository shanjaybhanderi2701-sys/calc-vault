package com.appblish.calculatorvault.intruder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntruderLogStoreTest {
    private fun store() = InMemoryIntruderLogStore()

    @Test
    fun record_thenEvents_newestFirst() =
        runTest {
            val s = store()
            s.record("1", "com.whatsapp", "WhatsApp", timestampMs = 100, photoBytes = null)
            s.record("2", "com.instagram.android", "Instagram", timestampMs = 300, photoBytes = null)
            s.record("3", "com.google.android.gm", "Gmail", timestampMs = 200, photoBytes = null)
            val events = s.events()
            assertThat(events.map { it.id }).containsExactly("2", "3", "1").inOrder()
        }

    @Test
    fun record_withPhoto_carriesStoredPath() =
        runTest {
            val s = store()
            val e = s.record("a", "com.whatsapp", "WhatsApp", 100, byteArrayOf(1, 2, 3))
            assertThat(e.photoPath).isNotNull()
            assertThat(s.events().single().photoPath).isEqualTo(e.photoPath)
        }

    @Test
    fun record_withoutPhoto_hasNullPath() =
        runTest {
            val s = store()
            s.record("a", "com.whatsapp", "WhatsApp", 100, null)
            assertThat(s.events().single().photoPath).isNull()
        }

    @Test
    fun label_withDelimiterAndNewlines_doesNotCorruptIndex() =
        runTest {
            val s = store()
            // A hostile label containing the field delimiter and a newline must not break framing.
            s.record("x", "com.evil", "Ba|::|d\nName", 100, null)
            s.record("y", "com.two", "Second", 200, null)
            val events = s.events()
            assertThat(events).hasSize(2)
            val badge = events.first { it.id == "x" }
            assertThat(badge.packageName).isEqualTo("com.evil")
            assertThat(badge.appLabel).doesNotContain("|::|")
        }

    @Test
    fun clear_emptiesTheLog() =
        runTest {
            val s = store()
            s.record("a", "com.whatsapp", "WhatsApp", 100, null)
            s.clear()
            assertThat(s.events()).isEmpty()
        }
}
