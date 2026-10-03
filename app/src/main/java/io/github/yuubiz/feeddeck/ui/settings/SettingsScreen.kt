package io.github.yuubiz.feeddeck.ui.settings

import android.content.Context
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
import io.github.yuubiz.feeddeck.BuildConfig
import io.github.yuubiz.feeddeck.R
import io.github.yuubiz.feeddeck.app.AppContainer
import io.github.yuubiz.feeddeck.data.db.AppDatabase
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import io.github.yuubiz.feeddeck.data.preferences.BrowserMode
import io.github.yuubiz.feeddeck.data.preferences.SettingsRepository
import io.github.yuubiz.feeddeck.data.preferences.ThemeMode
import io.github.yuubiz.feeddeck.data.repository.ArticleRepository
import io.github.yuubiz.feeddeck.model.ListMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val articles: ArticleRepository,
    private val imageLoader: ImageLoader,
    private val accountId: suspend () -> String,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
    }

    suspend fun articleCount(): Int = articles.countArticles(accountId())

    fun imageCacheBytes(): Long = imageLoader.diskCache?.size ?: 0

    /** Removes cached thumbnails only. Reading state, saved articles and categories are untouched. */
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }
}

fun databaseBytes(context: Context): Long =
    listOf(AppDatabase.NAME, "${AppDatabase.NAME}-wal", "${AppDatabase.NAME}-shm").sumOf { context.getDatabasePath(it).length() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onExport: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var httpWarning by remember { mutableStateOf(false) }
    var cacheVersion by remember { mutableIntStateOf(0) }
    val articleCount by produceState(-1) { value = vm.articleCount() }
    val dbBytes = remember(articleCount) { databaseBytes(context) }
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
                Choice(
                    stringResource(R.string.settings_list_mode),
                    listOf(ListMode.MAGAZINE to stringResource(R.string.list_mode_magazine), ListMode.LIST to stringResource(R.string.list_mode_list)),
                    s.listMode,
                ) { vm.update { setListMode(it) } }
            }
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

            item { Header(stringResource(R.string.settings_update)) }
            item {
                Choice(
                    stringResource(R.string.settings_interval),
                    AppSettings.REFRESH_INTERVALS.map { it to if (it == 0) stringResource(R.string.interval_off) else pluralStringResource(R.plurals.interval_hours, it, it) },
                    s.refreshIntervalHours,
                ) { vm.update { setRefreshInterval(it) } }
            }
            item { Note(stringResource(R.string.settings_interval_note)) }
            item { Toggle(stringResource(R.string.settings_unmetered), stringResource(R.string.settings_unmetered_note), s.unmeteredOnly) { v -> vm.update { setUnmeteredOnly(v) } } }
            item {
                Toggle(stringResource(R.string.settings_allow_http), stringResource(R.string.settings_allow_http_note), s.allowHttp, Modifier.testTag("allow_http")) { v ->
                    if (v) httpWarning = true else vm.update { setAllowHttp(false) }
                }
            }

            item { Header(stringResource(R.string.settings_data)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.settings_article_count, if (articleCount < 0) 0 else articleCount))
                    Text(stringResource(R.string.settings_db_size, Formatter.formatShortFileSize(context, dbBytes)))
                    if (dbBytes > AppContainer.DB_WARNING_BYTES) {
                        Text(stringResource(R.string.settings_db_warning), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.settings_cache_size, Formatter.formatShortFileSize(context, cacheBytes)))
                }
            }
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
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                    Text(stringResource(R.string.settings_local_note), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
