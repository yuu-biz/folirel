package io.github.yuubiz.folirel.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.BuildConfig
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.app.AppContainer
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.data.repository.RetentionPreview
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.ui.common.BrandLogo
import io.github.yuubiz.folirel.ui.common.Choice
import io.github.yuubiz.folirel.ui.common.GroupHeader
import io.github.yuubiz.folirel.ui.common.LayoutPicker
import io.github.yuubiz.folirel.ui.common.SelectionRow
import io.github.yuubiz.folirel.ui.common.SettingNote
import io.github.yuubiz.folirel.ui.common.SwitchRow
import io.github.yuubiz.folirel.ui.home.CardGridLayout
import kotlinx.coroutines.launch

@Composable
internal fun cardStyleName(style: CardLayoutStyle): String =
    stringResource(if (style == CardLayoutStyle.MIXED) R.string.card_style_mixed else R.string.card_style_even)

@Composable
internal fun columnsName(columns: Int): String =
    if (columns == 0) stringResource(R.string.card_columns_auto) else pluralStringResource(R.plurals.card_columns_n, columns, columns)

/**
 * The default layout (P2-C): the layout picker, then only the settings that layout has. The arrangement (Mixed or Even) is the
 * Magazine layout's alone; the maximum columns count for Magazine, Masonry and Bento; a list has neither. A choice that does not
 * apply is not shown, and is kept for when its layout is chosen again. A category can override each of these (home screen display
 * panel); this page is the value it inherits.
 */
@Composable
internal fun LayoutPage(s: AppSettings, vm: SettingsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        SettingNote(stringResource(R.string.settings_display_default_note), Modifier.padding(top = 8.dp))
        GroupHeader(stringResource(R.string.display_layout))
        LayoutPicker(
            selected = s.listMode,
            onSelect = { mode -> if (mode != null) vm.update { setListMode(mode) } },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            tagPrefix = "settings_layout_",
        )
        if (s.listMode == io.github.yuubiz.folirel.model.ListMode.MAGAZINE) {
            GroupHeader(stringResource(R.string.settings_card_style))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Choice(
                    options = CardLayoutStyle.entries,
                    selected = s.cardStyle,
                    label = { cardStyleName(it) },
                    tag = { "settings_style_${it.name}" },
                    onSelect = { style -> vm.update { setCardStyle(style) } },
                )
            }
            SettingNote(stringResource(R.string.layout_style_note))
        } else if (s.listMode.usesColumns()) {
            SettingNote(stringResource(R.string.layout_style_magazine_only), Modifier.padding(top = 12.dp).testTag("settings_style_not_used"))
        }
        if (s.listMode.usesColumns()) {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SelectionRow(
                title = stringResource(R.string.settings_card_columns),
                valueLabel = columnsName(s.cardColumns),
                options = AppSettings.CARD_COLUMNS,
                selected = s.cardColumns,
                optionLabel = { columnsName(it) },
                onSelect = { columns -> vm.update { setCardColumns(columns) } },
                modifier = Modifier.testTag("settings_columns_row"),
                optionTag = { "settings_columns_$it" },
                dialogNote = stringResource(R.string.layout_columns_note),
            )
            CardColumnsNote(s.cardColumns)
        } else {
            SettingNote(stringResource(R.string.layout_options_none), Modifier.padding(top = 12.dp).testTag("settings_layout_options_none"))
        }
        GroupHeader(stringResource(R.string.settings_group_images))
        SwitchRow(
            stringResource(R.string.settings_show_images),
            stringResource(R.string.settings_show_images_note),
            s.showImages,
            Modifier.testTag("show_images"),
        ) { v -> vm.update { setShowImages(v) } }
    }
}

