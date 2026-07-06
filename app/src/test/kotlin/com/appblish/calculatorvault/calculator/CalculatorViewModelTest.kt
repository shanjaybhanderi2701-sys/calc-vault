package com.appblish.calculatorvault.calculator

import com.appblish.calculatorvault.auth.VaultKind
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

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorViewModelTest {
    // Unconfined so the viewModelScope coroutine in onEquals runs eagerly and the state
    // is settled by the time each `onToken(EQUALS)` returns.
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `equals shows arithmetic result for normal input`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { null })
            listOf(CalcToken.SEVEN, CalcToken.PLUS, CalcToken.EIGHT, CalcToken.EQUALS).forEach(vm::onToken)
            assertThat(vm.uiState.value.display).isEqualTo("15")
            assertThat(vm.uiState.value.unlock).isNull()
        }

    @Test
    fun `equals unlocks the real vault when the code resolves to it`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { if (it == "4242") VaultKind.Real else null })
            listOf(CalcToken.FOUR, CalcToken.TWO, CalcToken.FOUR, CalcToken.TWO, CalcToken.EQUALS).forEach(vm::onToken)
            assertThat(vm.uiState.value.unlock).isEqualTo(VaultKind.Real)
        }

    @Test
    fun `equals routes a decoy code to its own decoy vault`() =
        runTest {
            val vm = CalculatorViewModel(resolvePin = { if (it == "5555") VaultKind.Decoy(0) else null })
            val keys = listOf(CalcToken.FIVE, CalcToken.FIVE, CalcToken.FIVE, CalcToken.FIVE, CalcToken.EQUALS)
            keys.forEach(vm::onToken)
            assertThat(vm.uiState.value.unlock).isEqualTo(VaultKind.Decoy(0))
        }

    @Test
    fun `a four-digit code that resolves to nothing clears the digits and shakes`() =
        runTest {
            // APP-242: wrong PIN + `=` clears the entry and bumps the shake counter —
            // no dialog, staying on the calculator.
            val vm = CalculatorViewModel(resolvePin = { null })
            val keys = listOf(CalcToken.NINE, CalcToken.NINE, CalcToken.NINE, CalcToken.NINE, CalcToken.EQUALS)
            keys.forEach(vm::onToken)
            assertThat(vm.uiState.value.unlock).isNull()
            assertThat(vm.uiState.value.display).isEmpty()
            assertThat(vm.uiState.value.pinRejections).isEqualTo(1)
        }

    @Test
    fun `each wrong code bumps the rejection counter again`() =
        runTest {
            // The counter must be monotonic so a second identical rejection still
            // re-triggers the shake animation keyed off it.
            val vm = CalculatorViewModel(resolvePin = { null })
            repeat(2) {
                listOf(CalcToken.NINE, CalcToken.NINE, CalcToken.NINE, CalcToken.NINE, CalcToken.EQUALS)
                    .forEach(vm::onToken)
            }
            assertThat(vm.uiState.value.pinRejections).isEqualTo(2)
        }

    @Test
    fun `there is no debug backdoor - 1234 without a credential never unlocks`() =
        runTest {
            // Spec §11 (APP-225): no debug seed PIN / default secret. The historical fixed
            // debug code gets the same wrong-PIN treatment as any other unknown code.
            val vm = CalculatorViewModel(resolvePin = { null })
            listOf(CalcToken.ONE, CalcToken.TWO, CalcToken.THREE, CalcToken.FOUR, CalcToken.EQUALS).forEach(vm::onToken)
            assertThat(vm.uiState.value.unlock).isNull()
            assertThat(vm.uiState.value.display).isEmpty()
            assertThat(vm.uiState.value.pinRejections).isEqualTo(1)
        }

    @Test
    fun `arithmetic with a four-digit result does not shake`() =
        runTest {
            // Only a *typed* 4-digit candidate is PIN-checked; ordinary sums (even ones
            // that yield 4-digit results) never trigger rejection feedback.
            val vm = CalculatorViewModel(resolvePin = { null })
            val keys =
                listOf(
                    CalcToken.NINE,
                    CalcToken.NINE,
                    CalcToken.NINE,
                    CalcToken.MULTIPLY,
                    CalcToken.NINE,
                    CalcToken.EQUALS,
                )
            keys.forEach(vm::onToken)
            assertThat(vm.uiState.value.display).isEqualTo("8991")
            assertThat(vm.uiState.value.pinRejections).isEqualTo(0)
        }

    @Test
    fun `clear resets the display`() {
        val vm = CalculatorViewModel(resolvePin = { null })
        vm.onToken(CalcToken.NINE)
        vm.onToken(CalcToken.CLEAR)
        assertThat(vm.uiState.value.display).isEmpty()
    }

    @Test
    fun `backspace deletes the last character`() {
        val vm = CalculatorViewModel(resolvePin = { null })
        vm.onToken(CalcToken.ONE)
        vm.onToken(CalcToken.TWO)
        vm.onToken(CalcToken.BACKSPACE)
        assertThat(vm.uiState.value.display).isEqualTo("1")
    }
}
