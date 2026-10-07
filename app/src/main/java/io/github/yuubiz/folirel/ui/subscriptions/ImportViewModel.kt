package io.github.yuubiz.folirel.ui.subscriptions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.folirel.app.DocumentIo
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.backend.local.opml.OpmlRejectedException
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.ImportPreview
import io.github.yuubiz.folirel.data.repository.ImportResult
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Preview(val preview: ImportPreview, val allowHttp: Boolean) : ImportState
    data class Rejected(val reason: OpmlRejectedException.Reason?) : ImportState
    data object ReadFailed : ImportState
    data object Committing : ImportState
    /** [httpFeeds]: subscribed feeds still fetched over http:// (offer the HTTPS check once). */
    data class Done(val result: ImportResult, val httpFeeds: Int = 0, val httpsAnswered: Boolean = false) : ImportState
    data object CommitFailed : ImportState
}

/** File pick → parse → preview → confirm → (fetch in background). Picking a file never touches the network. */
class ImportViewModel(
    private val documents: DocumentIo,
    private val subscriptions: SubscriptionRepository,
    private val settings: SettingsRepository,
    private val coordinator: RefreshCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun load(uri: Uri) {
        _state.value = ImportState.Reading
        viewModelScope.launch {
            val bytes = try {
                documents.read(uri)
            } catch (_: DocumentIo.TooLargeException) {
                _state.value = ImportState.Rejected(OpmlRejectedException.Reason.LIMIT_EXCEEDED)
                return@launch
            } catch (_: Exception) {
                _state.value = ImportState.ReadFailed
                return@launch
            }
            _state.value = try {
                val doc = withContext(Dispatchers.Default) { OpmlParser.parse(bytes) }
                val allowHttp = settings.current().allowHttp
                ImportState.Preview(subscriptions.previewImport(subscriptions.ensureLocalAccount(), doc, allowHttp), allowHttp)
            } catch (e: OpmlRejectedException) {
                ImportState.Rejected(e.reason)
            } catch (_: Exception) {
                ImportState.Rejected(null)
            }
        }
    }

    fun confirm() {
        val preview = (_state.value as? ImportState.Preview)?.preview ?: return
        _state.value = ImportState.Committing
        viewModelScope.launch {
            val result = try {
                subscriptions.commitImport(subscriptions.ensureLocalAccount(), preview.document)
            } catch (_: Exception) {
                _state.value = ImportState.CommitFailed
                return@launch
            }
            val httpFeeds = runCatching { subscriptions.httpFeeds(subscriptions.ensureLocalAccount()).size }.getOrDefault(0)
            _state.value = ImportState.Done(result, httpFeeds)
            // DB registration is final regardless of the fetch outcome; the fetch result is shown separately.
            if (result.feedIdsToFetch.isNotEmpty()) {
                // A failed refresh shows as interrupted in the refresh notice; it must not end the process after a finished import.
                try {
                    coordinator.refresh(RefreshScope.Feeds(result.feedIdsToFetch), RefreshReason.IMPORT)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /** The HTTPS question was answered ("check and change" or "not now"); it is not asked again for this import. */
    fun httpsAnswered() {
        (_state.value as? ImportState.Done)?.let { _state.value = it.copy(httpsAnswered = true) }
    }

    fun reset() {
        _state.value = ImportState.Idle
    }
}
