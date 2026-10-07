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

    /**
     * Copies the document into [dest] (app-private), at most [maxBytes]; a larger document is refused while it is copied. A full backup is
     * never read into memory as a whole; OPML keeps its own smaller limit in [read].
     */
    suspend fun copyToFile(uri: Uri, dest: java.io.File, maxBytes: Long) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open document")
        input.use { stream ->
            dest.outputStream().use { out ->
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw TooLargeException()
                    out.write(chunk, 0, n)
                }
            }
        }
    }

    /** Writes [src] to the document; any failure is reported to the caller. */
    suspend fun writeFile(uri: Uri, src: java.io.File) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open document")
        output.use { out -> src.inputStream().use { it.copyTo(out, 64 * 1024) } }
    }

    /** Best effort: removes a document that could not be written completely, so no broken backup is left behind. */
    suspend fun deleteQuietly(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            // The provider may not support deleting; the failure message already tells the reader the file is not usable.
        }
    }

    /** Writes the whole text; any failure is reported to the caller (no success toast on failure). */
    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open document")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
