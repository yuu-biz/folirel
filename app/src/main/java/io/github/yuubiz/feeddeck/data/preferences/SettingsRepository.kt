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
import io.github.yuubiz.feeddeck.model.CardLayoutStyle
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
    val listMode: ListMode = ListMode.LIST,
    val textScale: Float = 1.0f,
    /** Columns of the Magazine layout: 0 = automatic by width and text size, otherwise 1..3 (reduced when they do not fit). */
    val cardColumns: Int = 0,
    /** Mixed sizes (magazine page) or one size for every card. */
    val cardStyle: CardLayoutStyle = CardLayoutStyle.MIXED,
    val showImages: Boolean = true,
    val browserMode: BrowserMode = BrowserMode.CUSTOM_TABS,
    /** Periodic update interval in minutes; 0 = off. Default 2 hours. */
    val refreshIntervalMinutes: Int = 120,
    /** Refresh once when the app comes to the foreground (at most every 30 minutes, same network rule). */
    val startupRefresh: Boolean = true,
    val unmeteredOnly: Boolean = true,
    val allowHttp: Boolean = false,
    /**
     * Ask before opening an http:// article in the browser. Off only after "don't ask again" was ticked in that
     * dialog or the switch was turned off in settings. Independent from [allowHttp] (feed/image downloads) and
     * from the browser's own checks.
     */
    val confirmHttpOpen: Boolean = true,
    val onboardingDone: Boolean = false,
    val lastScope: ArticleScope = ArticleScope.All,
    val lastFilter: ReadFilter = ReadFilter.ALL,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    /** Last refresh attempt of any kind (docs/fetch-and-security.md §5: the startup rule is based on the last attempt). */
    val lastRefreshAttemptAt: Long = 0,
    /** Articles to keep on the device (0 = no limit). Unread and saved articles are never removed. */
    val retentionLimit: Int = 0,
    /** Thumbnail disk cache size; applied at the next app start. */
    val imageCacheMb: Int = 128,
) {
    companion object {
        val TEXT_SCALES = listOf(0.9f, 1.0f, 1.15f, 1.3f)
        val REFRESH_INTERVALS_MINUTES = listOf(0, 15, 30, 60, 120, 360, 720, 1440)
        /** Values of the v0.1 "refresh_interval_hours" key, migrated x60. */
        val LEGACY_INTERVAL_HOURS = listOf(0, 1, 2, 6, 12, 24)
        val RETENTION_LIMITS = listOf(0, 1_000, 3_000, 5_000, 10_000, 30_000)
        val IMAGE_CACHE_MB = listOf(64, 128, 256, 512)
        val CARD_COLUMNS = listOf(0, 1, 2, 3)
    }
}

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        /** `list_layout`; the pre-0.3.2 `list_mode` is translated by [ListModeMigration] and never read here. */
        val listMode = ListModeMigration.KEY
        val textScale = floatPreferencesKey("text_scale")
        val cardColumns = intPreferencesKey("card_columns")
        val cardStyle = stringPreferencesKey("card_style")
        val showImages = booleanPreferencesKey("show_images")
        val browserMode = stringPreferencesKey("browser_mode")
        /** v0.1 key (hours). Read only for migration; never written. */
        val legacyRefreshHours = intPreferencesKey("refresh_interval_hours")
        val refreshMinutes = intPreferencesKey("refresh_interval_minutes")
        val startupRefresh = booleanPreferencesKey("startup_refresh")
        val retentionLimit = intPreferencesKey("retention_limit")
        val imageCacheMb = intPreferencesKey("image_cache_mb")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
        val allowHttp = booleanPreferencesKey("allow_http")
        val confirmHttpOpen = booleanPreferencesKey("confirm_http_open")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastScope = stringPreferencesKey("last_scope")
        val lastFilter = stringPreferencesKey("last_filter")
        val sortOrder = stringPreferencesKey("sort_order")
        val lastAttempt = longPreferencesKey("last_refresh_attempt")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            theme = enumOr(p[Keys.theme], defaults.theme),
            listMode = enumOr(p[Keys.listMode], defaults.listMode),
            textScale = p[Keys.textScale]?.takeIf { it in AppSettings.TEXT_SCALES } ?: defaults.textScale,
            cardStyle = enumOr(p[Keys.cardStyle], defaults.cardStyle),
            cardColumns = p[Keys.cardColumns]?.takeIf { it in AppSettings.CARD_COLUMNS } ?: defaults.cardColumns,
            showImages = p[Keys.showImages] ?: defaults.showImages,
            browserMode = enumOr(p[Keys.browserMode], defaults.browserMode),
            refreshIntervalMinutes = intervalMinutes(p),
            // Existing users: startup refresh used to follow the automatic-update switch.
            startupRefresh = p[Keys.startupRefresh] ?: (intervalMinutes(p) > 0),
            unmeteredOnly = p[Keys.unmeteredOnly] ?: defaults.unmeteredOnly,
            allowHttp = p[Keys.allowHttp] ?: defaults.allowHttp,
            confirmHttpOpen = p[Keys.confirmHttpOpen] ?: defaults.confirmHttpOpen,
            onboardingDone = p[Keys.onboardingDone] ?: defaults.onboardingDone,
            lastScope = ArticleScope.decode(p[Keys.lastScope]),
            lastFilter = enumOr(p[Keys.lastFilter], defaults.lastFilter),
            sortOrder = enumOr(p[Keys.sortOrder], defaults.sortOrder),
            lastRefreshAttemptAt = p[Keys.lastAttempt] ?: 0,
            retentionLimit = p[Keys.retentionLimit]?.takeIf { it in AppSettings.RETENTION_LIMITS } ?: defaults.retentionLimit,
            imageCacheMb = p[Keys.imageCacheMb]?.takeIf { it in AppSettings.IMAGE_CACHE_MB } ?: defaults.imageCacheMb,
        )
    }

    /** Minutes key first; otherwise the v0.1 hours value x60 (2 hours stays 2 hours, never 2 minutes). */
    private fun intervalMinutes(p: Preferences): Int =
        p[Keys.refreshMinutes]?.takeIf { it in AppSettings.REFRESH_INTERVALS_MINUTES }
            ?: p[Keys.legacyRefreshHours]?.takeIf { it in AppSettings.LEGACY_INTERVAL_HOURS }?.let { it * 60 }
            ?: AppSettings().refreshIntervalMinutes

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(value: ThemeMode) = store.edit { it[Keys.theme] = value.name }
    suspend fun setListMode(value: ListMode) = store.edit { it[Keys.listMode] = value.name }
    suspend fun setTextScale(value: Float) = store.edit { it[Keys.textScale] = value }
    suspend fun setCardColumns(value: Int) = store.edit { it[Keys.cardColumns] = value }
    suspend fun setCardStyle(value: CardLayoutStyle) = store.edit { it[Keys.cardStyle] = value.name }
    suspend fun setShowImages(value: Boolean) = store.edit { it[Keys.showImages] = value }
    suspend fun setBrowserMode(value: BrowserMode) = store.edit { it[Keys.browserMode] = value.name }
    suspend fun setRefreshIntervalMinutes(minutes: Int) = store.edit { it[Keys.refreshMinutes] = minutes }
    suspend fun setStartupRefresh(value: Boolean) = store.edit { it[Keys.startupRefresh] = value }
    suspend fun setRetentionLimit(value: Int) = store.edit { it[Keys.retentionLimit] = value }
    suspend fun setImageCacheMb(value: Int) = store.edit { it[Keys.imageCacheMb] = value }
    suspend fun setUnmeteredOnly(value: Boolean) = store.edit { it[Keys.unmeteredOnly] = value }
    suspend fun setAllowHttp(value: Boolean) = store.edit { it[Keys.allowHttp] = value }
    suspend fun setConfirmHttpOpen(value: Boolean) = store.edit { it[Keys.confirmHttpOpen] = value }
    suspend fun setOnboardingDone(value: Boolean) = store.edit { it[Keys.onboardingDone] = value }
    suspend fun setLastScope(value: ArticleScope) = store.edit { it[Keys.lastScope] = value.encode() }
    suspend fun setLastFilter(value: ReadFilter) = store.edit { it[Keys.lastFilter] = value.name }
    suspend fun setSortOrder(value: SortOrder) = store.edit { it[Keys.sortOrder] = value.name }
    suspend fun setLastRefreshAttempt(at: Long) = store.edit { it[Keys.lastAttempt] = at }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}
