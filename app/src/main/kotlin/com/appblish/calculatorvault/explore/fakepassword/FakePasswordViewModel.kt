package com.appblish.calculatorvault.explore.fakepassword

import androidx.lifecycle.ViewModel
import com.appblish.calculatorvault.explore.ExploreStore
import com.appblish.calculatorvault.explore.FakePasswordState
import kotlinx.coroutines.flow.StateFlow

/** Backs the Fake Password (decoy PIN) management surface over the shared [ExploreStore]. */
class FakePasswordViewModel : ViewModel() {
    val state: StateFlow<FakePasswordState> = ExploreStore.fakePassword

    fun isValidPin(pin: String): Boolean = pin.length >= FakePasswordState.MIN_PIN_LENGTH && pin.all { it.isDigit() }

    fun save(
        pin: String,
        hint: String,
    ) {
        if (isValidPin(pin)) ExploreStore.setFakePassword(pin, hint)
    }

    fun setEnabled(enabled: Boolean) = ExploreStore.setFakePasswordEnabled(enabled)
}
