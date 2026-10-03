package io.github.yuubiz.feeddeck.ui.subscriptions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.feeddeck.app.DocumentIo
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlRejectedException
import io.github.yuubiz.feeddeck.data.preferences.SettingsRepository
import io.github.yuubiz.feeddeck.data.repository.ImportPreview
import io.github.yuubiz.feeddeck.data.repository.ImportResult
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.sync.RefreshCoordinator
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
    data class Done(val result: ImportResult) : ImportState
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
            _state.value = ImportState.Done(result)
            // DB registration is final regardless of the fetch outcome; the fetch result is shown separately.
            if (result.feedIdsToFetch.isNotEmpty()) {
                coordinator.refresh(RefreshScope.Feeds(result.feedIdsToFetch), RefreshReason.IMPORT)
            }
        }
    }

    fun reset() {
        _state.value = ImportState.Idle
    }
}
