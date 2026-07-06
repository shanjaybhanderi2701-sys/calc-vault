package com.appblish.calculatorvault.calculator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the trigger condition of the first-run "=" confirm cue (P3-1, APP-225 board
 * feedback): the pulse shows only when the caller opted in (onboarding create/confirm)
 * AND exactly [PIN_LENGTH] digits are entered. The pulse rendering itself lives in
 * `CalculatorKeypad` and is verified via Compose preview / on-device.
 */
class PinEqualsCueTest {
    @Test
    fun `cue fires only with a complete PIN`() {
        assertThat(equalsCueActive(cueEnabled = true, entry = "")).isFalse()
        assertThat(equalsCueActive(cueEnabled = true, entry = "123")).isFalse()
        assertThat(equalsCueActive(cueEnabled = true, entry = "1234")).isTrue()
    }

    @Test
    fun `cue never fires when the caller did not opt in`() {
        // Change-PIN, fake-password, forgot-password and the disguise all keep the default
        // false — a complete entry must not pulse there.
        assertThat(equalsCueActive(cueEnabled = false, entry = "1234")).isFalse()
    }
}
