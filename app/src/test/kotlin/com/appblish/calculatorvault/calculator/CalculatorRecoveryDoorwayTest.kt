package com.appblish.calculatorvault.calculator

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * PIN Recovery W2 (APP-324) — the two calculator doorways, verified on the ViewModel so the
 * "opens only, never resets" guarantee (spec §1.4) is provable without Compose:
 *  - the fixed `11223344 =` code raises [CalculatorUiState.openRecovery] and nothing else;
 *  - three failed PIN attempts raise the subtle "try another way" affordance (spec §3.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorRecoveryDoorwayTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun CalculatorViewModel.type(digits: String) {
        digits.forEach { c -> onToken(CalcToken.entries.first { it.input == c }) }
    }

    @Test
    fun `11223344 then equals opens recovery and resets nothing`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { error("doorway must not resolve a PIN") })
            vm.type("11223344")
            vm.onToken(CalcToken.EQUALS)

            assertThat(vm.uiState.value.openRecovery).isTrue()
            // Opens only: no vault unlock, and the display is left exactly as typed — the
            // doorway never evaluates the code to a number and never clears anything.
            assertThat(vm.uiState.value.unlock).isNull()
            assertThat(vm.uiState.value.display).isEqualTo("11223344")
            assertThat(vm.uiState.value.pinRejections).isEqualTo(0)
        }

    @Test
    fun `onRecoveryHandled clears the one-shot signal`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { null })
            vm.type("11223344")
            vm.onToken(CalcToken.EQUALS)
            assertThat(vm.uiState.value.openRecovery).isTrue()

            vm.onRecoveryHandled()
            assertThat(vm.uiState.value.openRecovery).isFalse()
        }

    @Test
    fun `affordance appears only on the third failed attempt`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { null })

            vm.type("0000")
            vm.onToken(CalcToken.EQUALS)
            assertThat(vm.uiState.value.showRecoveryAffordance).isFalse()

            vm.type("0000")
            vm.onToken(CalcToken.EQUALS)
            assertThat(vm.uiState.value.showRecoveryAffordance).isFalse()

            vm.type("0000")
            vm.onToken(CalcToken.EQUALS)
            assertThat(vm.uiState.value.pinRejections).isEqualTo(3)
            assertThat(vm.uiState.value.showRecoveryAffordance).isTrue()
        }

    @Test
    fun `affordance survives AC and its tap opens recovery`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { null })
            repeat(3) {
                vm.type("0000")
                vm.onToken(CalcToken.EQUALS)
            }
            assertThat(vm.uiState.value.showRecoveryAffordance).isTrue()

            // AC clears the digits + attempt count but must not hide the earned lifeline.
            vm.onToken(CalcToken.CLEAR)
            assertThat(vm.uiState.value.showRecoveryAffordance).isTrue()
            assertThat(vm.uiState.value.pinRejections).isEqualTo(0)

            vm.openRecoveryScreen()
            assertThat(vm.uiState.value.openRecovery).isTrue()
        }

    @Test
    fun `ordinary arithmetic never triggers recovery`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { null })
            vm.type("2")
            vm.onToken(CalcToken.PLUS)
            vm.type("2")
            vm.onToken(CalcToken.EQUALS)

            assertThat(vm.uiState.value.display).isEqualTo("4")
            assertThat(vm.uiState.value.openRecovery).isFalse()
            assertThat(vm.uiState.value.showRecoveryAffordance).isFalse()
        }
}
