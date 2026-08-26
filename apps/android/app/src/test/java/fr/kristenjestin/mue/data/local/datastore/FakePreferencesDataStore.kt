package fr.kristenjestin.mue.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A Preferences store in memory, so the two writes that surround a Room transaction can be
 * asserted on the JVM.
 *
 * `DataStore<T>` is a two-method interface — a `Flow` and an `updateData` — and `edit { }` is an
 * extension built on the second, so nothing here re-implements the library: the same extension
 * the shipped code calls runs against this. What it does not reproduce is the file, the protobuf
 * and the corruption handling, which is why the store's own behaviour stays in the instrumented
 * suite and only its *callers* are tested here.
 */
class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    /** How many times a caller wrote, so "one save is one write" is assertable. */
    var writes: Int = 0
        private set

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        writes++
        state.value = updated
        return updated
    }

    fun current(): Preferences = state.value
}
