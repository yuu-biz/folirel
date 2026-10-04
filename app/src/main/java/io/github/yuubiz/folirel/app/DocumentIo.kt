package io.github.yuubiz.folirel.app

import android.content.Context
import android.net.Uri
import io.github.yuubiz.folirel.model.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Storage Access Framework I/O for user-picked files (no storage permission). */
class DocumentIo(private val context: Context) {
    class TooLargeException : IOException("File too large")

    /** Reads at most [Limits.OPML_MAX_BYTES]; larger files are refused without reading them fully. */
    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open document")
        input.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > Limits.OPML_MAX_BYTES) throw TooLargeException()
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
    }

    /** Writes the whole text; any failure is reported to the caller (no success toast on failure). */
    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open document")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