/** What the column choice means on this screen right now, including when the choice is reduced to fit. */
@Composable
private fun CardColumnsNote(preference: Int) {
    val density = LocalDensity.current
    val widthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val columns = CardGridLayout.columns(widthDp, density.fontScale, preference)
    SettingNote(stringResource(R.string.settings_card_columns_note))
    SettingNote(
        stringResource(R.string.settings_card_columns_now, columns.count) +
            if (columns.corrected) " " + stringResource(R.string.settings_card_columns_reduced, preference) else "",
    )
}

/** Opening articles: the browser, and the question before an http:// article opens. Opening never marks an article read. */
@Composable
internal fun OpenPage(s: AppSettings, vm: SettingsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        GroupHeader(stringResource(R.string.settings_browser))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Choice(
                options = BrowserMode.entries,
                selected = s.browserMode,
                label = { stringResource(if (it == BrowserMode.CUSTOM_TABS) R.string.browser_custom_tabs else R.string.browser_external) },
                tag = { "browser_${it.name}" },
                onSelect = { mode -> vm.update { setBrowserMode(mode) } },
            )
        }
        SettingNote(stringResource(R.string.browser_note))
        SettingNote(stringResource(R.string.settings_open_note))
        GroupHeader(stringResource(R.string.settings_group_http))
        SwitchRow(
            stringResource(R.string.settings_confirm_http_open),
            stringResource(R.string.settings_confirm_http_open_note),
            s.confirmHttpOpen,
            Modifier.testTag("confirm_http_open"),
        ) { v -> vm.update { setConfirmHttpOpen(v) } }
    }
}

/**
 * Refresh and network: when the app updates by itself, on which networks, the question before a manual update on a metered one, and
 * whether unencrypted HTTP feeds and images are fetched at all. The meaning of each is unchanged; only where it sits is new.
 */
