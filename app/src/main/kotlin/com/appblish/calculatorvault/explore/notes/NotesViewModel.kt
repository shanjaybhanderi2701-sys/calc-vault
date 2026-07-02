package com.appblish.calculatorvault.explore.notes

import androidx.lifecycle.ViewModel
import com.appblish.calculatorvault.explore.ExploreStore
import com.appblish.calculatorvault.explore.Note
import kotlinx.coroutines.flow.StateFlow

/** Backs the Notes list + editor over the shared [ExploreStore]. */
class NotesViewModel(
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val notes: StateFlow<List<Note>> = ExploreStore.notes

    fun note(id: String?): Note? = id?.let { current -> notes.value.firstOrNull { it.id == current } }

    /** Creates or updates a note; returns the resolved id. */
    fun save(
        id: String?,
        title: String,
        body: String,
    ): String = ExploreStore.upsertNote(id, title, body, clock())

    fun delete(id: String) = ExploreStore.deleteNote(id)
}
