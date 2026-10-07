package io.github.yuubiz.folirel.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bounds of a backup that is read. They are far above what a real Folirel holds (a backup of 50,000 articles is a few tens of MB) and
 * stop a ZIP bomb or a hostile archive long before memory or storage run out. Sizes are counted on the bytes really inflated, never
 * taken from the sizes an entry declares.
 */
data class BackupLimits(
    /** The archive file itself. */
    val fileBytes: Long = 256L * MIB,
    /** Inflated bytes of one entry. */
    val entryBytes: Long = 768L * MIB,
    /** Inflated bytes of all entries together. */
    val totalBytes: Long = 1024L * MIB,
    /** Inflated bytes of the manifest and of the settings, which are read whole. */
    val smallEntryBytes: Long = 1L * MIB,
    /** Inflated bytes per compressed byte read; JSON compresses roughly 3-20 times, a ZIP bomb by hundreds or more. */
    val maxRatio: Int = 200,
    /** The ratio is not judged before this many bytes were inflated (tiny entries compress oddly). */
    val ratioFloorBytes: Long = 1L * MIB,
) {
    companion object {
        private const val MIB = 1024L * 1024
        val DEFAULT = BackupLimits()
    }
}

/**
 * Writes the entries of [BackupFormat.ENTRIES] in their order, each as UTF-8 JSON: an object, or an array streamed record by record so
 * that no archive is ever built as one string in memory.
 */
internal class BackupZipWriter(out: OutputStream, private val time: Long) : Closeable {
    private val zip = ZipOutputStream(out.buffered(64 * 1024))
    private var next = 0

    fun obj(name: String, value: JsonObject) {
        entry(name) { it.write(value.toString()) }
    }

    /** One array entry; [block] calls the given function once per record. */
    suspend fun array(name: String, block: suspend (emit: (JsonObject) -> Unit) -> Unit) {
        val writer = begin(name)
        writer.write("[")
        var first = true
        block { record ->
            if (!first) writer.write(",\n")
            first = false
            writer.write(record.toString())
        }
        writer.write("]\n")
        end(writer)
    }

    private inline fun entry(name: String, body: (BufferedWriter) -> Unit) {
        val writer = begin(name)
        body(writer)
        end(writer)
    }

    private fun begin(name: String): BufferedWriter {
        check(name == BackupFormat.ENTRIES.getOrNull(next)) { "entry $name out of order" }
        next++
        zip.putNextEntry(ZipEntry(name).apply { time = this@BackupZipWriter.time })
        // The entry must stay open after the writer is flushed: closing goes through closeEntry, not close.
        return BufferedWriter(OutputStreamWriter(object : FilterOutputStream(zip) {
            override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
            override fun close() = flush()
        }, Charsets.UTF_8), 64 * 1024)
    }

    private fun end(writer: BufferedWriter) {
        writer.flush()
        zip.closeEntry()
    }

    override fun close() {
        check(next == BackupFormat.ENTRIES.size) { "archive incomplete" }
        zip.close()
    }
}

/**
 * Reads an archive copied to app-private storage, entry by entry and only through the local headers in file order (the same view every
 * pass gets, so what was validated is what is applied). Refuses, before the visitor sees anything of it: an entry that is a directory,
 * has a path, is not one of the version 1 entries, appears twice or out of order, and any entry or total that inflates past [limits].
 */
