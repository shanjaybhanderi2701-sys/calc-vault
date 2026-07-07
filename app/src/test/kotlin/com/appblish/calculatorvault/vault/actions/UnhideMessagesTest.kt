package com.appblish.calculatorvault.vault.actions

import com.appblish.calculatorvault.vault.model.UnhideDisposition
import com.appblish.calculatorvault.vault.model.UnhideOutcome
import com.appblish.calculatorvault.vault.model.UnhideResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Locks the design §7 "never fail silently" copy for every un-hide outcome shape. */
class UnhideMessagesTest {
    private fun outcome(
        disposition: UnhideDisposition,
        dest: String? = null,
    ) = UnhideOutcome("id", disposition, dest)

    @Test
    fun `all landed at requested destination`() {
        val r = UnhideResult(listOf(outcome(UnhideDisposition.REQUESTED), outcome(UnhideDisposition.REQUESTED)))
        assertThat(UnhideMessages.summary(r)).isEqualTo("Unhid 2 photos.")
    }

    @Test
    fun `single item uses singular noun`() {
        val r = UnhideResult(listOf(outcome(UnhideDisposition.REQUESTED)))
        assertThat(UnhideMessages.summary(r)).isEqualTo("Unhid 1 photo.")
    }

    @Test
    fun `partial fallback names the count and destination`() {
        val r =
            UnhideResult(
                listOf(
                    outcome(UnhideDisposition.REQUESTED),
                    outcome(UnhideDisposition.FALLBACK, "Downloads"),
                ),
            )
        assertThat(UnhideMessages.summary(r))
            .isEqualTo("Unhid 1 · saved 1 to Downloads (original unavailable).")
    }

    @Test
    fun `all fell back reports the fallback destination`() {
        val r = UnhideResult(listOf(outcome(UnhideDisposition.FALLBACK, "Downloads")))
        assertThat(UnhideMessages.summary(r)).isEqualTo("Original folder unavailable — saved 1 to Downloads.")
    }

    @Test
    fun `total failure warns without losing the file`() {
        val r = UnhideResult(listOf(outcome(UnhideDisposition.FAILED), outcome(UnhideDisposition.FAILED)))
        assertThat(UnhideMessages.summary(r)).isEqualTo("Couldn't unhide — check storage access.")
        assertThat(r.totalFailure).isTrue()
        assertThat(r.unhidden).isEqualTo(0)
    }
}
