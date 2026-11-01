package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.sync.RefreshState
import io.github.yuubiz.folirel.sync.RequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long the notice of a finished update stays at most (a longer time is used when the system asks for one for accessibility). */
internal const val NOTICE_SHOW_MS = 5_000L

/** Room below the notice for the back-to-top button (40 dp + its 16 dp padding on both sides). */
private val ABOVE_BACK_TO_TOP_BUTTON = 72.dp

/**
 * What the update says on the home screen, drawn over the list so that none of it moves the articles (H02):
 * - while an update of the shown scope runs: a thin progress bar at the top and, at the bottom, what is running;
 * - after one that needs attention: the notice of [RefreshNotice], for a few seconds, with a cross and, if it helps,
 *   "retry" and "details". It is made once per request ([HomeViewModel] keeps it by request id), so neither rotating nor
 *   switching tabs shows it again or makes it last longer;
 * - what stays is not here: the small warning on the subscriptions entry, and the reasons in subscription management.
 *
 * It lives outside the article list's own box, so pressing it is never taken for a touch of the list.
 */
@Composable
internal fun HomeNotices(
    refresh: RefreshState,
    target: RefreshScope,
    structure: Structure,
    checkingHttps: Boolean,
    notice: RefreshNotice?,
    onDismissNotice: (Long) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val own: RequestStatus? = refresh.latestFor(target)?.takeIf { it.running }
    val other = refresh.requests.lastOrNull { it.running && it.scope != target }
    Box(modifier.fillMaxSize()) {
        if (own != null) {
            val p = own.progress
            val bar = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("refresh_progress")
            if (p != null && p.total > 0) LinearProgressIndicator(progress = { p.completed.toFloat() / p.total }, modifier = bar)
            else LinearProgressIndicator(bar)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = ABOVE_BACK_TO_TOP_BUTTON),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (notice != null) NoticeCard(notice, onDismissNotice, onOpenSubscriptions, onRetry)
            val running = runningText(own, other, structure, checkingHttps)
            if (running != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("refresh_running"),
                ) {
                    Text(
                        running,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun runningText(own: RequestStatus?, other: RequestStatus?, structure: Structure, checkingHttps: Boolean): String? {
    val lines = buildList {
        if (own != null) {
            val p = own.progress
            val firstFetch = structure.subscribedFeeds.isNotEmpty() && structure.subscribedFeeds.none { it.initialized }
            add(
                when {
                    firstFetch -> stringResource(R.string.status_first_fetch)
                    p != null && p.total > 0 -> stringResource(R.string.status_refreshing_progress, p.completed, p.total)
                    else -> stringResource(R.string.status_refreshing)
                },
            )
        }
        if (other != null) {
            val p = other.progress
            add(
                if (p != null && p.total > 0) stringResource(R.string.status_other_refresh_progress, p.completed, p.total)
                else stringResource(R.string.status_other_refresh),
            )
        }
        if (checkingHttps) add(stringResource(R.string.status_https_checking))
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun NoticeCard(notice: RefreshNotice, onDismiss: (Long) -> Unit, onOpenSubscriptions: () -> Unit, onRetry: () -> Unit) {
    // The end is fixed by when the update finished, not by when this was drawn: recomposition, rotation and tab changes
    // do not start it again. The system's accessibility timeout may make it longer (never shorter).
    val accessibility = LocalAccessibilityManager.current
    val showFor = accessibility?.calculateRecommendedTimeoutMillis(NOTICE_SHOW_MS, containsIcons = false, containsText = true, containsControls = true) ?: NOTICE_SHOW_MS
    val deadline = notice.finishedAt + showFor
    LaunchedEffect(notice.requestId) {
        val wait = deadline - System.currentTimeMillis()
        // Real time, whatever clock drives the frames (an animation-free test frame clock must not stretch it).
        if (wait > 0) withContext(Dispatchers.Default) { delay(wait) }
        onDismiss(notice.requestId)
    }
    if (deadline <= System.currentTimeMillis()) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("refresh_notice"),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    noticeText(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(top = 8.dp, end = 4.dp).testTag("refresh_notice_text"),
                )
                IconButton(onClick = { onDismiss(notice.requestId) }, modifier = Modifier.testTag("refresh_notice_dismiss")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.notice_dismiss), modifier = Modifier.size(18.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (notice.canRetry) {
                    TextButton(
                        onClick = { onDismiss(notice.requestId); onRetry() },
                        modifier = Modifier.testTag("retry_unfinished"),
                    ) { Text(stringResource(R.string.action_retry_unfinished), color = MaterialTheme.colorScheme.inversePrimary) }
                }
                TextButton(
                    onClick = { onDismiss(notice.requestId); onOpenSubscriptions() },
                    modifier = Modifier.testTag("refresh_notice_details"),
                ) { Text(stringResource(R.string.notice_details), color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

@Composable
private fun noticeText(n: RefreshNotice): String {
    if (n.interrupted) return stringResource(R.string.status_interrupted)
    val parts = buildList {
        if (n.ok > 0) add(stringResource(R.string.status_part_ok, n.ok))
        if (n.failed > 0) add(stringResource(R.string.status_part_failed, n.failed))
        if (n.waiting > 0) add(stringResource(R.string.status_part_waiting, n.waiting))
        if (n.notReached > 0) add(stringResource(R.string.status_part_not_reached, n.notReached))
        if (n.httpPending > 0) add(stringResource(R.string.status_part_http, n.httpPending))
    }
    val lines = buildList {
        if (parts.isNotEmpty()) add(stringResource(R.string.status_result_compact, n.total, parts.joinToString(stringResource(R.string.status_part_separator))))
        if (n.storage > 0) add(pluralStringResource(R.plurals.status_storage_failed, n.storage, n.storage))
        if (n.deferredOffline) add(stringResource(R.string.status_deferred_offline))
    }
    return lines.joinToString("\n")
}
