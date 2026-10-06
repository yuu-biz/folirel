package io.github.yuubiz.folirel.ui.share

import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ShareCandidate
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.model.ShareProvenance
import io.github.yuubiz.folirel.ui.subscriptions.formatTime

/**
 * The Share to Subscribe flow as a dialog on top of whatever is showing. It is not a route: the back stack, the scope, the filters,
 * the search and the scroll position under it are not touched, and closing it (Cancel, Back, tapping outside) shows them as they were.
 * Nothing here registers a feed except the explicit Subscribe / Subscribe again button of a candidate.
 *
 * [onOpenFeed] opens the details of a feed the user already has; the dialog closes first.
 */
@Composable
fun ShareSubscribeOverlay(controller: ShareSubscriptionController, onOpenFeed: (feedId: String) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ShareState.Idle -> Unit
        is ShareState.Done -> LaunchedEffect(s.id) { controller.acknowledge() }
        is ShareState.ChoosingUrl -> ShareDialog(
            onDismiss = controller::cancel,
            text = {
                Text(stringResource(R.string.share_choose_note), style = MaterialTheme.typography.bodyMedium)
                s.urls.forEach { url ->
                    OutlinedButton(onClick = { controller.choose(url) }, modifier = Modifier.fillMaxWidth().testTag("share_url_$url")) {
                        Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
        is ShareState.Discovering -> ShareDialog(
            onDismiss = controller::cancel,
            text = {
                Text(stringResource(R.string.share_exploring, hostOf(s.url)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share_exploring"))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            },
        )
        is ShareState.Candidates -> {
            val only = s.candidates.singleOrNull()
            ShareDialog(
                onDismiss = controller::cancel,
                // One candidate is confirmed in the dialog's own button row; several are chosen from the list.
                confirm = if (only != null && only.existing?.subscribed != true) {
                    { ActionButton(only, controller) }
                } else null,
                text = {
                    Text(
                        stringResource(if (only != null) R.string.share_found_one else R.string.share_found_many),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.candidates.forEachIndexed { index, candidate ->
                        if (index > 0) HorizontalDivider()
                        CandidateInfo(candidate, controller, onOpenFeed, showAction = only == null)
                    }
                },
            )
        }
        is ShareState.Failed -> ShareDialog(
            onDismiss = controller::cancel,
            dismissLabel = R.string.ok,
            text = { Text(stringResource(problemText(s.problem)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share_problem")) },
        )
        is ShareState.Subscribing -> ShareDialog(
            // A registration being written is not interrupted; the dialog closes by itself when it is done.
            onDismiss = {},
            dismissLabel = null,
            text = {
                Text(stringResource(R.string.share_subscribing), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            },
        )
    }
}

@Composable
private fun ShareDialog(
    onDismiss: () -> Unit,
    text: @Composable () -> Unit,
    confirm: (@Composable () -> Unit)? = null,
    dismissLabel: Int? = R.string.cancel,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { text() }
        },
        confirmButton = { confirm?.invoke() },
        dismissButton = {
            if (dismissLabel != null) TextButton(onClick = onDismiss, modifier = Modifier.testTag("share_cancel")) { Text(stringResource(dismissLabel)) }
        },
        modifier = Modifier.testTag("share_overlay"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateInfo(candidate: ShareCandidate, controller: ShareSubscriptionController, onOpenFeed: (String) -> Unit, showAction: Boolean) {
    val context = LocalContext.current
    val existing = candidate.existing
    Column(Modifier.fillMaxWidth().testTag("share_candidate_${candidate.url}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            candidate.feedTitle ?: candidate.titleOnPage ?: stringResource(R.string.untitled),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(candidate.url, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        candidate.siteUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (candidate.provenance == ShareProvenance.SITE_ROOT) {
            Text(stringResource(R.string.share_from_root), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("share_from_root"))
        }
        val latest = formatTime(context, candidate.newestAt)
        Text(
            pluralStringResource(R.plurals.discovery_entries, candidate.entryCount, candidate.entryCount) +
                (latest?.let { " · " + stringResource(R.string.share_latest, it) } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (existing != null) {
            Text(
                stringResource(if (existing.subscribed) R.string.share_already else R.string.share_unsubscribed_note) + "  ·  " + existing.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(if (existing.subscribed) "share_already" else "share_unsubscribed"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (existing?.subscribed == true) {
                OutlinedButton(
                    onClick = { controller.cancel(); onOpenFeed(existing.feedId) },
                    modifier = Modifier.testTag("share_open_feed"),
                ) { Text(stringResource(R.string.share_open_feed)) }
            } else if (showAction) {
                ActionButton(candidate, controller)
            }
        }
    }
}

@Composable
private fun ActionButton(candidate: ShareCandidate, controller: ShareSubscriptionController) {
    val again = candidate.existing != null
    OutlinedButton(onClick = { controller.subscribe(candidate) }, modifier = Modifier.testTag(if (again) "share_resubscribe" else "share_subscribe")) {
        Text(stringResource(if (again) R.string.share_action_resubscribe else R.string.share_action_subscribe))
    }
}

private fun hostOf(url: String): String = url.toUri().host ?: url

private fun problemText(problem: ShareProblem): Int = when (problem) {
    ShareProblem.NO_URL -> R.string.share_problem_no_url
    ShareProblem.TEXT_TOO_LARGE -> R.string.share_problem_too_large
    ShareProblem.INVALID_URL -> R.string.share_problem_invalid_url
    ShareProblem.NO_FEED -> R.string.share_problem_no_feed
    ShareProblem.FEEDS_UNREADABLE -> R.string.share_problem_feeds_unreadable
    ShareProblem.HTTP_NOT_ALLOWED -> R.string.share_problem_http
    ShareProblem.TLS -> R.string.share_problem_tls
    ShareProblem.NETWORK -> R.string.share_problem_network
    ShareProblem.BLOCKED -> R.string.share_problem_blocked
    ShareProblem.PAGE_STATUS -> R.string.share_problem_status
    ShareProblem.PAGE_TOO_LARGE -> R.string.share_problem_page_large
    ShareProblem.FAILED -> R.string.share_problem_failed
}
