package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecycleBinTest {
    private val day = 24L * 60L * 60L * 1000L

    private fun entry(deletedDaysAgo: Long): RecycleBinEntry {
        val now = 1_000L * day
        return RecycleBinEntry(
            item = VaultItem("i", VaultCategory.PHOTOS, "a.jpg", "Today", now),
            deletedAt = now - deletedDaysAgo * day,
        )
    }

    private val now = 1_000L * day

    @Test
    fun `days left counts down within the window`() {
        assertThat(RecycleBin.daysLeft(entry(0), now)).isEqualTo(30)
        assertThat(RecycleBin.daysLeft(entry(10), now)).isEqualTo(20)
        assertThat(RecycleBin.daysLeft(entry(29), now)).isEqualTo(1)
    }

    @Test
    fun `days left never goes negative`() {
        assertThat(RecycleBin.daysLeft(entry(45), now)).isEqualTo(0)
    }

    @Test
    fun `entry expires only at or past the window`() {
        assertThat(RecycleBin.isExpired(entry(29), now)).isFalse()
        assertThat(RecycleBin.isExpired(entry(30), now)).isTrue()
        assertThat(RecycleBin.isExpired(entry(31), now)).isTrue()
    }

    @Test
    fun `partition splits expired from surviving`() {
        val entries = listOf(entry(5), entry(30), entry(40), entry(0))
        val (expired, kept) = RecycleBin.partitionExpired(entries, now)
        assertThat(expired).hasSize(2)
        assertThat(kept).hasSize(2)
    }
}