internal class BackupZipReader(private val file: File, private val limits: BackupLimits) {
    /** [visit] is called once per entry in [BackupFormat.ENTRIES] order with a bounded stream of its content. */
    suspend fun read(visit: suspend (name: String, input: InputStream) -> Unit) {
        if (file.length() > limits.fileBytes) refuse(BackupProblem.TOO_LARGE, "archive file")
        val counted = CountingInputStream(file.inputStream().buffered(64 * 1024))
        var total = 0L
        try {
            ZipInputStream(counted, Charsets.UTF_8).use { zip ->
                var index = 0
                while (true) {
                    val entry = try {
                        zip.nextEntry
                    } catch (e: IllegalArgumentException) {
                        // An entry name that is not valid UTF-8.
                        refuse(BackupProblem.DAMAGED, "ZIP entry name")
                    } ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.contains('/') || name.contains('\\') || name.contains("..") || name.contains(':') || name.isEmpty()) {
                        refuse(BackupProblem.UNSAFE_ARCHIVE, "entry path")
                    }
                    if (index == 0 && name != BackupFormat.MANIFEST) refuse(BackupProblem.NOT_A_BACKUP, "no manifest")
                    if (name !in BackupFormat.ENTRIES) refuse(BackupProblem.UNSAFE_ARCHIVE, "unexpected entry")
                    if (BackupFormat.ENTRIES.indexOf(name) < index) refuse(BackupProblem.UNSAFE_ARCHIVE, "duplicate entry")
                    if (name != BackupFormat.ENTRIES[index]) refuse(BackupProblem.UNSAFE_ARCHIVE, "entry order")
                    index++
                    val cap = if (name == BackupFormat.MANIFEST || name == BackupFormat.SETTINGS) limits.smallEntryBytes else limits.entryBytes
                    val bounded = BoundedEntryStream(zip, cap) { n ->
                        total += n
                        if (total > limits.totalBytes) refuse(BackupProblem.TOO_LARGE, "total size")
                        if (total > limits.ratioFloorBytes && total > limits.maxRatio.toLong() * counted.count.coerceAtLeast(1)) {
                            refuse(BackupProblem.TOO_LARGE, "compression ratio")
                        }
                    }
                    visit(name, bounded)
                    // Whatever the visitor left unread is still inflated and counted, so trailing data cannot hide behind a short JSON value.
                    bounded.drain()
                }
                if (index == 0) refuse(BackupProblem.NOT_A_BACKUP, "empty or not a ZIP")
                if (index < BackupFormat.ENTRIES.size) refuse(BackupProblem.DAMAGED, "missing ${BackupFormat.ENTRIES[index]}")
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipException) {
            refuse(BackupProblem.DAMAGED, "ZIP: ${e.javaClass.simpleName}")
        } catch (e: IOException) {
            refuse(BackupProblem.DAMAGED, "read: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private val json = Json

        /** A small entry (manifest, settings) as one JSON value. */
        fun readValue(input: InputStream): JsonElement = parse { json.parseToJsonElement(input.readBytes().toString(Charsets.UTF_8)) }

        /** The records of an array entry, one at a time; the whole array is never held in memory. */
        @OptIn(ExperimentalSerializationApi::class)
        suspend fun forEachRecord(input: InputStream, each: suspend (JsonElement) -> Unit) {
            val records = parse { json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).iterator() }
            // Only the JSON reading is classified as damage; what [each] throws (a refused value, a database error) passes unchanged.
            while (true) {
                val record = parse { if (records.hasNext()) records.next() else null } ?: break
                each(record)
            }
        }

        private inline fun <T> parse(block: () -> T): T = try {
            block()
        } catch (e: SerializationException) {
            refuse(BackupProblem.DAMAGED, "JSON")
        } catch (e: IllegalArgumentException) {
            refuse(BackupProblem.DAMAGED, "JSON")
        } catch (e: IllegalStateException) {
            refuse(BackupProblem.DAMAGED, "JSON")
        }
    }
}

/** Counts the bytes taken from the archive file (compressed side). */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

/** The content of the current entry, failing as soon as it inflates past [cap]; closing it does not close the archive. */
private class BoundedEntryStream(private val zip: ZipInputStream, private val cap: Long, private val onBytes: (Int) -> Unit) : InputStream() {
    private var read = 0L

    private fun account(n: Int) {
        if (n <= 0) return
        read += n
        if (read > cap) refuse(BackupProblem.TOO_LARGE, "entry size")
        onBytes(n)
    }

    override fun read(): Int = zip.read().also { if (it >= 0) account(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { account(it) }

    fun drain() {
        val buffer = ByteArray(64 * 1024)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }

    override fun close() = Unit
}
