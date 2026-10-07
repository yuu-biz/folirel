package io.github.yuubiz.folirel.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.HiddenKeywords
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The colour set, independent from [ThemeMode] (light/dark). [CLASSIC] is the blue set every version up to 0.6.0 used and the
 * default, so an update never changes how an existing install looks. [DYNAMIC] needs Android 12 (API 31); where it is not
 * available the app shows [CLASSIC] (see [effective]) and keeps the stored choice untouched.
 */
enum class ColorPalette {
    CLASSIC, LEAF, SAND, VIOLET, DYNAMIC,

    /** One base colour chosen by the user ([AppSettings.customSeed]); the light and dark schemes are made from it. */
    CUSTOM;

    /** The palette that is drawn: the stored one, except that Dynamic on a system without it is Classic. */
    fun effective(sdkInt: Int): ColorPalette = if (this == DYNAMIC && sdkInt < DYNAMIC_MIN_SDK) CLASSIC else this

    companion object {
        /** Material dynamic colour (dynamicLightColorScheme) exists from Android 12. */
        const val DYNAMIC_MIN_SDK = 31

        fun isAvailable(palette: ColorPalette, sdkInt: Int): Boolean = palette != DYNAMIC || sdkInt >= DYNAMIC_MIN_SDK
    }
}

enum class BrowserMode { CUSTOM_TABS, EXTERNAL }

/**
 * How the category navigation is shown on a wide window: [AUTO] a persistent pane on an expanded window (>= 840 dp) and the drawer
 * below that, [PANE] a pane as soon as the window is medium (>= 600 dp) and the articles keep a readable width, [DRAWER] always the
 * modal drawer. A compact window (< 600 dp) always has the drawer. Default [AUTO], also for a store written before 0.8.0.
 */
enum class CategoryNavigation { AUTO, PANE, DRAWER }

