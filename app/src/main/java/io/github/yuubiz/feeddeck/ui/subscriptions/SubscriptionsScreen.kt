package io.github.yuubiz.feeddeck.ui.subscriptions

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.feeddeck.R
import io.github.yuubiz.feeddeck.data.repository.AddFeedResult
import io.github.yuubiz.feeddeck.model.FeedNode
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.Structure
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    )
    LaunchedEffect(vm, context) {
        vm.eventFlow.collect { event ->
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
            }
            snackbar.showSnackbar(texts.getValue(key))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    vm: SubscriptionsViewModel,
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onAddFeed: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCategories: () -> Unit,
) {
    val structure by vm.structure.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    val query = search.trim().lowercase()
    val feeds = structure.feeds.values
        .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.url.lowercase().contains(query) }
        .sortedBy { it.name.lowercase() }
    val (active, inactive) = feeds.partition { it.subscribed }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.nav_subscriptions)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = onAddFeed) { Icon(Icons.Filled.Add, stringResource(R.string.action_add_feed)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.categories_title)) }, onClick = { menu = false; onCategories() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_import_opml)) }, onClick = { menu = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export_opml)) }, onClick = { menu = false; onExport() })
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
            if (structure.loaded && structure.feeds.isEmpty()) {
                item {
                    Text(stringResource(R.string.empty_no_subscriptions), modifier = Modifier.padding(16.dp))
                }
            }
            items(active, key = { it.id }) { FeedRow(it, structure) { onOpenFeed(it.id) } }
            if (inactive.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.subscriptions_unsubscribed_header),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(inactive, key = { it.id }) { FeedRow(it, structure) { onOpenFeed(it.id) } }
            }
        }
    }
}

@Composable
private fun FeedRow(feed: FeedNode, structure: Structure, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("feed_row_${feed.id}"),
    ) {
        Text(feed.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val categories = feed.categoryIds.mapNotNull { id -> structure.categories.firstOrNull { it.id == id }?.display }
        val status = feedStatus(feed)
        Text(
            listOfNotNull(feed.url.toHttpUrlOrNull()?.host, categories.joinToString(", ").ifEmpty { stringResource(R.string.scope_uncategorized) }, status)
                .joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = if (feed.lastError != null && feed.subscribed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider()
}

@Composable
fun feedStatus(feed: FeedNode): String? = when {
    !feed.subscribed -> stringResource(R.string.unsubscribed_label)
    feed.paused -> stringResource(R.string.feed_paused)
    feed.lastError != null -> errorLabel(feed.lastError)
    !feed.initialized && feed.url.startsWith("http://") -> stringResource(R.string.feed_http_pending)
    !feed.initialized -> stringResource(R.string.feed_not_fetched)
    else -> null
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedDetailScreen(vm: SubscriptionsViewModel, feedId: String, onBack: () -> Unit) {
    val structure by vm.structure.collectAsStateWithLifecycle()
    val feed = structure.feeds[feedId]
    val snackbar = remember { SnackbarHostState() }
    val detail by produceState<FeedDetail?>(null, feedId, feed) { value = vm.detail(feedId) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(feed?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        if (feed == null) return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LabeledValue(stringResource(R.string.feed_url), feed.url) }
            detail?.fetchUrl?.takeIf { it != feed.url }?.let { item { LabeledValue(stringResource(R.string.feed_fetch_url), it) } }
            detail?.siteUrl?.let { item { LabeledValue(stringResource(R.string.feed_site_url), it) } }
            item {
                val state = detail?.state
                LabeledValue(stringResource(R.string.feed_last_attempt), formatTime(context, state?.lastAttemptAt) ?: stringResource(R.string.never))
                LabeledValue(stringResource(R.string.feed_last_success), formatTime(context, state?.lastSuccessAt) ?: stringResource(R.string.never))
                LabeledValue(stringResource(R.string.feed_last_error), feed.lastError?.let { errorLabel(it) } ?: stringResource(R.string.none))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.feed_pause), modifier = Modifier.weight(1f))
                    Switch(checked = feed.paused, onCheckedChange = { vm.setPaused(feedId, it) }, enabled = feed.subscribed)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.feed_rename)) }
                    OutlinedButton(onClick = { vm.refreshFeed(feedId) }, enabled = feed.subscribed && !feed.paused) { Text(stringResource(R.string.menu_refresh)) }
                }
            }
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
            initial = detail?.userTitle ?: feed.name,
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
private fun LabeledValue(label: String, value: String) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(context: android.content.Context, millis: Long?): String? = millis?.let {
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
fun CategoriesScreen(vm: SubscriptionsViewModel, onBack: () -> Unit) {
    val structure by vm.structure.collectAsStateWithLifecycle()
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
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
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
