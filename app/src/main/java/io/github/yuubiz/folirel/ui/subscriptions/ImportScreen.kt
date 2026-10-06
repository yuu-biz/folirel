package io.github.yuubiz.folirel.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import io.github.yuubiz.folirel.ui.theme.appBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.backend.local.opml.OpmlRejectedException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: ImportViewModel, onPickAgain: () -> Unit, onClose: () -> Unit, onHttpsCheck: (start: Boolean) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                ImportState.Idle -> {
                    Text(stringResource(R.string.import_intro))
                    Button(onClick = onPickAgain) { Text(stringResource(R.string.import_pick)) }
                }
                ImportState.Reading, ImportState.Committing -> CircularProgressIndicator()
                is ImportState.Preview -> PreviewContent(s, onConfirm = vm::confirm, onCancel = onClose)
                is ImportState.Rejected -> {
                    Text(
                        stringResource(
                            when (s.reason) {
                                OpmlRejectedException.Reason.SECURITY -> R.string.import_rejected_security
                                OpmlRejectedException.Reason.LIMIT_EXCEEDED -> R.string.import_rejected_limit
                                OpmlRejectedException.Reason.NOT_OPML -> R.string.import_rejected_not_opml
                                OpmlRejectedException.Reason.MALFORMED, null -> R.string.import_rejected_malformed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("import_error"),
                    )
                    Text(stringResource(R.string.import_nothing_changed))
                    OutlinedButton(onClick = onPickAgain) { Text(stringResource(R.string.import_pick)) }
                }
                ImportState.ReadFailed -> {
                    Text(stringResource(R.string.import_read_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onPickAgain) { Text(stringResource(R.string.import_pick)) }
                }
                ImportState.CommitFailed -> {
                    Text(stringResource(R.string.import_commit_failed), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.import_nothing_changed))
                }
                is ImportState.Done -> {
                    Text(stringResource(R.string.import_done_title), style = MaterialTheme.typography.titleMedium)
                    CountRow(stringResource(R.string.import_created), s.result.createdFeeds)
                    CountRow(stringResource(R.string.import_resubscribed), s.result.resubscribedFeeds)
                    CountRow(stringResource(R.string.import_memberships), s.result.addedMemberships)
                    CountRow(stringResource(R.string.import_categories_created), s.result.createdCategories)
                    Text(stringResource(R.string.import_fetch_note), style = MaterialTheme.typography.bodyMedium)
                    if (s.httpFeeds > 0) {
                        Text(stringResource(R.string.summary_http, s.httpFeeds), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { onHttpsCheck(false) }) { Text(stringResource(R.string.https_check_title)) }
                    }
                    Button(onClick = onClose, modifier = Modifier.testTag("import_done")) { Text(stringResource(R.string.import_go_home)) }
                    if (s.httpFeeds > 0 && !s.httpsAnswered) {
                        HttpsConsentDialog(
                            count = s.httpFeeds,
                            onAccept = {
                                vm.httpsAnswered()
                                onHttpsCheck(true)
                            },
                            onDismiss = vm::httpsAnswered,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(state: ImportState.Preview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val p = state.preview
    Text(stringResource(R.string.import_preview_title), style = MaterialTheme.typography.titleMedium)
    p.document.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Column(Modifier.testTag("import_preview")) {
        CountRow(stringResource(R.string.import_new_feeds), p.newFeeds)
        CountRow(stringResource(R.string.import_existing_feeds), p.existingFeeds)
        CountRow(stringResource(R.string.import_resubscribe), p.resubscribeFeeds)
        if (p.resubscribeSamples.isNotEmpty()) {
            // Feeds the user unsubscribed (for example a failed-feed cleanup) come back with an old OPML: say so by name.
            val more = if (p.resubscribeFeeds > p.resubscribeSamples.size) {
                stringResource(R.string.import_resubscribe_more, p.resubscribeFeeds - p.resubscribeSamples.size)
            } else null
            Text(
                (listOf(stringResource(R.string.import_resubscribe_note)) + p.resubscribeSamples.map { "· $it" } + listOfNotNull(more))
                    .joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("import_resubscribe_list"),
            )
        }
        CountRow(stringResource(R.string.import_added_memberships), p.addedMemberships)
        CountRow(stringResource(R.string.import_multi_category), p.multiCategoryFeeds)
        CountRow(stringResource(R.string.import_new_categories), p.newCategories)
        CountRow(stringResource(R.string.import_empty_categories), p.emptyCategories)
        CountRow(stringResource(R.string.import_http), p.httpFeeds)
        if (p.httpPending > 0) {
            Text(stringResource(R.string.import_http_pending_note), style = MaterialTheme.typography.bodySmall)
        }
        CountRow(stringResource(R.string.import_invalid), p.invalidUrls)
        CountRow(stringResource(R.string.import_unsupported), p.unsupportedItems)
        if (p.warnings > 0) CountRow(stringResource(R.string.import_warnings), p.warnings)
    }
    HorizontalDivider()
    // The file cannot reveal what Feedly left out of it, so only a general note is shown (no guessed counts).
    Text(stringResource(R.string.import_feedly_note), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onConfirm, enabled = p.totalUniqueFeeds > 0 || p.newCategories > 0, modifier = Modifier.testTag("import_confirm")) {
            Text(stringResource(R.string.import_confirm))
        }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}
