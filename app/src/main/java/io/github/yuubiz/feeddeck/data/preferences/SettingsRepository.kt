package io.github.yuubiz.feeddeck.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.ListMode
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class BrowserMode { CUSTOM_TABS, EXTERNAL }

/** Display/update preferences and the last selection. Reading state and subscriptions live in Room only. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val listMode: ListMode = ListMode.MAGAZINE,
    val textScale: Float = 1.0f,
    val showImages: Boolean = true,
    val browserMode: BrowserMode = BrowserMode.CUSTOM_TABS,
    /** 0 = off. */
    val refreshIntervalHours: Int = 2,
    val unmeteredOnly: Boolean = true,
    val allowHttp: Boolean = false,
    val onboardingDone: Boolean = false,
    val lastScope: ArticleScope = ArticleScope.All,
    val lastFilter: ReadFilter = ReadFilter.ALL,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val lastAutoRefreshAttemptAt: Long = 0,
) {
    companion object {
        val TEXT_SCALES = listOf(0.9f, 1.0f, 1.15f, 1.3f)
        val REFRESH_INTERVALS = listOf(0, 1, 2, 6, 12, 24)
    }
}

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val listMode = stringPreferencesKey("list_mode")
        val textScale = floatPreferencesKey("text_scale")
        val showImages = booleanPreferencesKey("show_images")
        val browserMode = stringPreferencesKey("browser_mode")
        val refreshInterval = intPreferencesKey("refresh_interval_hours")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
        val allowHttp = booleanPreferencesKey("allow_http")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastScope = stringPreferencesKey("last_scope")
        val lastFilter = stringPreferencesKey("last_filter")
        val sortOrder = stringPreferencesKey("sort_order")
        val lastAutoAttempt = longPreferencesKey("last_auto_refresh_attempt")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            theme = enumOr(p[Keys.theme], defaults.theme),
            listMode = enumOr(p[Keys.listMode], defaults.listMode),
            textScale = p[Keys.textScale]?.takeIf { it in AppSettings.TEXT_SCALES } ?: defaults.textScale,
            showImages = p[Keys.showImages] ?: defaults.showImages,
            browserMode = enumOr(p[Keys.browserMode], defaults.browserMode),
            refreshIntervalHours = p[Keys.refreshInterval]?.takeIf { it in AppSettings.REFRESH_INTERVALS } ?: defaults.refreshIntervalHours,
            unmeteredOnly = p[Keys.unmeteredOnly] ?: defaults.unmeteredOnly,
            allowHttp = p[Keys.allowHttp] ?: defaults.allowHttp,
            onboardingDone = p[Keys.onboardingDone] ?: defaults.onboardingDone,
            lastScope = ArticleScope.decode(p[Keys.lastScope]),
            lastFilter = enumOr(p[Keys.lastFilter], defaults.lastFilter),
            sortOrder = enumOr(p[Keys.sortOrder], defaults.sortOrder),
            lastAutoRefreshAttemptAt = p[Keys.lastAutoAttempt] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(value: ThemeMode) = store.edit { it[Keys.theme] = value.name }
    suspend fun setListMode(value: ListMode) = store.edit { it[Keys.listMode] = value.name }
    suspend fun setTextScale(value: Float) = store.edit { it[Keys.textScale] = value }
    suspend fun setShowImages(value: Boolean) = store.edit { it[Keys.showImages] = value }
    suspend fun setBrowserMode(value: BrowserMode) = store.edit { it[Keys.browserMode] = value.name }
    suspend fun setRefreshInterval(hours: Int) = store.edit { it[Keys.refreshInterval] = hours }
    suspend fun setUnmeteredOnly(value: Boolean) = store.edit { it[Keys.unmeteredOnly] = value }
    suspend fun setAllowHttp(value: Boolean) = store.edit { it[Keys.allowHttp] = value }
    suspend fun setOnboardingDone(value: Boolean) = store.edit { it[Keys.onboardingDone] = value }
    suspend fun setLastScope(value: ArticleScope) = store.edit { it[Keys.lastScope] = value.encode() }
    suspend fun setLastFilter(value: ReadFilter) = store.edit { it[Keys.lastFilter] = value.name }
    suspend fun setSortOrder(value: SortOrder) = store.edit { it[Keys.sortOrder] = value.name }
    suspend fun setLastAutoRefreshAttempt(at: Long) = store.edit { it[Keys.lastAutoAttempt] = at }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}
