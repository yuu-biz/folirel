package io.github.yuubiz.folirel.ui.subscriptions

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import io.github.yuubiz.folirel.ui.theme.appBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.model.FeedDiagnostics
import io.github.yuubiz.folirel.model.FeedFetchInfo
import io.github.yuubiz.folirel.model.FeedNode

/**
 * Feed detail: URLs, what happened on the last request / HTTP attempt / applied body, what is stored, and the
 * actions "refresh this feed" and "revalidate and refetch". Nothing here is sent anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedDetailScreen(vm: SubscriptionsViewModel, feedId: String, onBack: () -> Unit) {
    val structure by vm.structure.collectAsStateWithLifecycle()
    val feed = structure.feeds[feedId]
    val infoFlow = remember(feedId) { vm.feedInfo(feedId) }
    val diagFlow = remember(feedId) { vm.feedDiagnostics(feedId) }
    val info by infoFlow.collectAsStateWithLifecycle(null)
    val diag by diagFlow.collectAsStateWithLifecycle(null)
    val refresh by vm.refreshState.collectAsStateWithLifecycle()
    val running = refresh.requests.any { it.running && it.scope == RefreshScope.Feeds(listOf(feedId)) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                expandedHeight = 56.dp,
                title = { Text(feed?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        if (feed == null) return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LabeledValue(stringResource(R.string.feed_url), feed.url) }
            info?.fetchUrl?.takeIf { it != feed.url }?.let { item { LabeledValue(stringResource(R.string.feed_fetch_url), it) } }
            info?.httpsFromUrl?.let { item { LabeledValue(stringResource(R.string.feed_https_migrated), it) } }
            info?.siteUrl?.let { item { LabeledValue(stringResource(R.string.feed_site_url), it) } }
            item {
                val enabled = feed.subscribed && !feed.paused
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.refreshFeed(feedId) }, enabled = enabled && !running, modifier = Modifier.testTag("refresh_feed")) {
                            Text(stringResource(R.string.action_refresh_this_feed))
                        }
                        OutlinedButton(onClick = { vm.refreshFeed(feedId, force = true) }, enabled = enabled, modifier = Modifier.testTag("refetch_feed")) {
                            Text(stringResource(R.string.action_refetch_revalidate))
                        }
                    }
                    Text(stringResource(R.string.refetch_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!feed.subscribed || feed.paused) {
                        Text(stringResource(R.string.feed_inactive_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { DiagnosticsSection(feed, info, diag) }
            if (feed.subscribed) item { DiscoverySection(vm, feedId, feed.fetchUrl, info) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.feed_pause), modifier = Modifier.weight(1f))
                    Switch(checked = feed.paused, onCheckedChange = { vm.setPaused(feedId, it) }, enabled = feed.subscribed)
                }
            }
            item { OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.feed_rename)) } }
            item { Text(stringResource(R.string.feed_categories), style = MaterialTheme.typography.titleSmall) }
            if (structure.categories.isEmpty()) item { Text(stringResource(R.string.feed_no_categories), style = MaterialTheme.typography.bodySmall) }
            items(structure.categories, key = { it.id }) { category ->
                val checked = category.id in feed.categoryIds
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        vm.setCategories(feedId, if (checked) feed.categoryIds - category.id else feed.categoryIds + category.id)
                    },
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(category.display, modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                HorizontalDivider()
                if (feed.subscribed) {
                    TextButton(onClick = { confirmUnsubscribe = true }, modifier = Modifier.testTag("unsubscribe")) {
                        Text(stringResource(R.string.feed_unsubscribe), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { vm.resubscribe(feedId) }) { Text(stringResource(R.string.feed_resubscribe)) }
                }
                Text(stringResource(R.string.feed_url_change_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (renaming && feed != null) {
        TextInputDialog(
            title = stringResource(R.string.feed_rename),
            initial = info?.userTitle ?: feed.name,
            onConfirm = { vm.rename(feedId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text(stringResource(R.string.feed_unsubscribe)) },
            text = { Text(stringResource(R.string.feed_unsubscribe_message)) },
            confirmButton = { TextButton(onClick = { confirmUnsubscribe = false; vm.unsubscribe(feedId) }) { Text(stringResource(R.string.feed_unsubscribe)) } },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DiagnosticsSection(feed: FeedNode, info: FeedFetchInfo?, d: FeedDiagnostics?) {
    val context = LocalContext.current
    val never = stringResource(R.string.never)
    val unknown = stringResource(R.string.unknown_value)
    fun time(millis: Long?) = formatTime(context, millis) ?: never
    fun num(value: Int?) = value?.toString() ?: unknown
    Column(Modifier.fillMaxWidth().testTag("feed_diagnostics"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(stringResource(R.string.diag_last_request))
        LabeledValue(
            stringResource(R.string.diag_request),
            d?.requestedAt?.let { stringResource(R.string.diag_request_value, time(it), reasonLabel(d.requestReason), scopeLabel(d.requestScope), if (d.requestForce) stringResource(R.string.diag_forced) else "") }
                ?: never,
        )
        val result = d?.result
        LabeledValue(
            stringResource(R.string.diag_result),
            when {
                result != null -> resultLabel(result) + (d.resultAt?.let { " (${time(it)})" } ?: "")
                d?.pendingSince != null -> stringResource(R.string.result_queued)
                else -> unknown
            },
        )
        reasonText(feed, info, d)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        SectionTitle(stringResource(R.string.diag_last_attempt))
        LabeledValue(stringResource(R.string.feed_last_attempt), time(info?.lastAttemptAt))
        LabeledValue(stringResource(R.string.feed_last_success), time(info?.lastSuccessAt))
        if (d?.attemptStartedAt != null) {
            val status = when {
                d.httpStatus != null -> "HTTP ${d.httpStatus}"
                d.attemptFinishedAt == null -> stringResource(R.string.result_running)
                else -> stringResource(R.string.diag_no_response)
            }
            val duration = d.attemptFinishedAt?.let { f -> stringResource(R.string.diag_duration, (f - d.attemptStartedAt).coerceAtLeast(0) / 1000.0) } ?: ""
            LabeledValue(stringResource(R.string.diag_http), listOf(status, duration).filter { it.isNotEmpty() }.joinToString(" · "))
            LabeledValue(stringResource(R.string.diag_conditional), stringResource(if (d.validatorsSent) R.string.yes else R.string.no))
        }
        val error = d?.error ?: feed.lastError
        LabeledValue(
            stringResource(R.string.feed_last_error),
            error?.let { e -> errorLabel(e) + (d?.errorDetail?.let { " (${detailLabel(it)})" } ?: "") } ?: stringResource(R.string.none),
        )
        info?.retryAfterUntil?.takeIf { it > System.currentTimeMillis() }?.let { LabeledValue(stringResource(R.string.diag_retry_after), time(it)) }
            ?: info?.nextEligibleAt?.takeIf { it > System.currentTimeMillis() }?.let { LabeledValue(stringResource(R.string.diag_backoff_until), time(it)) }

        SectionTitle(stringResource(R.string.diag_last_body))
        if (d?.bodyAt == null) {
            Text(stringResource(R.string.diag_no_body), style = MaterialTheme.typography.bodySmall)
        } else {
            LabeledValue(stringResource(R.string.diag_body_time), time(d.bodyAt))
            LabeledValue(stringResource(R.string.diag_body_size), d.bodyBytes?.let { Formatter.formatShortFileSize(context, it) } ?: unknown)
            LabeledValue(stringResource(R.string.diag_entries), stringResource(R.string.diag_entries_value, num(d.rawEntries), num(d.uniqueEntries), num(d.undatedEntries)))
            if (d.oldestPublishedAt != null && d.newestPublishedAt != null) {
                LabeledValue(stringResource(R.string.diag_date_range), "${time(d.oldestPublishedAt)} – ${time(d.newestPublishedAt)}")
            }
            LabeledValue(stringResource(R.string.diag_applied), stringResource(R.string.diag_applied_value, num(d.insertedArticles), num(d.updatedArticles), num(d.skippedPruned)))
        }

        SectionTitle(stringResource(R.string.diag_stored))
        LabeledValue(
            stringResource(R.string.diag_stored_counts),
            info?.let { stringResource(R.string.diag_stored_value, it.storedArticles, it.unreadArticles, it.savedArticles) } ?: unknown,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/** Why this feed currently has no fresh result, in plain words (SYNC-04 steps 1–3). */
