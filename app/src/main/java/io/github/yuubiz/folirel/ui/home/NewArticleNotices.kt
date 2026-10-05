package io.github.yuubiz.folirel.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The articles the new-article banner announces. Finished refreshes [announce] what they inserted; the user answering
 * the banner (tapping it, going to the top, dismissing it) [acknowledge]s them in one step, whatever was on screen.
 * Acknowledging is not reading: it keeps no article state, only stops the announcement.
 *
 * An acknowledged id is remembered (bounded) so that a request that finishes later and lists the same article again,
 * which happens when two requests share a feed, never announces it a second time. Articles that really arrive after
 * the acknowledgement are announced as new.
 */
internal class NewArticleNotices(private val maxPending: Int = 5_000, private val maxAcknowledged: Int = 20_000) {
    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** Announced and not yet acknowledged, oldest announcement first. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val acknowledged = LinkedHashSet<String>()

    fun announce(ids: Collection<String>) {
        val fresh = synchronized(acknowledged) { ids.filter { it !in acknowledged } }
        if (fresh.isEmpty()) return
        _pending.update { current -> (current + fresh).toList().takeLast(maxPending).toSet() }
    }

    fun acknowledge(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(acknowledged) {
            acknowledged.addAll(ids)
            while (acknowledged.size > maxAcknowledged) acknowledged.remove(acknowledged.first())
        }
        _pending.update { current -> if (ids.none { it in current }) current else current - ids.toSet() }
    }
}