/** Display/update preferences and the last selection. Reading state and subscriptions live in Room only. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Colour set; absent in a store written before 0.7.0, which reads as [ColorPalette.CLASSIC] (no visual change on update). */
    val palette: ColorPalette = ColorPalette.CLASSIC,
    /** The base colour of [ColorPalette.CUSTOM], opaque ARGB. It is kept when another set is chosen. Absent in a store written before 0.9.3. */
    val customSeed: Int = DEFAULT_CUSTOM_SEED,
    /** Colours of the light Custom scheme set by hand (0.9.5); null: made from [customSeed]. Absent in a store written before 0.9.5. */
    val customLight: CustomColors? = null,
    /** The same for the dark Custom scheme; light and dark are independent. */
    val customDark: CustomColors? = null,
    val listMode: ListMode = ListMode.LIST,
    val textScale: Float = 1.0f,
    /** Columns of the Magazine, Masonry and Bento layouts: 0 = automatic by width and text size, otherwise 1..5 (reduced when they do not fit). */
    val cardColumns: Int = 0,
    /** Mixed sizes (magazine page) or one size for every card. */
    val cardStyle: CardLayoutStyle = CardLayoutStyle.MIXED,
    val showImages: Boolean = true,
    /** Category navigation of a wide window (pane or drawer); a compact window has the drawer whatever this says. */
    val categoryNavigation: CategoryNavigation = CategoryNavigation.AUTO,
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
    /**
     * Ask before a manual update on a metered network while automatic updates wait for an unmetered one. Off only after
     * "don't ask again" was ticked in that dialog or the switch was turned off in settings. Automatic updates are unaffected.
     */
    val confirmMeteredRefresh: Boolean = true,
    val onboardingDone: Boolean = false,
    val lastScope: ArticleScope = ArticleScope.All,
    val lastFilter: ReadFilter = ReadFilter.ALL,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    /** How far back the home list reaches; kept across categories and restarts. A store written before 0.9.0 reads as [PeriodFilter.ALL]. */
    val periodFilter: PeriodFilter = PeriodFilter.ALL,
    /** Global hidden keywords, in the order the reader added them (title and summary are matched). Empty before 0.9.0. */
    val hiddenKeywords: List<String> = emptyList(),
    /** The hidden-keyword filter is on. Off by default; it only has an effect while [hiddenKeywords] is not empty (see [activeHiddenKeywords]). */
    val hiddenKeywordsEnabled: Boolean = false,
    /** Last refresh attempt of any kind (docs/fetch-and-security.md §5: the startup rule is based on the last attempt). */
    val lastRefreshAttemptAt: Long = 0,
    /** Articles to keep on the device (0 = no limit). Unread and saved articles are never removed. */
    val retentionLimit: Int = 0,
    /** Thumbnail disk cache size; applied at the next app start. */
    val imageCacheMb: Int = 128,
) {
    /** The keywords the list query applies: none while the filter is off. */
    val activeHiddenKeywords: List<String> get() = if (hiddenKeywordsEnabled) hiddenKeywords else emptyList()

    companion object {
        /** Text size, in percent: 90..130 in steps of 5. The four values older versions offered (90, 100, 115, 130) are on this grid. */
        /** The base colour Custom starts from and "reset" returns to (a teal; opaque ARGB). */
        const val DEFAULT_CUSTOM_SEED: Int = 0xFF00796B.toInt()

        /** A stored or chosen seed with its alpha forced opaque (the colour is an RGB value). */
        fun opaque(seed: Int): Int = seed or 0xFF000000.toInt()

        const val TEXT_SCALE_MIN_PERCENT = 90
        const val TEXT_SCALE_MAX_PERCENT = 130
        const val TEXT_SCALE_STEP_PERCENT = 5

        /** The stored factor for [percent] (115 -> 1.15f, the same float the older constants had). */
        fun textScaleOf(percent: Int): Float = percent / 100f

        fun textScalePercent(scale: Float): Int = Math.round(scale * 100f)

        /** A stored factor is valid when it is exactly on the grid; anything else (hand-edited, from a later version) is the default. */
        fun isTextScale(scale: Float): Boolean {
            val percent = textScalePercent(scale)
            return percent in TEXT_SCALE_MIN_PERCENT..TEXT_SCALE_MAX_PERCENT &&
                (percent - TEXT_SCALE_MIN_PERCENT) % TEXT_SCALE_STEP_PERCENT == 0 && textScaleOf(percent) == scale
        }
        val REFRESH_INTERVALS_MINUTES = listOf(0, 15, 30, 60, 120, 360, 720, 1440)
        val RETENTION_LIMITS = listOf(0, 1_000, 3_000, 5_000, 10_000, 30_000)
        val IMAGE_CACHE_MB = listOf(64, 128, 256, 512)
        /** 0 = automatic, otherwise the most columns wanted. Shared by the global setting, the category override and their validation. */
        val CARD_COLUMNS = listOf(0, 1, 2, 3, 4, 5)
    }
}

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val palette = stringPreferencesKey("color_palette")
        val customSeed = intPreferencesKey("custom_seed")
        val customLight = stringPreferencesKey("custom_light_colors")
        val customDark = stringPreferencesKey("custom_dark_colors")
        val listMode = stringPreferencesKey("list_layout")
        val textScale = floatPreferencesKey("text_scale")
        val cardColumns = intPreferencesKey("card_columns")
        val cardStyle = stringPreferencesKey("card_style")
        val showImages = booleanPreferencesKey("show_images")
        val categoryNavigation = stringPreferencesKey("category_navigation")
        val browserMode = stringPreferencesKey("browser_mode")
        val refreshMinutes = intPreferencesKey("refresh_interval_minutes")
        val startupRefresh = booleanPreferencesKey("startup_refresh")
        val retentionLimit = intPreferencesKey("retention_limit")
        val imageCacheMb = intPreferencesKey("image_cache_mb")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
        val allowHttp = booleanPreferencesKey("allow_http")
        val confirmHttpOpen = booleanPreferencesKey("confirm_http_open")
        val confirmMeteredRefresh = booleanPreferencesKey("confirm_metered_refresh")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastScope = stringPreferencesKey("last_scope")
        val lastFilter = stringPreferencesKey("last_filter")
        val sortOrder = stringPreferencesKey("sort_order")
        val periodFilter = stringPreferencesKey("period_filter")
        val hiddenKeywords = stringPreferencesKey("hidden_keywords")
        val hiddenKeywordsEnabled = booleanPreferencesKey("hidden_keywords_enabled")
        val lastAttempt = longPreferencesKey("last_refresh_attempt")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            theme = enumOr(p[Keys.theme], defaults.theme),
            palette = enumOr(p[Keys.palette], defaults.palette),
            customSeed = p[Keys.customSeed]?.let { AppSettings.opaque(it) } ?: defaults.customSeed,
            customLight = CustomColors.decode(p[Keys.customLight]),
            customDark = CustomColors.decode(p[Keys.customDark]),
            listMode = enumOr(p[Keys.listMode], defaults.listMode),
            textScale = p[Keys.textScale]?.takeIf { AppSettings.isTextScale(it) } ?: defaults.textScale,
            cardStyle = enumOr(p[Keys.cardStyle], defaults.cardStyle),
            cardColumns = p[Keys.cardColumns]?.takeIf { it in AppSettings.CARD_COLUMNS } ?: defaults.cardColumns,
            showImages = p[Keys.showImages] ?: defaults.showImages,
            categoryNavigation = enumOr(p[Keys.categoryNavigation], defaults.categoryNavigation),
            browserMode = enumOr(p[Keys.browserMode], defaults.browserMode),
            refreshIntervalMinutes = intervalMinutes(p),
            // Until the user chooses, startup refresh follows the automatic-update switch.
            startupRefresh = p[Keys.startupRefresh] ?: (intervalMinutes(p) > 0),
            unmeteredOnly = p[Keys.unmeteredOnly] ?: defaults.unmeteredOnly,
            allowHttp = p[Keys.allowHttp] ?: defaults.allowHttp,
            confirmHttpOpen = p[Keys.confirmHttpOpen] ?: defaults.confirmHttpOpen,
            confirmMeteredRefresh = p[Keys.confirmMeteredRefresh] ?: defaults.confirmMeteredRefresh,
            onboardingDone = p[Keys.onboardingDone] ?: defaults.onboardingDone,
            lastScope = ArticleScope.decode(p[Keys.lastScope]),
            lastFilter = enumOr(p[Keys.lastFilter], defaults.lastFilter),
            sortOrder = enumOr(p[Keys.sortOrder], defaults.sortOrder),
            periodFilter = enumOr(p[Keys.periodFilter], defaults.periodFilter),
            hiddenKeywords = HiddenKeywords.decode(p[Keys.hiddenKeywords]),
            hiddenKeywordsEnabled = p[Keys.hiddenKeywordsEnabled] ?: defaults.hiddenKeywordsEnabled,
            lastRefreshAttemptAt = p[Keys.lastAttempt] ?: 0,
            retentionLimit = p[Keys.retentionLimit]?.takeIf { it in AppSettings.RETENTION_LIMITS } ?: defaults.retentionLimit,
            imageCacheMb = p[Keys.imageCacheMb]?.takeIf { it in AppSettings.IMAGE_CACHE_MB } ?: defaults.imageCacheMb,
        )
    }

    private fun intervalMinutes(p: Preferences): Int =
        p[Keys.refreshMinutes]?.takeIf { it in AppSettings.REFRESH_INTERVALS_MINUTES } ?: AppSettings().refreshIntervalMinutes

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(value: ThemeMode) = store.edit { it[Keys.theme] = value.name }
    suspend fun setPalette(value: ColorPalette) = store.edit { it[Keys.palette] = value.name }

    /** Sets the Custom base colour and makes Custom the colour set, in one write (the picker's Apply). */
    suspend fun applyCustomPalette(seed: Int) = store.edit {
        it[Keys.customSeed] = AppSettings.opaque(seed)
        it[Keys.palette] = ColorPalette.CUSTOM.name
    }

    /** Back to the default base colour; the colour set and the colours set by hand stay as they are. */
    suspend fun resetCustomSeed() = store.edit { it[Keys.customSeed] = AppSettings.DEFAULT_CUSTOM_SEED }

    /** The colours of the light or [dark] Custom scheme set by hand (the editor's Save), in one write. The base colour is not touched. */
    suspend fun setCustomColors(dark: Boolean, colors: CustomColors) = store.edit {
        it[if (dark) Keys.customDark else Keys.customLight] = colors.encode()
    }

    /** Forgets the colours set by hand for [light] and/or [dark]: those modes are made from the base colour again ("Regenerate"). */
    suspend fun regenerateCustomColors(light: Boolean, dark: Boolean) = store.edit {
        if (light) it.remove(Keys.customLight)
        if (dark) it.remove(Keys.customDark)
    }
    suspend fun setListMode(value: ListMode) = store.edit { it[Keys.listMode] = value.name }
    suspend fun setTextScale(value: Float) = store.edit { it[Keys.textScale] = value }
    suspend fun setCardColumns(value: Int) = store.edit { it[Keys.cardColumns] = value }
    suspend fun setCardStyle(value: CardLayoutStyle) = store.edit { it[Keys.cardStyle] = value.name }
    suspend fun setShowImages(value: Boolean) = store.edit { it[Keys.showImages] = value }
    suspend fun setCategoryNavigation(value: CategoryNavigation) = store.edit { it[Keys.categoryNavigation] = value.name }
    suspend fun setBrowserMode(value: BrowserMode) = store.edit { it[Keys.browserMode] = value.name }
    suspend fun setRefreshIntervalMinutes(minutes: Int) = store.edit { it[Keys.refreshMinutes] = minutes }
    suspend fun setStartupRefresh(value: Boolean) = store.edit { it[Keys.startupRefresh] = value }
    suspend fun setRetentionLimit(value: Int) = store.edit { it[Keys.retentionLimit] = value }
    suspend fun setImageCacheMb(value: Int) = store.edit { it[Keys.imageCacheMb] = value }
    suspend fun setUnmeteredOnly(value: Boolean) = store.edit { it[Keys.unmeteredOnly] = value }
    suspend fun setAllowHttp(value: Boolean) = store.edit { it[Keys.allowHttp] = value }
    suspend fun setConfirmHttpOpen(value: Boolean) = store.edit { it[Keys.confirmHttpOpen] = value }
    suspend fun setConfirmMeteredRefresh(value: Boolean) = store.edit { it[Keys.confirmMeteredRefresh] = value }
    suspend fun setOnboardingDone(value: Boolean) = store.edit { it[Keys.onboardingDone] = value }
    suspend fun setLastScope(value: ArticleScope) = store.edit { it[Keys.lastScope] = value.encode() }
    suspend fun setLastFilter(value: ReadFilter) = store.edit { it[Keys.lastFilter] = value.name }
    suspend fun setSortOrder(value: SortOrder) = store.edit { it[Keys.sortOrder] = value.name }
    suspend fun setPeriodFilter(value: PeriodFilter) = store.edit { it[Keys.periodFilter] = value.name }
    suspend fun setHiddenKeywordsEnabled(value: Boolean) = store.edit { it[Keys.hiddenKeywordsEnabled] = value }

    /**
     * Adds a hidden keyword (one write, so the list and the switch change together). The first keyword of an empty list also turns the
     * filter on: a switch for a list with nothing in it would mean nothing. The list is read inside the write, so two quick adds both land.
     */
    suspend fun addHiddenKeyword(raw: String): HiddenKeywords.AddResult {
        var result: HiddenKeywords.AddResult = HiddenKeywords.AddResult.Blank
        store.edit { p ->
            val current = HiddenKeywords.decode(p[Keys.hiddenKeywords])
            result = HiddenKeywords.add(current, raw)
            (result as? HiddenKeywords.AddResult.Added)?.let { added ->
                p[Keys.hiddenKeywords] = HiddenKeywords.encode(added.list)
                if (current.isEmpty()) p[Keys.hiddenKeywordsEnabled] = true
            }
        }
        return result
    }

    suspend fun removeHiddenKeyword(keyword: String) = store.edit { p ->
        p[Keys.hiddenKeywords] = HiddenKeywords.encode(HiddenKeywords.remove(HiddenKeywords.decode(p[Keys.hiddenKeywords]), keyword))
    }
    suspend fun setLastRefreshAttempt(at: Long) = store.edit { it[Keys.lastAttempt] = at }

    /** Everything stored, as it is (the restore keeps it to put back if the restore cannot finish). */
    suspend fun rawSnapshot(): Preferences = store.data.first()

    /** Puts back a [rawSnapshot] exactly, in one write. */
    suspend fun replaceRaw(preferences: Preferences) {
        store.updateData { preferences }
    }

    /**
     * Replaces every setting with [settings] (a full restore), in one write: keys this version does not know are dropped, the colours set
     * by hand are removed when [settings] has none. Kept from the device: the time of the last refresh attempt (bookkeeping of this
     * install, not a choice). First-run setup counts as done: the restored app is set up.
     */
    suspend fun replaceForRestore(settings: AppSettings) = store.edit { p ->
        val lastAttempt = p[Keys.lastAttempt]
        p.clear()
        lastAttempt?.let { p[Keys.lastAttempt] = it }
        p[Keys.theme] = settings.theme.name
        p[Keys.palette] = settings.palette.name
        p[Keys.customSeed] = AppSettings.opaque(settings.customSeed)
        settings.customLight?.let { p[Keys.customLight] = it.encode() }
        settings.customDark?.let { p[Keys.customDark] = it.encode() }
        p[Keys.listMode] = settings.listMode.name
        p[Keys.textScale] = settings.textScale
        p[Keys.cardColumns] = settings.cardColumns
        p[Keys.cardStyle] = settings.cardStyle.name
        p[Keys.showImages] = settings.showImages
        p[Keys.categoryNavigation] = settings.categoryNavigation.name
        p[Keys.browserMode] = settings.browserMode.name
        p[Keys.refreshMinutes] = settings.refreshIntervalMinutes
        p[Keys.startupRefresh] = settings.startupRefresh
        p[Keys.retentionLimit] = settings.retentionLimit
        p[Keys.imageCacheMb] = settings.imageCacheMb
        p[Keys.unmeteredOnly] = settings.unmeteredOnly
        p[Keys.allowHttp] = settings.allowHttp
        p[Keys.confirmHttpOpen] = settings.confirmHttpOpen
        p[Keys.confirmMeteredRefresh] = settings.confirmMeteredRefresh
        p[Keys.onboardingDone] = true
        p[Keys.lastScope] = settings.lastScope.encode()
        p[Keys.lastFilter] = settings.lastFilter.name
        p[Keys.sortOrder] = settings.sortOrder.name
        p[Keys.periodFilter] = settings.periodFilter.name
        p[Keys.hiddenKeywords] = HiddenKeywords.encode(settings.hiddenKeywords)
        p[Keys.hiddenKeywordsEnabled] = settings.hiddenKeywordsEnabled
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    companion object {
        /**
         * A settings file that cannot be decoded is replaced by the defaults instead of failing every read: otherwise the app
         * would stop at each start and the only way out, clearing the app's storage, would also delete the database.
         * Subscriptions, articles and reading state are in Room and are not affected; HTTP is off again by default.
         */
        val corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
    }
}