@Composable
private fun reasonText(feed: FeedNode, info: FeedFetchInfo?, d: FeedDiagnostics?): String? = when {
    !feed.subscribed || feed.paused -> stringResource(R.string.feed_inactive_note)
    d?.result == FeedResult.HTTP_DISABLED -> stringResource(R.string.reason_http_disabled)
    d?.result == FeedResult.BACKOFF -> stringResource(R.string.reason_backoff)
    d?.result == FeedResult.OFFLINE -> stringResource(R.string.reason_offline)
    d?.result == FeedResult.NOT_STARTED -> stringResource(R.string.reason_not_started)
    d?.result == FeedResult.CANCELLED -> stringResource(R.string.reason_cancelled)
    d?.result == FeedResult.CONTINUING -> stringResource(R.string.reason_continuing)
    info?.lastSuccessAt == null && feed.fetchUrl.startsWith("http://") -> stringResource(R.string.reason_http_disabled)
    else -> null
}

@Composable
private fun reasonLabel(reason: String?): String = stringResource(
    when (reason) {
        "MANUAL" -> R.string.reason_kind_manual
        "IMPORT" -> R.string.reason_kind_import
        "STARTUP" -> R.string.reason_kind_startup
        "PERIODIC" -> R.string.reason_kind_periodic
        else -> R.string.unknown_value
    },
)

@Composable
private fun scopeLabel(scope: String?): String = when {
    scope == null -> stringResource(R.string.unknown_value)
    scope == "all" -> stringResource(R.string.scope_all)
    scope == "uncategorized" -> stringResource(R.string.scope_uncategorized)
    scope == "pending" -> stringResource(R.string.scope_pending)
    scope.startsWith("category:") -> stringResource(R.string.scope_category)
    scope.startsWith("feeds:") -> stringResource(R.string.scope_feeds, scope.removePrefix("feeds:"))
    else -> scope
}

@Composable
private fun detailLabel(detail: String): String = when (detail) {
    "BODY_SIZE" -> stringResource(R.string.limit_body_size)
    "ENTRY_COUNT" -> stringResource(R.string.limit_entry_count)
    "XML_DEPTH" -> stringResource(R.string.limit_xml_depth)
    "XML_ELEMENTS" -> stringResource(R.string.limit_xml_elements)
    "DOCTYPE_RSS091" -> stringResource(R.string.detail_doctype_rss091)
    else -> detail
}
