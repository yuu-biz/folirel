package io.github.yuubiz.feeddeck.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yuubiz.feeddeck.model.ListMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list layout was renamed in 0.3.2 and the old stored names overlap the new ones (old LIST = new COMPACT_LIST, old
 * MAGAZINE = new LIST, old CARD = new MAGAZINE). A user who upgrades must keep the layout they had, for all three.
 * This is the translation and the repository logic; the real DataStore file with the production migration, and the
 * layout that is actually drawn, are checked on a device (ListLayoutUpgradeTest) and on the real 0.3.1 -> 0.3.2 APKs.
 */
class ListModeMigrationTest {
    private val legacy = stringPreferencesKey("list_mode")
    private val current = stringPreferencesKey("list_layout")

    /** Applies the production migration to [initial] the way DataStore does when it is first opened. */
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> get() = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private suspend fun open(initial: Preferences): MemoryStore {
        val migrated = if (ListModeMigration.shouldMigrate(initial)) ListModeMigration.migrate(initial) else initial
        return MemoryStore(migrated)
    }

    private fun upgraded(vararg stored: Pair<String, String>): ListMode = runBlocking {
        val prefs = mutablePreferencesOf().apply { stored.forEach { (k, v) -> this[stringPreferencesKey(k)] = v } }
        SettingsRepository(open(prefs)).settings.first().listMode
    }

    @Test
    fun everyOldLayoutKeepsItsMeaning() {
        // stored by 0.3.1  ->  what it showed  ->  the layout that shows the same thing now
        assertEquals("one column with a side thumbnail", ListMode.LIST, upgraded("list_mode" to "MAGAZINE"))
        assertEquals("one dense row per article", ListMode.COMPACT_LIST, upgraded("list_mode" to "LIST"))
        assertEquals("the grid of cards of different sizes", ListMode.MAGAZINE, upgraded("list_mode" to "CARD"))
    }

    @Test
    fun theOldNamesAreNotReadAsTheNewOnes() {
        // The trap of a plain rename: an old LIST read as the new LIST, an old MAGAZINE as the new MAGAZINE.
        assertEquals(ListMode.COMPACT_LIST, upgraded("list_mode" to "LIST"))
        assertEquals(ListMode.LIST, upgraded("list_mode" to "MAGAZINE"))
        // And the new key holds new names only: a stored new name is read as itself.
        assertEquals(ListMode.LIST, upgraded("list_layout" to "LIST"))
        assertEquals(ListMode.MAGAZINE, upgraded("list_layout" to "MAGAZINE"))
        assertEquals(ListMode.COMPACT_LIST, upgraded("list_layout" to "COMPACT_LIST"))
    }

    @Test
    fun theMigrationAddsTheNewKeyAndLeavesTheOldOneAlone() = runBlocking<Unit> {
        val before = mutablePreferencesOf(legacy to "CARD")
        assertTrue(ListModeMigration.shouldMigrate(before))
        val after = ListModeMigration.migrate(before)
        assertEquals("MAGAZINE", after[current])
        assertEquals("CARD", after[legacy])
        assertFalse("done: nothing left to migrate", ListModeMigration.shouldMigrate(after))
    }

    @Test
    fun aChoiceMadeAfterTheUpgradeIsKeptAndNotMigratedAgain() = runBlocking<Unit> {
        // 0.3.1 left MAGAZINE (the one-column list); the user upgrades, then picks the compact list.
        val first = open(mutablePreferencesOf(legacy to "MAGAZINE"))
        assertEquals(ListMode.LIST, SettingsRepository(first).settings.first().listMode)
        SettingsRepository(first).setListMode(ListMode.COMPACT_LIST)
        // At the next start the old key still says MAGAZINE, but the new key (the later choice) decides.
        val again = first.data.first()
        assertFalse(ListModeMigration.shouldMigrate(again))
        assertEquals(ListMode.COMPACT_LIST, SettingsRepository(MemoryStore(again)).settings.first().listMode)
    }

    @Test
    fun noStoredLayoutMeansTheDefaultThatLooksLikeBefore() = runBlocking<Unit> {
        // The 0.3.1 default was MAGAZINE (the one-column list), which is LIST now.
        assertFalse(ListModeMigration.shouldMigrate(emptyPreferences()))
        assertEquals(ListMode.LIST, SettingsRepository(open(emptyPreferences())).settings.first().listMode)
        assertEquals(ListMode.LIST, AppSettings().listMode)
    }

    @Test
    fun anUnknownOldValueFallsBackToTheDefaultWithoutWritingAnything() = runBlocking<Unit> {
        val before = mutablePreferencesOf(legacy to "SOMETHING_ELSE")
        assertEquals(before, ListModeMigration.migrate(before))
        assertNull(ListModeMigration.migrate(before)[current])
        assertEquals(ListMode.LIST, upgraded("list_mode" to "SOMETHING_ELSE"))
    }

    @Test
    fun theTranslationCoversEveryOldValueAndEveryNewLayoutOnce() {
        val oldValues = listOf("MAGAZINE", "LIST", "CARD")
        val mapped = oldValues.map { ListModeMigration.fromLegacy(it) }
        assertEquals(ListMode.entries.toSet(), mapped.toSet())
        assertEquals(oldValues.size, mapped.toSet().size)
        assertNull(ListModeMigration.fromLegacy(null))
    }

    @Test
    fun theStoredNamesOfTheNewKeyAreTheEnumNames() {
        assertEquals(listOf("LIST", "COMPACT_LIST", "MAGAZINE"), ListMode.entries.map { it.name })
    }
}
