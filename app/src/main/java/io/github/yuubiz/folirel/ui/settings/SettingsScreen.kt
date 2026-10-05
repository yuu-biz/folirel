package io.github.yuubiz.folirel.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import io.github.yuubiz.folirel.BuildConfig
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.app.AppContainer
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.data.repository.ArticleRepository
import io.github.yuubiz.folirel.data.repository.RetentionManager
import io.github.yuubiz.folirel.data.repository.RetentionPreview
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.home.CardGridLayout
import kotlinx.coroutines.Dispatchers
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

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onExport: () -> Unit, onHttpsCheck: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var httpWarning by remember { mutableStateOf(false) }
    var cacheVersion by remember { mutableIntStateOf(0) }
    var retentionAsk by remember { mutableStateOf<RetentionPreview?>(null) }
    var dataVersion by remember { mutableIntStateOf(0) }
    val articleCount by produceState(-1, dataVersion) { value = vm.articleCount() }
    val retentionPreview by produceState<RetentionPreview?>(null, dataVersion, s.retentionLimit) {
        value = if (s.retentionLimit > 0) vm.retentionPreview(s.retentionLimit) else null
    }
    val removedText = stringResource(R.string.retention_removed)
    val dbBytes = remember(articleCount) { vm.databaseBytes() }
    val cacheBytes = remember(cacheVersion) { vm.imageCacheBytes() }
    val cacheCleared = stringResource(R.string.settings_cache_cleared)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { Header(stringResource(R.string.settings_display)) }
            item {
                Choice(
                    stringResource(R.string.settings_theme),
                    listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
                    ),
                    s.theme,
                ) { vm.update { setTheme(it) } }
            }
            item {
                Text(
                    stringResource(R.string.settings_display_default_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Choice(
                    stringResource(R.string.settings_list_mode),
                    listOf(
                        ListMode.LIST to stringResource(R.string.list_mode_list),
                        ListMode.COMPACT_LIST to stringResource(R.string.list_mode_compact_list),
                        ListMode.MAGAZINE to stringResource(R.string.list_mode_magazine),
                    ),
                    s.listMode,
                ) { vm.update { setListMode(it) } }
            }
            item {
                Choice(
                    stringResource(R.string.settings_card_style),
                    listOf(
                        CardLayoutStyle.MIXED to stringResource(R.string.card_style_mixed),
                        CardLayoutStyle.EVEN to stringResource(R.string.card_style_even),
                    ),
                    s.cardStyle,
                ) { vm.update { setCardStyle(it) } }
            }
            item {
                Choice(
                    stringResource(R.string.settings_card_columns),
                    AppSettings.CARD_COLUMNS.map { it to if (it == 0) stringResource(R.string.card_columns_auto) else pluralStringResource(R.plurals.card_columns_n, it, it) },
                    s.cardColumns,
                ) { vm.update { setCardColumns(it) } }
            }
            item { CardColumnsNote(s.cardColumns) }
            item {
                Choice(
                    stringResource(R.string.settings_text_size),
                    AppSettings.TEXT_SCALES.map { it to "×$it" },
                    s.textScale,
                ) { vm.update { setTextScale(it) } }
            }
            item { Toggle(stringResource(R.string.settings_show_images), stringResource(R.string.settings_show_images_note), s.showImages) { v -> vm.update { setShowImages(v) } } }

            item { Header(stringResource(R.string.settings_open)) }
            item {
                Choice(
                    stringResource(R.string.settings_browser),
                    listOf(BrowserMode.CUSTOM_TABS to stringResource(R.string.browser_custom_tabs), BrowserMode.EXTERNAL to stringResource(R.string.browser_external)),
                    s.browserMode,
                ) { vm.update { setBrowserMode(it) } }
            }
            item { Note(stringResource(R.string.settings_open_note)) }
            item {
                Toggle(stringResource(R.string.settings_confirm_http_open), stringResource(R.string.settings_confirm_http_open_note), s.confirmHttpOpen, Modifier.testTag("confirm_http_open")) { v ->
                    vm.update { setConfirmHttpOpen(v) }
                }
            }

            item { Header(stringResource(R.string.settings_update)) }
            item {
                Choice(
                    stringResource(R.string.settings_interval),
                    AppSettings.REFRESH_INTERVALS_MINUTES.map { it to intervalLabel(it) },
                    s.refreshIntervalMinutes,
                ) { vm.update { setRefreshIntervalMinutes(it) } }
            }
            item { Note(stringResource(R.string.settings_interval_note)) }
            item {
                Toggle(stringResource(R.string.settings_startup_refresh), stringResource(R.string.settings_startup_refresh_note), s.startupRefresh) { v ->
                    vm.update { setStartupRefresh(v) }
                }
            }
            item { Toggle(stringResource(R.string.settings_unmetered), stringResource(R.string.settings_unmetered_note), s.unmeteredOnly) { v -> vm.update { setUnmeteredOnly(v) } } }
            item {
                Toggle(stringResource(R.string.settings_allow_http), stringResource(R.string.settings_allow_http_note), s.allowHttp, Modifier.testTag("allow_http")) { v ->
                    if (v) httpWarning = true else vm.update { setAllowHttp(false) }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Note(stringResource(R.string.settings_https_note))
                    OutlinedButton(onClick = onHttpsCheck, modifier = Modifier.testTag("settings_https_check")) { Text(stringResource(R.string.https_check_title)) }
                }
            }

            item { Header(stringResource(R.string.settings_data)) }
            item {
                Choice(
                    stringResource(R.string.settings_retention),
                    AppSettings.RETENTION_LIMITS.map { it to if (it == 0) stringResource(R.string.retention_unlimited) else stringResource(R.string.retention_count, it) },
                    s.retentionLimit,
                ) { limit ->
                    if (limit == 0 || (s.retentionLimit in 1..limit)) {
                        // No limit, or a looser limit: nothing would be removed beyond what the current one keeps.
                        vm.update { setRetentionLimit(limit) }
                    } else {
                        scope.launch { retentionAsk = vm.retentionPreview(limit) }
                    }
                }
            }
            item { Note(stringResource(R.string.settings_retention_note)) }
            retentionPreview?.takeIf { it.overLimitBecauseProtected }?.let { p ->
                item { Note(stringResource(R.string.retention_over_limit, p.total, p.protectedCount)) }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.settings_article_count, if (articleCount < 0) 0 else articleCount))
                    Text(stringResource(R.string.settings_db_size, Formatter.formatShortFileSize(context, dbBytes)))
                    if (dbBytes > AppContainer.DB_WARNING_BYTES) {
                        Text(stringResource(R.string.settings_db_warning), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.settings_cache_size, Formatter.formatShortFileSize(context, cacheBytes), s.imageCacheMb))
                }
            }
            item {
                Choice(
                    stringResource(R.string.settings_image_cache),
                    AppSettings.IMAGE_CACHE_MB.map { it to stringResource(R.string.cache_mb, it) },
                    s.imageCacheMb,
                ) { vm.update { setImageCacheMb(it) } }
            }
            item { Note(stringResource(R.string.settings_image_cache_note)) }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            vm.clearImageCache()
                            cacheVersion++
                            snackbar.showSnackbar(cacheCleared)
                        }
                    }) { Text(stringResource(R.string.settings_clear_cache)) }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(onClick = onExport) { Text(stringResource(R.string.action_export_opml)) }
                    Note(stringResource(R.string.settings_export_note))
                }
            }

            item { Header(stringResource(R.string.settings_about)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT))
                    Text(stringResource(R.string.settings_local_note), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    retentionAsk?.let { p ->
        AlertDialog(
            onDismissRequest = { retentionAsk = null },
            title = { Text(stringResource(R.string.retention_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.retention_confirm_message, p.limit, p.total, p.toRemove, p.protectedCount) +
                        if (p.overLimitBecauseProtected) "\n" + stringResource(R.string.retention_over_limit, p.total - p.toRemove, p.protectedCount) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    retentionAsk = null
                    scope.launch {
                        val removed = vm.confirmRetention(p.limit)
                        dataVersion++
                        snackbar.showSnackbar(removedText.format(removed))
                    }
                }, modifier = Modifier.testTag("retention_confirm")) { Text(stringResource(R.string.retention_confirm)) }
            },
            dismissButton = { TextButton(onClick = { retentionAsk = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (httpWarning) {
        AlertDialog(
            onDismissRequest = { httpWarning = false },
            title = { Text(stringResource(R.string.http_allow_title)) },
            text = { Text(stringResource(R.string.http_allow_message)) },
            confirmButton = {
                TextButton(onClick = { httpWarning = false; vm.update { setAllowHttp(true) } }, modifier = Modifier.testTag("allow_http_confirm")) {
                    Text(stringResource(R.string.http_allow_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { httpWarning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Header(text: String) {
    HorizontalDivider()
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Composable
private fun Toggle(title: String, note: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title)
        options.forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) },
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.interval_off)
    minutes < 60 -> pluralStringResource(R.plurals.interval_minutes, minutes, minutes)
    else -> pluralStringResource(R.plurals.interval_hours, minutes / 60, minutes / 60)
}

/** What the column choice means on this screen right now, including when the choice is reduced to fit. */
@Composable
private fun CardColumnsNote(preference: Int) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthDp = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp().value }
    val fontScale = density.fontScale
    val columns = CardGridLayout.columns(widthDp, fontScale, preference)
    Note(stringResource(R.string.settings_card_columns_note))
    Note(
        stringResource(R.string.settings_card_columns_now, columns.count) +
            if (columns.corrected) " " + stringResource(R.string.settings_card_columns_reduced, preference) else "",
    )
}
