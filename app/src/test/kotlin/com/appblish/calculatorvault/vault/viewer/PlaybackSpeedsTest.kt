package com.appblish.calculatorvault.vault.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-350 (Wave 3) — the §5c speed-dialog option set and label formatting, pinned so the DoD
 * "Speed control works (0.5x-2x range)" reflects the exact required steps.
 */
class PlaybackSpeedsTest {
    @Test
    fun `options are exactly the spec set 0_5 to 2x`() {
        assertThat(PlaybackSpeeds.OPTIONS)
            .containsExactly(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
            .inOrder()
    }

    @Test
    fun `labels render round steps with one decimal and 0_75 with two`() {
        assertThat(PlaybackSpeeds.label(0.5f)).isEqualTo("0.5x")
        assertThat(PlaybackSpeeds.label(0.75f)).isEqualTo("0.75x")
        assertThat(PlaybackSpeeds.label(1.0f)).isEqualTo("1.0x")
        assertThat(PlaybackSpeeds.label(1.5f)).isEqualTo("1.5x")
        assertThat(PlaybackSpeeds.label(2.0f)).isEqualTo("2.0x")
    }

    @Test
    fun `isDefault is true only at 1x`() {
        assertThat(PlaybackSpeeds.isDefault(1.0f)).isTrue()
        assertThat(PlaybackSpeeds.isDefault(1.5f)).isFalse()
    }

    @Test
    fun `nearest snaps arbitrary speeds to the closest option`() {
        assertThat(PlaybackSpeeds.nearest(0.8f)).isEqualTo(0.75f)
        assertThat(PlaybackSpeeds.nearest(1.9f)).isEqualTo(2.0f)
        assertThat(PlaybackSpeeds.nearest(1.1f)).isEqualTo(1.0f)
    }
}
