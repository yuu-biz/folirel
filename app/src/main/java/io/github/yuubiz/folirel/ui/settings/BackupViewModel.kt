package io.github.yuubiz.folirel.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import io.github.yuubiz.folirel.app.DocumentIo
import io.github.yuubiz.folirel.data.backup.BackupCounts
import io.github.yuubiz.folirel.data.backup.BackupException
import io.github.yuubiz.folirel.data.backup.BackupLimits
import io.github.yuubiz.folirel.data.backup.BackupProblem
import io.github.yuubiz.folirel.data.backup.FullBackupService
import io.github.yuubiz.folirel.data.backup.RestoreOutcome
import io.github.yuubiz.folirel.data.backup.RestorePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BackupState {
    data object Idle : BackupState
    data object Writing : BackupState
    data class Written(val counts: BackupCounts) : BackupState
    data object Failed : BackupState
}

sealed interface RestoreState {
    data object Idle : RestoreState

    /** Copying and checking the archive; nothing is changed. */
    data object Checking : RestoreState

    /** Refused; nothing was changed. [problem] null: the file could not be read. */
    data class Refused(val problem: BackupProblem?) : RestoreState

    data class Preview(val preview: RestorePreview) : RestoreState

    /** Confirmed and running; cannot be left. */
    data object Applying : RestoreState
    data class Restored(val counts: BackupCounts) : RestoreState

    /** An update or the HTTPS check did not stop; nothing was changed. The preview stays so that the reader can try again. */
    data class Busy(val preview: RestorePreview) : RestoreState
    data class Failed(val settingsKept: Boolean) : RestoreState
}

/**
 * Full backup and restore for the Storage page and the restore screen. The file work runs in the application scope, so leaving the
 * screen never cuts a backup or a restore in half.
 */
class BackupViewModel(
    private val service: FullBackupService,
    private val documents: DocumentIo,
    private val operations: CoroutineScope,
) : ViewModel() {
    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup: StateFlow<BackupState> = _backup.asStateFlow()

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    /** Writes a backup to the document the reader created. A failed write leaves no half file behind where the provider allows it. */
    fun createBackup(uri: Uri) {
        if (_backup.value == BackupState.Writing) return
        _backup.value = BackupState.Writing
        operations.launch {
            val file = runCatching { service.newWorkFile("outgoing") }.getOrNull()
            _backup.value = try {
                checkNotNull(file)
                val manifest = file.outputStream().use { service.createBackup(it) }
                documents.writeFile(uri, file)
                BackupState.Written(manifest.counts)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                documents.deleteQuietly(uri)
                BackupState.Failed
            } finally {
                file?.delete()
            }
        }
    }

    /** The result message was shown. */
    fun backupMessageShown() {
        if (_backup.value is BackupState.Written || _backup.value == BackupState.Failed) _backup.value = BackupState.Idle
    }

    /** A document was picked: copy it to app-private storage and check all of it. Nothing is changed. */
    fun load(uri: Uri) {
        discardPreview()
        _restore.value = RestoreState.Checking
        operations.launch {
            val file = runCatching { service.newWorkFile("incoming") }.getOrNull()
            _restore.value = try {
                checkNotNull(file)
                documents.copyToFile(uri, file, BackupLimits.DEFAULT.fileBytes)
                RestoreState.Preview(service.inspect(file))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BackupException) {
                file?.delete()
                RestoreState.Refused(e.problem)
            } catch (e: DocumentIo.TooLargeException) {
                file?.delete()
                RestoreState.Refused(BackupProblem.TOO_LARGE)
            } catch (e: Exception) {
                file?.delete()
                RestoreState.Refused(null)
            }
        }
    }

    /** The reader confirmed the replace. */
    fun confirm() {
        val preview = when (val s = _restore.value) {
            is RestoreState.Preview -> s.preview
            is RestoreState.Busy -> s.preview
            else -> return
        }
        _restore.value = RestoreState.Applying
        operations.launch {
            _restore.value = when (val outcome = service.restore(preview)) {
                RestoreOutcome.Restored -> RestoreState.Restored(preview.counts)
                RestoreOutcome.Busy -> RestoreState.Busy(preview)
                is RestoreOutcome.Failed -> RestoreState.Failed(outcome.settingsKept).also { service.discard(preview) }
            }
        }
    }

    /** Cancel or leave: the copy of the archive is deleted; nothing else changes. Not possible while a restore runs. */
    fun close() {
        if (_restore.value == RestoreState.Applying || _restore.value == RestoreState.Checking) return
        discardPreview()
        _restore.value = RestoreState.Idle
    }

    private fun discardPreview() {
        when (val s = _restore.value) {
            is RestoreState.Preview -> service.discard(s.preview)
            is RestoreState.Busy -> service.discard(s.preview)
            else -> Unit
        }
    }
}
