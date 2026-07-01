package com.appblish.calculatorvault.calculator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CalculatorEngineTest {
    @Test
    fun `adds two numbers`() {
        assertThat(CalculatorEngine.evaluate("2+3")).isEqualTo(5.0)
    }

    @Test
    fun `evaluates left to right without precedence`() {
        // 2 + 3 * 4 -> (2+3)*4 = 20, deliberately (pocket-calculator behavior).
        assertThat(CalculatorEngine.evaluate("2+3*4")).isEqualTo(20.0)
    }

    @Test
    fun `supports unicode operators`() {
        assertThat(CalculatorEngine.evaluate("6÷2")).isEqualTo(3.0)
        assertThat(CalculatorEngine.evaluate("6×2")).isEqualTo(12.0)
    }

    @Test
    fun `handles leading negative`() {
        assertThat(CalculatorEngine.evaluate("-5+2")).isEqualTo(-3.0)
    }

    @Test
    fun `division by zero returns null`() {
        assertThat(CalculatorEngine.evaluate("4/0")).isNull()
    }

    @Test
    fun `empty input returns null`() {
        assertThat(CalculatorEngine.evaluate("")).isNull()
        assertThat(CalculatorEngine.evaluate("   ")).isNull()
    }

    @Test
    fun `malformed input returns null`() {
        assertThat(CalculatorEngine.evaluate("2++")).isNull()
        assertThat(CalculatorEngine.evaluate("abc")).isNull()
    }
}
