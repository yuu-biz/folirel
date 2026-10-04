package io.github.yuubiz.folirel.data.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences

/** The migrations the settings DataStore is opened with (production and the device test of the upgrade use this list). */
object SettingsMigrations {
    val all: List<DataMigration<Preferences>> = listOf(ListModeMigration)
}
