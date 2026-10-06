package io.github.yuubiz.folirel.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import io.github.yuubiz.folirel.ui.theme.appBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.HttpsCheck
import io.github.yuubiz.folirel.model.HttpsReason
import io.github.yuubiz.folirel.model.HttpsVerdict

/**
 * "Check HTTPS support of HTTP feeds": one consent for all http:// feeds, then a background check with progress,
 * cancel and a result list. Leaving the screen does not stop it. Allowing HTTP for the rest is a separate setting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HttpsUpgradeScreen(vm: SubscriptionsViewModel, onBack: () -> Unit) {
    val state by vm.httpsState.collectAsStateWithLifecycle()
    val candidates by produceState(-1, state.finished, state.running) { value = vm.httpCandidateCount() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.https_check_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.https_check_explain), style = MaterialTheme.typography.bodyMedium)
            }
            when {
                state.running -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("https_progress")) {
                        Text(stringResource(R.string.https_checking, state.checked, state.total))
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { state.checked.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(stringResource(R.string.https_background_note), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = vm::cancelHttpsUpgrade, modifier = Modifier.testTag("https_cancel")) { Text(stringResource(R.string.cancel)) }
                    }
                }
                state.finished -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("https_result")) {
                        Text(
                            stringResource(
                                R.string.https_result_summary,
                                state.count(HttpsVerdict.CHANGED),
                                state.count(HttpsVerdict.UNCHANGED),
                                state.count(HttpsVerdict.UNCHECKED),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.cancelled) Text(stringResource(R.string.https_cancelled_note), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.https_remaining_note), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (candidates > 0) OutlinedButton(onClick = vm::startHttpsUpgrade) { Text(stringResource(R.string.https_check_again)) }
                            TextButton(onClick = vm::dismissHttpsUpgrade) { Text(stringResource(R.string.https_clear_result)) }
                        }
                    }
                }
                else -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (candidates < 0) "" else stringResource(R.string.https_candidates, candidates),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Button(onClick = vm::startHttpsUpgrade, enabled = candidates > 0, modifier = Modifier.testTag("https_start")) {
                            Text(stringResource(R.string.https_check_and_change))
                        }
                    }
                }
            }
            if (state.results.isNotEmpty()) {
                val groups = listOf(HttpsVerdict.CHANGED, HttpsVerdict.UNCHANGED, HttpsVerdict.UNCHECKED)
                for (verdict in groups) {
                    val rows = state.results.filter { it.verdict == verdict }
                    if (rows.isEmpty()) continue
                    item {
                        HorizontalDivider()
                        Text(verdictLabel(verdict) + " (${rows.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(rows, key = { "${verdict.name}-${it.feedId}" }) { HttpsResultRow(it) }
                }
            }
        }
    }
}

/** The consent dialog shown after an OPML import that left http:// feeds. */
@Composable
fun HttpsConsentDialog(count: Int, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.https_check_title)) },
        text = { Text(stringResource(R.string.https_consent_message, count)) },
        confirmButton = { TextButton(onClick = onAccept, modifier = Modifier.testTag("https_consent_accept")) { Text(stringResource(R.string.https_check_and_change)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.https_not_now)) } },
    )
}

@Composable
private fun HttpsResultRow(check: HttpsCheck) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(check.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(httpsReasonLabel(check.reason), check.httpStatus?.let { "HTTP $it" }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (check.verdict == HttpsVerdict.CHANGED) check.httpsUrl.orEmpty() else check.httpUrl,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun verdictLabel(verdict: HttpsVerdict): String = stringResource(
    when (verdict) {
        HttpsVerdict.CHANGED -> R.string.https_verdict_changed
        HttpsVerdict.UNCHANGED -> R.string.https_verdict_unchanged
        HttpsVerdict.UNCHECKED -> R.string.https_verdict_unchecked
    },
)

@Composable
fun httpsReasonLabel(reason: HttpsReason): String = stringResource(
    when (reason) {
        HttpsReason.OK -> R.string.https_reason_ok
        HttpsReason.HTML_PAGE -> R.string.https_reason_html
        HttpsReason.NOT_A_FEED -> R.string.https_reason_not_feed
        HttpsReason.TLS_ERROR -> R.string.https_reason_tls
        HttpsReason.DOWNGRADE_TO_HTTP -> R.string.https_reason_downgrade
        HttpsReason.DIFFERENT_FEED -> R.string.https_reason_different
        HttpsReason.REDIRECT_UNCONFIRMED -> R.string.https_reason_redirect_unconfirmed
        HttpsReason.HTTP_STATUS -> R.string.https_reason_status
        HttpsReason.BLOCKED -> R.string.https_reason_blocked
        HttpsReason.TOO_LARGE -> R.string.https_reason_too_large
        HttpsReason.TIMEOUT -> R.string.https_reason_timeout
        HttpsReason.NETWORK -> R.string.https_reason_network
        HttpsReason.SERVER_BUSY -> R.string.https_reason_busy
        HttpsReason.CANCELLED -> R.string.https_reason_cancelled
        HttpsReason.SKIPPED -> R.string.https_reason_skipped
    },
)
