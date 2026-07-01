package com.appblish.calculatorvault.calculator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CalculatorViewModelTest {
    @Test
    fun `equals shows arithmetic result for normal input`() {
        val vm = CalculatorViewModel()
        "7+8".forEach { vm.onDigit(it.toString()) }
        vm.onEquals()
        assertThat(vm.uiState.value.result).isEqualTo("15")
        assertThat(vm.uiState.value.unlockRequested).isFalse()
    }

    @Test
    fun `equals requests unlock when input is the secret`() {
        val vm =
            CalculatorViewModel(
                secretDetector = SecretCodeDetector(secretProvider = { "42" }),
            )
        "42".forEach { vm.onDigit(it.toString()) }
        vm.onEquals()
        assertThat(vm.uiState.value.unlockRequested).isTrue()
    }

    @Test
    fun `clear resets state`() {
        val vm = CalculatorViewModel()
        vm.onDigit("9")
        vm.onClear()
        assertThat(vm.uiState.value.input).isEmpty()
    }
}
