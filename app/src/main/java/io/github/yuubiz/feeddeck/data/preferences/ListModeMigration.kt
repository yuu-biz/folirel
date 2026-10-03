package io.github.yuubiz.feeddeck.data.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yuubiz.feeddeck.model.ListMode

/**
 * The list layout was stored under `list_mode` as MAGAZINE, LIST or CARD up to 0.3.1. Those names meant: MAGAZINE = one
 * column with thumbnails, LIST = compact rows, CARD = the grid of cards of different sizes. From 0.3.2 the concepts are
 * named for what they are (List, Compact list, Magazine) and stored under the new key `list_layout`. The names overlap
 * (an old LIST is a new COMPACT_LIST, an old MAGAZINE is a new LIST), so an old value must never be read as a new name:
 * it is translated here, once, explicitly, and the user keeps the layout they had.
 *
 * The old key is left in the file as it was (this only adds `list_layout`); once `list_layout` exists it alone decides.
 */
object ListModeMigration : DataMigration<Preferences> {
    val LEGACY_KEY = stringPreferencesKey("list_mode")
    val KEY = stringPreferencesKey("list_layout")

    /** The new layout that shows what the stored pre-0.3.2 [legacy] value showed; null for a value that never existed. */
    fun fromLegacy(legacy: String?): ListMode? = when (legacy) {
        "MAGAZINE" -> ListMode.LIST
        "LIST" -> ListMode.COMPACT_LIST
        "CARD" -> ListMode.MAGAZINE
        else -> null
    }

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[KEY] == null && currentData[LEGACY_KEY] != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val migrated = fromLegacy(currentData[LEGACY_KEY]) ?: return currentData
        return currentData.toMutablePreferences().apply { this[KEY] = migrated.name }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
