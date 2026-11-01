package io.github.yuubiz.folirel.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One message at a time: a new one replaces the one on screen instead of waiting until it has timed out. A replaced message counts
 * as dismissed, so its Undo is gone.
 */
class LatestSnackbar(val host: SnackbarHostState, private val scope: CoroutineScope) {
    private var shown: Job? = null

    fun show(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        shown?.cancel()
        shown = scope.launch {
            val duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long
            if (host.showSnackbar(message, actionLabel, duration = duration) == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

@Composable
fun rememberLatestSnackbar(): LatestSnackbar {
    val scope = rememberCoroutineScope()
    return remember { LatestSnackbar(SnackbarHostState(), scope) }
}
