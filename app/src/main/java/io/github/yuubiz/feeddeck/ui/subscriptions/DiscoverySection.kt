package io.github.yuubiz.feeddeck.ui.subscriptions

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.feeddeck.R
import io.github.yuubiz.feeddeck.model.DiscoveryCandidate
import io.github.yuubiz.feeddeck.model.DiscoveryResult
import io.github.yuubiz.feeddeck.model.FeedFetchInfo
import kotlinx.coroutines.launch

/**
 * "Find where this feed moved" (SUB-DISCOVERY) on the feed detail screen: reads this feed's own site for RSS/Atom
 * links, lists what it found with facts (readable as a feed, shared articles, same title, dates) and lets the user
 * pick one. Nothing is picked or changed automatically. Web search is only a hand-off to the device search (on a tap,
 * with the feed name); a found address can be pasted back to be checked the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverySection(vm: SubscriptionsViewModel, feedId: String, feedFetchUrl: String, info: FeedFetchInfo?) {
    val state by vm.discoveryState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entered by rememberSaveable { mutableStateOf("") }
    var adopting by remember { mutableStateOf<DiscoveryCandidate?>(null) }
    var noSearchApp by remember { mutableStateOf(false) }
    val running = (state as? DiscoveryState.Running)?.feedId == feedId

    Column(Modifier.fillMaxWidth().testTag("discovery_section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.discovery_intro), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.startDiscovery(feedId) }, enabled = !running, modifier = Modifier.testTag("find_moved")) {
                Text(stringResource(R.string.discovery_find))
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val name = vm.searchName(feedId).orEmpty()
                        // The device's own search, started by this tap with the feed name: no search service is built in.
                        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "$name RSS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            noSearchApp = true
                        }
                    }
                },
                modifier = Modifier.testTag("web_search_feed"),
            ) { Text(stringResource(R.string.discovery_web_search)) }
        }
        if (noSearchApp) Text(stringResource(R.string.discovery_no_search_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.discovery_web_search_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = entered,
            onValueChange = { entered = it },
            label = { Text(stringResource(R.string.discovery_enter_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("discovery_entered"),
        )
        OutlinedButton(onClick = { vm.startDiscovery(feedId, entered) }, enabled = entered.isNotBlank() && !running, modifier = Modifier.testTag("check_entered")) {
            Text(stringResource(R.string.discovery_check_entered))
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        val done = state as? DiscoveryState.Done
        if (done != null && done.feedId == feedId) {
            val result = done.result
            if (result == null) {
                Text(stringResource(R.string.discovery_failed), color = MaterialTheme.colorScheme.error)
            } else {
                Text(pageStatusText(result), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("discovery_status"))
                if (result.pageUrl != null) Text(result.pageUrl, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                result.candidates.forEach { candidate -> CandidateCard(candidate, onUse = { adopting = candidate }) }
                if (result.candidates.isEmpty() && result.page in setOf(DiscoveryResult.PageStatus.READ, DiscoveryResult.PageStatus.FEED_ENTERED)) {
                    Text(stringResource(R.string.discovery_none_found), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = vm::clearDiscovery) { Text(stringResource(R.string.discovery_close)) }
            }
        }
        info?.urlMoves?.takeIf { it.isNotEmpty() }?.let { moves ->
            Text(stringResource(R.string.discovery_history), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            moves.forEach { m ->
                Text(
                    (formatTime(context, m.movedAt) ?: "") + "\n" + m.fromUrl + "\n→ " + m.toUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    adopting?.let { candidate ->
        AlertDialog(
            onDismissRequest = { adopting = null },
            title = { Text(stringResource(R.string.discovery_adopt_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.discovery_adopt_from, feedFetchUrl), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.discovery_adopt_to, candidate.url), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.discovery_adopt_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.applyMove(feedId, feedFetchUrl, candidate.url); adopting = null }, modifier = Modifier.testTag("adopt_confirm")) {
                    Text(stringResource(R.string.discovery_adopt_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { adopting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun pageStatusText(result: DiscoveryResult): String = stringResource(
    when (result.page) {
        DiscoveryResult.PageStatus.READ -> R.string.discovery_page_read
        DiscoveryResult.PageStatus.FEED_ENTERED -> R.string.discovery_page_feed
        DiscoveryResult.PageStatus.NO_SITE -> R.string.discovery_page_no_site
        DiscoveryResult.PageStatus.HTTP_NOT_ALLOWED -> R.string.discovery_page_http
        DiscoveryResult.PageStatus.UNREADABLE -> R.string.discovery_page_unreadable
    },
)

@Composable
private fun CandidateCard(candidate: DiscoveryCandidate, onUse: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("candidate_${candidate.url}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(candidate.url, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (!candidate.fetchable) {
            Text(
                stringResource(R.string.discovery_not_fetchable) + (candidate.failure?.let { " · " + errorLabel(it) } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        Text(
            listOfNotNull(candidate.feedTitle, candidate.siteUrl).joinToString(" · ").ifEmpty { stringResource(R.string.untitled) },
            style = MaterialTheme.typography.bodyMedium,
        )
        val range = if (candidate.oldestAt != null && candidate.newestAt != null) {
            " · " + (formatTime(context, candidate.oldestAt) ?: "") + " – " + (formatTime(context, candidate.newestAt) ?: "")
        } else ""
        Text(stringResource(R.string.discovery_entries, candidate.entryCount) + range, style = MaterialTheme.typography.labelMedium)
        val evidence = buildList {
            if (candidate.sharedEntries > 0) add(stringResource(R.string.discovery_shared, candidate.sharedEntries))
            if (candidate.sameTitle) add(stringResource(R.string.discovery_same_title))
            if (isEmpty()) add(stringResource(R.string.discovery_no_evidence))
        }
        Text(evidence.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onUse, modifier = Modifier.testTag("use_candidate")) { Text(stringResource(R.string.discovery_use)) }
    }
}
