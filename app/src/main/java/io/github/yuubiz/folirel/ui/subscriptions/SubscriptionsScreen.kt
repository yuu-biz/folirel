package io.github.yuubiz.folirel.ui.subscriptions

import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import io.github.yuubiz.folirel.ui.home.SourceIcon
import io.github.yuubiz.folirel.model.FeedProblem
import io.github.yuubiz.folirel.model.FeedListFilter
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.model.FeedDiagnostics
import io.github.yuubiz.folirel.model.FeedNode
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.Structure

/** Collects one-shot events into a snackbar. */
@Composable
fun SubscriptionEvents(vm: SubscriptionsViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val texts = mapOf(
        "added" to stringResource(R.string.msg_feed_added),
        "added_http" to stringResource(R.string.msg_feed_added_http),
        "resub" to stringResource(R.string.msg_feed_resubscribed),
        "exists" to stringResource(R.string.msg_feed_exists),
        "invalid" to stringResource(R.string.msg_feed_invalid),
        "export_ok" to stringResource(R.string.msg_export_done),
        "export_ng" to stringResource(R.string.msg_export_failed),
        "conflict" to stringResource(R.string.msg_category_conflict),
        "cat_invalid" to stringResource(R.string.msg_category_invalid),
        "failed" to stringResource(R.string.msg_update_failed),
        "move_ok" to stringResource(R.string.msg_move_applied),
        "move_stale" to stringResource(R.string.msg_move_stale),
    )
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(vm, context) {
        vm.eventFlow.collect { event ->
            if (event is SubscriptionEvent.BulkUnsubscribed) {
                val n = event.ids.size
                val message = buildString {
                    append(if (n == 0) resources.getString(R.string.msg_bulk_nothing_changed) else resources.getQuantityString(R.plurals.msg_bulk_unsubscribed, n, n))
                    if (event.skippedRecovered > 0) append(" ").append(resources.getQuantityString(R.plurals.msg_bulk_kept_recovered, event.skippedRecovered, event.skippedRecovered))
                }
                // The undo restores exactly the feeds this action changed.
                val result = snackbar.showSnackbar(message, actionLabel = if (n > 0) undoLabel else null, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) vm.undoBulkUnsubscribe(event.ids)
                return@collect
            }
            if (event is SubscriptionEvent.MoveConflict) {
                snackbar.showSnackbar(resources.getString(R.string.msg_move_conflict, event.name))
                return@collect
            }
            val key = when (event) {
                is SubscriptionEvent.FeedAdded -> when (event.result) {
                    is AddFeedResult.Added -> if (event.httpPending) "added_http" else "added"
                    is AddFeedResult.Resubscribed -> "resub"
                    is AddFeedResult.AlreadySubscribed -> "exists"
                    is AddFeedResult.Invalid -> "invalid"
                }
                SubscriptionEvent.ExportDone -> "export_ok"
                SubscriptionEvent.ExportFailed -> "export_ng"
                SubscriptionEvent.CategoryConflict -> "conflict"
                SubscriptionEvent.CategoryInvalid -> "cat_invalid"
                SubscriptionEvent.OperationFailed -> "failed"
                SubscriptionEvent.MoveApplied -> "move_ok"
                SubscriptionEvent.MoveStale -> "move_stale"
                is SubscriptionEvent.BulkUnsubscribed, is SubscriptionEvent.MoveConflict -> "failed"
            }
            snackbar.showSnackbar(texts.getValue(key))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubscriptionsScreen(
    vm: SubscriptionsViewModel,
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onAddFeed: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCategories: () -> Unit,
    onHttpsCheck: () -> Unit,
) {
    val structure by vm.structure.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val refresh by vm.refreshState.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<BulkSnapshot?>(null) }
    val now = remember(structure, diagnostics) { System.currentTimeMillis() }
    val query = search.trim().lowercase()
    val matching = structure.feeds.values
        .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.url.lowercase().contains(query) || it.fetchUrl.lowercase().contains(query) }
        .sortedBy { it.name.lowercase() }
    val (activeAll, inactive) = matching.partition { it.subscribed }
    // The filter narrows the subscribed feeds only; unsubscribed ones stay in their own section.
    val active = activeAll.filter { filter.matches(it, diagnostics[it.id], now) }
    val subscribed = structure.subscribedFeeds
    val httpCount = subscribed.count { it.fetchUrl.startsWith("http://") }
    val unfinished = subscribed.count { f -> !f.paused && (diagnostics[f.id]?.pendingSince != null || f.lastError != null) }
    val filterCounts = FeedListFilter.entries.associateWith { f -> subscribed.count { f.matches(it, diagnostics[it.id], now) } }
    // A selection never keeps feeds that are gone (unsubscribed elsewhere): only ids of subscribed feeds count.
    val selectedIds = selected.filter { structure.feeds[it]?.subscribed == true }

    BackHandler(enabled = selecting) { vm.stopSelecting() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(if (selecting) pluralStringResource(R.plurals.selected_count, selectedIds.size, selectedIds.size) else stringResource(R.string.nav_subscriptions)) },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = vm::stopSelecting, modifier = Modifier.testTag("selection_close")) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_cancel_selection))
                        }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    }
                },
                actions = {
                    if (!selecting) {
                        IconButton(onClick = onAddFeed) { Icon(Icons.Filled.Add, stringResource(R.string.action_add_feed)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_select_feeds)) },
                                onClick = { menu = false; vm.startSelecting() },
                                modifier = Modifier.testTag("menu_select"),
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.categories_title)) }, onClick = { menu = false; onCategories() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.https_check_title)) }, onClick = { menu = false; onHttpsCheck() })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_retry_unfinished)) },
                                enabled = unfinished > 0 && !refresh.running,
                                onClick = { menu = false; vm.retryPending() },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_import_opml)) }, onClick = { menu = false; onImport() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export_opml)) }, onClick = { menu = false; onExport() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = vm::setSearch,
                    label = { Text(stringResource(R.string.subscriptions_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (subscribed.isNotEmpty() && !selecting) {
                item { FetchSummary(subscribed, diagnostics, httpCount, unfinished, refresh.running, onHttpsCheck) { vm.retryPending() } }
            }
            if (subscribed.isNotEmpty()) {
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        FeedListFilter.entries.forEach { f ->
                            val count = filterCounts.getValue(f)
                            // A filter with nothing in it stays visible only while it is the chosen one.
                            if (f == FeedListFilter.ALL || count > 0 || f == filter) {
                                FilterChip(
                                    selected = f == filter,
                                    onClick = { vm.setFilter(f) },
                                    label = { Text(stringResource(filterLabel(f)) + " " + count, maxLines = 1) },
                                    modifier = Modifier.testTag("feed_filter_${f.name}"),
                                )
                            }
                        }
                    }
                }
            }
            if (selecting) {
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { vm.selectAll(active.map { it.id }) }, enabled = active.isNotEmpty(), modifier = Modifier.testTag("select_shown")) {
                            Text(pluralStringResource(R.plurals.select_shown, active.size, active.size))
                        }
                        OutlinedButton(onClick = vm::clearSelected, enabled = selectedIds.isNotEmpty()) { Text(stringResource(R.string.action_clear_selection)) }
                        Button(
                            onClick = { confirm = vm.snapshotOf(selectedIds) },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.testTag("unsubscribe_selected"),
                        ) { Text(pluralStringResource(R.plurals.unsubscribe_selected, selectedIds.size, selectedIds.size)) }
                    }
                }
            }
            if (structure.loaded && structure.feeds.isEmpty()) {
                item {
                    Text(stringResource(R.string.empty_no_subscriptions), modifier = Modifier.padding(16.dp))
                }
            }
            if (structure.loaded && structure.feeds.isNotEmpty() && active.isEmpty() && activeAll.isNotEmpty()) {
                item { Text(stringResource(R.string.filter_nothing_shown), modifier = Modifier.padding(16.dp)) }
            }
            items(active, key = { it.id }) { feed ->
                FeedRow(
                    feed, diagnostics[feed.id], structure, now,
                    selecting = selecting, checked = feed.id in selectedIds,
                    onClick = { if (selecting) vm.toggleSelected(feed.id) else onOpenFeed(feed.id) },
                    onLongClick = { if (!selecting) vm.startSelecting(feed.id) },
                )
            }
            if (inactive.isNotEmpty() && !selecting) {
                item {
                    Text(
                        stringResource(R.string.subscriptions_unsubscribed_header),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(inactive, key = { it.id }) {
                    FeedRow(it, diagnostics[it.id], structure, now, selecting = false, checked = false, onClick = { onOpenFeed(it.id) }, onLongClick = {})
                }
            }
        }
    }
    confirm?.let { snapshot ->
        BulkUnsubscribeDialog(
            snapshot = snapshot,
            structure = structure,
            onConfirm = { vm.confirmBulkUnsubscribe(snapshot); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

private fun filterLabel(filter: FeedListFilter): Int = when (filter) {
    FeedListFilter.ALL -> R.string.feed_filter_all
    FeedListFilter.FAILED -> R.string.feed_filter_failed
    FeedListFilter.NEVER_FETCHED -> R.string.feed_filter_never
    FeedListFilter.HTTP_PENDING -> R.string.feed_filter_http
    FeedListFilter.WAITING -> R.string.feed_filter_waiting
    FeedListFilter.PAUSED -> R.string.feed_filter_paused
}

@Composable
fun problemLabel(problem: FeedProblem): String = stringResource(
    when (problem) {
        FeedProblem.MISSING -> R.string.problem_missing
        FeedProblem.ACCESS -> R.string.problem_access
        FeedProblem.BUSY -> R.string.problem_busy
        FeedProblem.SERVER -> R.string.problem_server
        FeedProblem.TLS -> R.string.problem_tls
        FeedProblem.NETWORK -> R.string.problem_network
        FeedProblem.UNREADABLE -> R.string.problem_unreadable
        FeedProblem.BLOCKED -> R.string.problem_blocked
        FeedProblem.OTHER -> R.string.problem_other
    },
)

/**
 * Confirmation for the fixed [snapshot]. It never changes which feeds it is about; it only shows live whether a feed
 * with a problem has recovered since it was selected (those are kept subscribed). Nothing here says a site is
 * closed: a failure is not proof.
 */
@Composable
private fun BulkUnsubscribeDialog(snapshot: BulkSnapshot, structure: Structure, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recovered = snapshot.items.filter { it.problem != null && structure.feeds[it.id]?.problem == null }
    val count = snapshot.items.size - recovered.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.bulk_title, count, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.items.take(BULK_LIST_LIMIT).forEach { item ->
                    val gone = item in recovered
                    Column(Modifier.testTag("bulk_item_${item.id}")) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val details = buildList {
                            if (gone) add(stringResource(R.string.bulk_recovered))
                            else item.problem?.let { add(problemLabel(it)) }
                            item.httpStatus?.takeIf { item.problem != null && !gone }?.let { add("HTTP $it") }
                            if (item.failureCount > 0 && !gone) add(pluralStringResource(R.plurals.bulk_failures, item.failureCount, item.failureCount))
                            add(
                                formatTime(context, item.lastSuccessAt)?.let { stringResource(R.string.bulk_last_success, it) }
                                    ?: stringResource(R.string.bulk_never_succeeded),
                            )
                        }
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (snapshot.items.size > BULK_LIST_LIMIT) {
                    Text(pluralStringResource(R.plurals.bulk_more, snapshot.items.size - BULK_LIST_LIMIT, snapshot.items.size - BULK_LIST_LIMIT), style = MaterialTheme.typography.labelMedium)
                }
                if (recovered.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.bulk_recovered_note, recovered.size, recovered.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("bulk_recovered_note"),
                    )
                }
                Text(stringResource(R.string.bulk_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = count > 0, modifier = Modifier.testTag("bulk_confirm")) {
                Text(pluralStringResource(R.plurals.unsubscribe_selected, count, count))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("bulk_cancel")) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val BULK_LIST_LIMIT = 8

/** Counts by last result, so "many feeds not fetched" has a visible reason (SYNC-04). */
@Composable
private fun FetchSummary(
    feeds: List<FeedNode>,
    diagnostics: Map<String, FeedDiagnostics>,
    httpCount: Int,
    unfinished: Int,
    running: Boolean,
    onHttpsCheck: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = feeds.filter { !it.paused }
    val results = active.map { diagnostics[it.id]?.result }
    val ok = results.count { it == FeedResult.UPDATED || it == FeedResult.NOT_MODIFIED }
    val failed = active.count { it.lastError != null }
    val httpHeld = results.count { it == FeedResult.HTTP_DISABLED }
    val waiting = results.count { it == FeedResult.BACKOFF || it == FeedResult.OFFLINE }
    val notDone = results.count { it != null && it.unfinished }
    val neverFetched = active.count { !it.initialized }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("fetch_summary"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.summary_counts, active.size, ok, failed, httpHeld, waiting, notDone),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (neverFetched > 0) Text(stringResource(R.string.summary_never_fetched, neverFetched), style = MaterialTheme.typography.bodySmall)
            if (httpCount > 0) {
                Text(stringResource(R.string.summary_http, httpCount), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onHttpsCheck, modifier = Modifier.testTag("https_check_open")) { Text(stringResource(R.string.https_check_title)) }
            }
            if (unfinished > 0) {
                OutlinedButton(onClick = onRetry, enabled = !running) { Text(stringResource(R.string.action_retry_unfinished_count, unfinished)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(
    feed: FeedNode,
    diagnostics: FeedDiagnostics?,
    structure: Structure,
    now: Long,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("feed_row_${feed.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.testTag("feed_check_${feed.id}"))
            Spacer(Modifier.width(8.dp))
        }
        SourceIcon(feed.id, feed.name, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(feed.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val categories = feed.categoryIds.mapNotNull { id -> structure.categories.firstOrNull { it.id == id }?.display }
            val status = feedStatus(feed, diagnostics)
            Text(
                listOfNotNull(feed.fetchUrl.toUri().host, categories.joinToString(", ").ifEmpty { stringResource(R.string.scope_uncategorized) }, status)
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (feed.lastError != null && feed.subscribed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val problem = feed.problem
            if (feed.subscribed && problem != null) {
                // What is known about the failure: kind, HTTP status, how often in a row, last success, next retry.
                val retryAt = maxOf(feed.retryAfterUntil ?: 0L, feed.nextEligibleAt ?: 0L).takeIf { it > now }
                val details = buildList {
                    add(problemLabel(problem))
                    diagnostics?.httpStatus?.let { add("HTTP $it") }
                    if (feed.failureCount > 0) add(pluralStringResource(R.plurals.bulk_failures, feed.failureCount, feed.failureCount))
                    add(formatTime(context, feed.lastSuccessAt)?.let { stringResource(R.string.bulk_last_success, it) } ?: stringResource(R.string.bulk_never_succeeded))
                    retryAt?.let { t -> formatTime(context, t)?.let { add(stringResource(R.string.bulk_retry_after, it)) } }
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}

@Composable
fun feedStatus(feed: FeedNode, diagnostics: FeedDiagnostics? = null): String? {
    val result = diagnostics?.result
    return when {
        !feed.subscribed -> stringResource(R.string.unsubscribed_label)
        feed.paused -> stringResource(R.string.feed_paused)
        result == FeedResult.HTTP_DISABLED -> stringResource(R.string.feed_http_pending)
        result == FeedResult.BACKOFF -> stringResource(R.string.result_backoff)
        result != null && result.unfinished -> resultLabel(result)
        feed.lastError != null -> errorLabel(feed.lastError)
        diagnostics?.pendingSince != null && result == null -> stringResource(R.string.result_queued)
        !feed.initialized && feed.fetchUrl.startsWith("http://") -> stringResource(R.string.feed_http_pending)
        !feed.initialized -> stringResource(R.string.feed_not_fetched)
        else -> null
    }
}

@Composable
fun resultLabel(result: FeedResult): String = stringResource(
    when (result) {
        FeedResult.UPDATED -> R.string.result_updated
        FeedResult.NOT_MODIFIED -> R.string.result_not_modified
        FeedResult.FAILED -> R.string.result_failed
        FeedResult.BACKOFF -> R.string.result_backoff
        FeedResult.HTTP_DISABLED -> R.string.feed_http_pending
        FeedResult.OFFLINE -> R.string.result_offline
        FeedResult.NOT_STARTED -> R.string.result_not_started
        FeedResult.CANCELLED -> R.string.result_cancelled
        FeedResult.CONTINUING -> R.string.result_continuing
        FeedResult.REPLACED -> R.string.result_replaced
        FeedResult.INACTIVE -> R.string.result_inactive
    },
)

@Composable
fun errorLabel(error: FetchError): String = stringResource(
    when (error) {
        FetchError.NETWORK -> R.string.error_network
        FetchError.TIMEOUT -> R.string.error_timeout
        FetchError.HTTP_BLOCKED -> R.string.error_http_blocked
        FetchError.SECURITY -> R.string.error_security
        FetchError.TLS -> R.string.error_tls
        FetchError.ACCESS_DENIED -> R.string.error_access_denied
        FetchError.NOT_FOUND -> R.string.error_not_found
        FetchError.GONE -> R.string.error_gone
        FetchError.RATE_LIMITED -> R.string.error_rate_limited
        FetchError.SERVER -> R.string.error_server
        FetchError.HTTP_STATUS -> R.string.error_http_status
        FetchError.REDIRECT_LOOP, FetchError.TOO_MANY_REDIRECTS -> R.string.error_redirect
        FetchError.FORMAT -> R.string.error_format
        FetchError.NOT_A_FEED -> R.string.error_not_a_feed
        FetchError.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
        FetchError.LIMIT_EXCEEDED -> R.string.error_limit
        FetchError.UNEXPECTED_NOT_MODIFIED -> R.string.error_unexpected_304
        FetchError.STORAGE -> R.string.error_storage
    },
)

@Composable
internal fun LabeledValue(label: String, value: String) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun formatTime(context: android.content.Context, millis: Long?): String? = millis?.let {
    android.text.format.DateUtils.formatDateTime(
        context,
        it,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_YEAR,
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String? = null, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = label?.let { { Text(it) } },
                modifier = Modifier.testTag("text_input"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, modifier = Modifier.testTag("text_input_ok")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(vm: SubscriptionsViewModel, onBack: () -> Unit, initialOrder: Boolean = false) {
    val structure by vm.structure.collectAsStateWithLifecycle()
    // Two views of the same categories: the hierarchy (rename, add, delete) and the order of the tabs (pins, drag).
    var orderMode by rememberSaveable { mutableStateOf(initialOrder) }
    val snackbar = remember { SnackbarHostState() }
    var creatingUnder by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { creatingUnder = null; creating = true }) { Icon(Icons.Filled.Add, stringResource(R.string.category_add)) }
                },
            )
        },
    ) { padding ->
      Column(Modifier.padding(padding).fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = !orderMode,
                onClick = { orderMode = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("categories_mode_tree"),
                label = { Text(stringResource(R.string.categories_mode_tree), maxLines = 1) },
            )
            SegmentedButton(
                selected = orderMode,
                onClick = { orderMode = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("categories_mode_order"),
                label = { Text(stringResource(R.string.categories_mode_order), maxLines = 1) },
            )
        }
        if (orderMode) {
            TabOrderList(vm, onDisplaySettings = {}, modifier = Modifier.weight(1f))
        } else
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (structure.categories.isEmpty()) item { Text(stringResource(R.string.categories_empty), modifier = Modifier.padding(16.dp)) }
            items(structure.categories, key = { it.id }) { category ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = (16 + category.depth * 16).dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(category.path.last())
                        Text(
                            pluralStringResource(R.plurals.category_feed_count, category.subtreeFeedCount, category.subtreeFeedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.category_rename)) }, onClick = { menu = false; renamingId = category.id })
                        DropdownMenuItem(text = { Text(stringResource(R.string.category_add_child)) }, onClick = { menu = false; creatingUnder = category.id; creating = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.category_delete)) }, onClick = { menu = false; deletingId = category.id })
                    }
                }
                HorizontalDivider()
            }
        }
      }
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.category_add),
            initial = "",
            label = creatingUnder?.let { id -> structure.categories.firstOrNull { it.id == id }?.display },
            onConfirm = { vm.createCategory(creatingUnder, it); creating = false },
            onDismiss = { creating = false },
        )
    }
    renamingId?.let { id ->
        val category = structure.categories.firstOrNull { it.id == id }
        TextInputDialog(
            title = stringResource(R.string.category_rename),
            initial = category?.path?.lastOrNull().orEmpty(),
            onConfirm = { vm.renameCategory(id, it); renamingId = null },
            onDismiss = { renamingId = null },
        )
    }
    deletingId?.let { id ->
        val category = structure.categories.firstOrNull { it.id == id }
        val descendants = category?.let { c -> structure.categories.count { it.id != c.id && it.path.size > c.path.size && it.path.subList(0, c.path.size) == c.path } } ?: 0
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.category_delete)) },
            text = { Text(pluralStringResource(R.plurals.category_delete_message, descendants, category?.display.orEmpty(), descendants)) },
            confirmButton = { TextButton(onClick = { vm.deleteCategory(id); deletingId = null }) { Text(stringResource(R.string.category_delete)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun AddFeedDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_feed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.add_feed_url_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("add_feed_url"),
                )
                Text(stringResource(R.string.add_feed_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank(), modifier = Modifier.testTag("add_feed_ok")) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
