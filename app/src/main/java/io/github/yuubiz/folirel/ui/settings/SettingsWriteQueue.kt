package io.github.yuubiz.folirel.ui.settings

import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Every change a Settings control makes goes through this one queue and is written in the order it was made, so a quick series of
 * taps (colour, light/dark, layout) or two slider releases always ends with the last choice, never an earlier one that happened to be
 * written later. A write that fails is reported once through [onFailure] (no retry); the stored value is then what it was, and the
 * queue carries on with the next write. The screen shows stored values only, so nothing is shown as saved that is not.
 */
internal class SettingsWriteQueue(
    scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val onFailure: (Exception) -> Unit,
) {
    private val writes = Channel<suspend SettingsRepository.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                try {
                    repository.write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
        }
    }

    fun submit(write: suspend SettingsRepository.() -> Unit) {
        writes.trySend(write)
    }
}
