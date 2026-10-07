package io.github.yuubiz.folirel.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.backup.BackupProblem
import io.github.yuubiz.folirel.data.backup.RestorePreview
import io.github.yuubiz.folirel.ui.theme.appBarColors
import java.text.DateFormat
import java.util.Date

/**
 * Restore from a full backup: what the archive holds and what it replaces, then an explicit confirmation. Nothing is changed before
 * that; while the restore runs the screen cannot be left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(vm: BackupViewModel, onPickAgain: () -> Unit, onClose: () -> Unit, onFinished: () -> Unit) {
    val state by vm.restore.collectAsStateWithLifecycle()
    val busy = state == RestoreState.Applying || state == RestoreState.Checking
    val close = {
        if (state is RestoreState.Restored) onFinished() else if (!busy) onClose()
    }
    BackHandler { close() }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.restore_title)) },
                navigationIcon = {
                    if (!busy) IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("restore_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                RestoreState.Idle -> OutlinedButton(onClick = onPickAgain) { Text(stringResource(R.string.restore_pick_again)) }
                RestoreState.Checking -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.restore_checking))
                }
                is RestoreState.Refused -> {
                    Text(stringResource(refusedText(s.problem)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("restore_error"))
                    Text(stringResource(R.string.restore_nothing_changed))
                    OutlinedButton(onClick = onPickAgain) { Text(stringResource(R.string.restore_pick_again)) }
                }
                is RestoreState.Preview -> PreviewContent(s.preview, onConfirm = vm::confirm, onCancel = onClose)
                RestoreState.Applying -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.restore_applying))
                }
                is RestoreState.Busy -> {
                    Text(stringResource(R.string.restore_busy), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("restore_busy"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::confirm) { Text(stringResource(R.string.restore_retry)) }
                        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                    }
                }
                is RestoreState.Failed -> {
                    Text(
                        stringResource(if (s.settingsKept) R.string.restore_failed else R.string.restore_failed_settings),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("restore_failed"),
                    )
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
                }
                is RestoreState.Restored -> {
                    Text(stringResource(R.string.restore_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("restore_done"))
                    Text(stringResource(R.string.restore_done_note), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onFinished, modifier = Modifier.testTag("restore_go_home")) { Text(stringResource(R.string.import_go_home)) }
                }
            }
        }
    }
}

private fun refusedText(problem: BackupProblem?): Int = when (problem) {
    BackupProblem.NOT_A_BACKUP -> R.string.restore_refused_not_backup
    BackupProblem.UNSUPPORTED_VERSION -> R.string.restore_refused_version
    BackupProblem.TOO_LARGE -> R.string.restore_refused_too_large
    BackupProblem.UNSAFE_ARCHIVE -> R.string.restore_refused_unsafe
    BackupProblem.DAMAGED -> R.string.restore_refused_damaged
    BackupProblem.INVALID_DATA -> R.string.restore_refused_invalid
    null -> R.string.restore_read_failed
}

@Composable
private fun PreviewContent(p: RestorePreview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val created = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.manifest.createdAt))
    Text(stringResource(R.string.restore_preview_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.restore_created, created, p.manifest.appVersionName, p.manifest.formatVersion),
        style = MaterialTheme.typography.bodySmall,
    )
    Column(Modifier.testTag("restore_preview")) {
        ValueRow(stringResource(R.string.restore_feeds), stringResource(R.string.restore_feeds_value, p.counts.feeds, p.counts.subscribedFeeds, p.unsubscribedFeeds))
        ValueRow(stringResource(R.string.restore_categories), p.counts.categories.toString())
        ValueRow(stringResource(R.string.restore_articles), stringResource(R.string.restore_articles_value, p.counts.articles, p.unreadArticles))
        ValueRow(stringResource(R.string.restore_saved), p.counts.savedArticles.toString())
        ValueRow(stringResource(R.string.restore_hidden_keywords), p.counts.hiddenKeywords.toString())
        ValueRow(
            stringResource(R.string.restore_appearance),
            themeModeName(p.settings.theme) + " · " + paletteName(p.settings.palette.effective(android.os.Build.VERSION.SDK_INT)),
        )
    }
    HorizontalDivider()
    Text(stringResource(R.string.restore_replace_warning), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.restore_current, p.current.feeds, p.current.articles, p.current.savedArticles),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("restore_current"),
    )
    Text(stringResource(R.string.restore_no_refresh_note), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { asking = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.testTag("restore_replace"),
        ) { Text(stringResource(R.string.restore_replace)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("restore_cancel")) { Text(stringResource(R.string.cancel)) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { asking = false; onConfirm() }, modifier = Modifier.testTag("restore_confirm")) {
                    Text(stringResource(R.string.restore_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
