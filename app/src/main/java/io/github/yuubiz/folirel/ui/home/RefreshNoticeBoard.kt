package io.github.yuubiz.folirel.ui.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Holds the one [RefreshNotice] on show. The end of a notice belongs to its request: it is removed by its own request
 * id, so the timer of an older notice never removes a newer one, and a notice that is already older than [keepMillis]
 * when it is posted (it finished while nobody looked) is never shown. It only forgets the notice: the update's report
 * and the feeds' stored errors are not touched.
 */
internal class RefreshNoticeBoard(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val keepMillis: Long,
) {
    private val _current = MutableStateFlow<RefreshNotice?>(null)
    val current: StateFlow<RefreshNotice?> = _current.asStateFlow()

    fun post(notice: RefreshNotice) {
        val remaining = notice.finishedAt + keepMillis - clock.millis()
        if (remaining <= 0) return
        _current.value = notice
        scope.launch {
            delay(remaining)
            dismiss(notice.requestId)
        }
    }

    /** The notice ran out or was closed. Only the notice of that request: a newer one stays. */
    fun dismiss(requestId: Long) {
        _current.update { if (it?.requestId == requestId) null else it }
    }
}
