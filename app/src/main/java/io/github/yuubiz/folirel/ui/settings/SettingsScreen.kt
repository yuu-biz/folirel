package io.github.yuubiz.folirel.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import io.github.yuubiz.folirel.BuildConfig
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.ArticleRepository
import io.github.yuubiz.folirel.data.repository.RetentionManager
import io.github.yuubiz.folirel.data.repository.RetentionPreview
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.common.NavRow
import io.github.yuubiz.folirel.ui.common.listModeName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val articles: ArticleRepository,
    private val retention: RetentionManager,
    private val imageLoader: ImageLoader,
    private val accountId: suspend () -> String,
    private val databaseFileBytes: () -> Long,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * Every change a control makes goes through this one queue and is written in the order it was made, so a quick series of taps
     * (colour, light/dark, layout) or a slider released twice always ends with the last choice, never with an earlier one that
     * happened to be written later.
     */
    private val writes = Channel<suspend SettingsRepository.() -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (write in writes) {
                try {
                    repository.write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed write leaves the stored value as it was, and the control shows the stored value: nothing is claimed that is not saved.
                }
            }
        }
    }

    fun update(block: suspend SettingsRepository.() -> Unit) {
        writes.trySend(block)
    }

    suspend fun articleCount(): Int = articles.countArticles(accountId())

    /** What the limit would remove now (nothing is removed by this call). */
    suspend fun retentionPreview(limit: Int): RetentionPreview = retention.preview(accountId(), limit)

    /** The user confirmed the removal count: store the limit and apply it once now. */
    suspend fun confirmRetention(limit: Int): Int {
        repository.setRetentionLimit(limit)
        return retention.apply(accountId(), limit)
    }

    fun imageCacheBytes(): Long = imageLoader.diskCache?.size ?: 0

    fun databaseBytes(): Long = databaseFileBytes()

    /** Removes cached thumbnails only. Reading state, saved articles and categories are untouched. */
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }
}

/** The pages under Settings. The top page lists them with the values they hold; a page is one step below it, never deeper. */
enum class SettingsPage(val route: String, val title: Int) {
    APPEARANCE("settings/appearance", R.string.settings_group_appearance),
    LAYOUT("settings/layout", R.string.settings_group_layout),
    OPEN("settings/open", R.string.settings_open),
    REFRESH("settings/refresh", R.string.settings_group_refresh),
    STORAGE("settings/storage", R.string.settings_group_storage),
    ABOUT("settings/about", R.string.settings_about),
    ;

    companion object {
        const val TOP_ROUTE = "settings"

        fun fromRoute(route: String): SettingsPage? = entries.firstOrNull { it.route == route }
    }
}

/** Settings: the top page (page == null) or one of its pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onHttpsCheck: () -> Unit,
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(page?.title ?: R.string.nav_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).testTag(if (page == null) "settings_top" else "settings_page_${page.name}")) {
            when (page) {
                null -> SettingsTop(s, onOpenPage)
                SettingsPage.APPEARANCE -> AppearancePage(s, vm)
                SettingsPage.LAYOUT -> LayoutPage(s, vm)
                SettingsPage.OPEN -> OpenPage(s, vm)
                SettingsPage.REFRESH -> RefreshPage(s, vm, onHttpsCheck)
                SettingsPage.STORAGE -> StoragePage(s, vm, snackbar, onExport)
                SettingsPage.ABOUT -> AboutPage()
            }
        }
    }
}

@Composable
private fun SettingsTop(s: AppSettings, onOpenPage: (SettingsPage) -> Unit) {
    val dash = " · "
    val appearance = listOf(
        themeModeName(s.theme),
        paletteName(s.palette.effective(android.os.Build.VERSION.SDK_INT)),
        stringResource(R.string.percent_value, AppSettings.textScalePercent(s.textScale)),
    ).joinToString(dash)
    val layout = buildList {
        add(listModeName(s.listMode))
        if (s.listMode == ListMode.MAGAZINE) add(cardStyleName(s.cardStyle))
        if (s.listMode.usesColumns()) add(columnsName(s.cardColumns))
    }.joinToString(dash)
    val open = stringResource(if (s.browserMode == BrowserMode.CUSTOM_TABS) R.string.browser_custom_tabs else R.string.browser_external)
    val refresh = if (s.refreshIntervalMinutes == 0) {
        stringResource(R.string.settings_summary_refresh_off)
    } else {
        intervalName(s.refreshIntervalMinutes) + dash + stringResource(if (s.unmeteredOnly) R.string.settings_summary_unmetered else R.string.settings_summary_any_network)
    }
    val storage = retentionSummary(s.retentionLimit) + dash + stringResource(R.string.settings_summary_images_mb, s.imageCacheMb)
    val about = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT)
    listOf(
        SettingsPage.APPEARANCE to appearance,
        SettingsPage.LAYOUT to layout,
        SettingsPage.OPEN to open,
        SettingsPage.REFRESH to refresh,
        SettingsPage.STORAGE to storage,
        SettingsPage.ABOUT to about,
    ).forEachIndexed { i, (page, summary) ->
        if (i > 0) HorizontalDivider()
        NavRow(stringResource(page.title), summary, { onOpenPage(page) }, Modifier.testTag("settings_row_${page.name}"))
    }
}

@Composable
internal fun intervalName(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.interval_off)
    minutes < 60 -> pluralStringResource(R.plurals.interval_minutes, minutes, minutes)
    else -> pluralStringResource(R.plurals.interval_hours, minutes / 60, minutes / 60)
}

@Composable
internal fun retentionSummary(limit: Int): String =
    if (limit == 0) stringResource(R.string.settings_summary_no_limit) else stringResource(R.string.settings_summary_keep, limit)

/** The layouts that arrange cards in columns: the ones the column setting applies to. */
internal fun ListMode.usesColumns(): Boolean = this == ListMode.MAGAZINE || this == ListMode.MASONRY || this == ListMode.BENTO