@Composable
internal fun RefreshPage(s: AppSettings, vm: SettingsViewModel, onHttpsCheck: () -> Unit) {
    var httpWarning by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        GroupHeader(stringResource(R.string.settings_group_automatic))
        SelectionRow(
            title = stringResource(R.string.settings_interval),
            valueLabel = intervalName(s.refreshIntervalMinutes),
            options = AppSettings.REFRESH_INTERVALS_MINUTES,
            selected = s.refreshIntervalMinutes,
            optionLabel = { intervalName(it) },
            onSelect = { minutes -> vm.update { setRefreshIntervalMinutes(minutes) } },
            modifier = Modifier.testTag("interval_row"),
            optionTag = { "interval_$it" },
        )
        SettingNote(stringResource(R.string.settings_interval_note))
        SwitchRow(stringResource(R.string.settings_startup_refresh), stringResource(R.string.settings_startup_refresh_note), s.startupRefresh, Modifier.testTag("startup_refresh")) { v ->
            vm.update { setStartupRefresh(v) }
        }
        SwitchRow(stringResource(R.string.settings_unmetered), stringResource(R.string.settings_unmetered_note), s.unmeteredOnly, Modifier.testTag("unmetered_only")) { v ->
            vm.update { setUnmeteredOnly(v) }
        }
        GroupHeader(stringResource(R.string.settings_group_manual))
        SwitchRow(
            stringResource(R.string.settings_confirm_metered),
            stringResource(R.string.settings_confirm_metered_note),
            s.confirmMeteredRefresh,
            Modifier.testTag("confirm_metered_refresh"),
        ) { v -> vm.update { setConfirmMeteredRefresh(v) } }
        GroupHeader(stringResource(R.string.settings_group_http))
        SwitchRow(stringResource(R.string.settings_allow_http), stringResource(R.string.settings_allow_http_note), s.allowHttp, Modifier.testTag("allow_http")) { v ->
            if (v) httpWarning = true else vm.update { setAllowHttp(false) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            SettingNote(stringResource(R.string.settings_https_note), Modifier.padding(horizontal = 0.dp))
            OutlinedButton(onClick = onHttpsCheck, modifier = Modifier.testTag("settings_https_check")) { Text(stringResource(R.string.https_check_title)) }
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

/**
 * Storage and data: how many articles are kept (a smaller limit is previewed and confirmed before anything is removed), the image
 * cache, and the OPML export. Clearing the image cache never touches articles, read or saved state or categories.
 */
@Composable
internal fun StoragePage(s: AppSettings, vm: SettingsViewModel, snackbar: SnackbarHostState, onExport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    Column(Modifier.fillMaxWidth()) {
        GroupHeader(stringResource(R.string.settings_group_articles))
        SelectionRow(
            title = stringResource(R.string.settings_retention),
            valueLabel = if (s.retentionLimit == 0) stringResource(R.string.retention_unlimited) else stringResource(R.string.retention_count, s.retentionLimit),
            options = AppSettings.RETENTION_LIMITS,
            selected = s.retentionLimit,
            optionLabel = { if (it == 0) stringResource(R.string.retention_unlimited) else stringResource(R.string.retention_count, it) },
            onSelect = { limit ->
                if (limit == 0 || (s.retentionLimit in 1..limit)) {
                    // No limit, or a looser limit: nothing would be removed beyond what the current one keeps.
                    vm.update { setRetentionLimit(limit) }
                } else {
                    scope.launch { retentionAsk = vm.retentionPreview(limit) }
                }
            },
            modifier = Modifier.testTag("retention_row"),
            optionTag = { "retention_$it" },
        )
        SettingNote(stringResource(R.string.settings_retention_note))
        retentionPreview?.takeIf { it.overLimitBecauseProtected }?.let { p ->
            SettingNote(stringResource(R.string.retention_over_limit, p.total, p.protectedCount))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.settings_article_count, if (articleCount < 0) 0 else articleCount))
            Text(stringResource(R.string.settings_db_size, Formatter.formatShortFileSize(context, dbBytes)))
            if (dbBytes > AppContainer.DB_WARNING_BYTES) {
                Text(stringResource(R.string.settings_db_warning), color = MaterialTheme.colorScheme.error)
            }
        }

        GroupHeader(stringResource(R.string.settings_group_images))
        SelectionRow(
            title = stringResource(R.string.settings_image_cache),
            valueLabel = stringResource(R.string.cache_mb, s.imageCacheMb),
            options = AppSettings.IMAGE_CACHE_MB,
            selected = s.imageCacheMb,
            optionLabel = { stringResource(R.string.cache_mb, it) },
            onSelect = { mb -> vm.update { setImageCacheMb(mb) } },
            modifier = Modifier.testTag("image_cache_row"),
            optionTag = { "image_cache_$it" },
        )
        SettingNote(stringResource(R.string.settings_image_cache_note))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.settings_cache_size, Formatter.formatShortFileSize(context, cacheBytes), s.imageCacheMb))
            Row(Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        vm.clearImageCache()
                        cacheVersion++
                        snackbar.showSnackbar(cacheCleared)
                    }
                }, modifier = Modifier.testTag("clear_image_cache")) { Text(stringResource(R.string.settings_clear_cache)) }
            }
        }

        GroupHeader(stringResource(R.string.settings_group_backup))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.testTag("export_opml")) { Text(stringResource(R.string.action_export_opml)) }
            SettingNote(stringResource(R.string.settings_export_note), Modifier.padding(horizontal = 0.dp))
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
                        // A limit that could not be stored removed nothing; the screen already says that the setting was not saved.
                        if (removed != null) snackbar.showSnackbar(removedText.format(removed))
                    }
                }, modifier = Modifier.testTag("retention_confirm")) { Text(stringResource(R.string.retention_confirm)) }
            },
            dismissButton = { TextButton(onClick = { retentionAsk = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun AboutPage() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Fixed brand picture (never tinted or taken from the colour set); small, so the version below stays in view at large text.
        BrandLogo(Modifier.testTag("about_logo").padding(bottom = 12.dp))
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT), modifier = Modifier.testTag("about_version"))
        Text(stringResource(R.string.settings_local_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
