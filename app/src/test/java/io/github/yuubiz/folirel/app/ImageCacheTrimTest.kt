package io.github.yuubiz.folirel.app

import coil3.disk.DiskCache
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A thumbnail cache made smaller than what it holds is trimmed at the next start, not only when the next thumbnail is written. */
class ImageCacheTrimTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val entryBytes = 100_000

    private fun open(maxBytes: Long): DiskCache = DiskCache.Builder()
        .directory(folder.root.toOkioPath())
        .maxSizeBytes(maxBytes)
        .build()

    /** Coil's cleanup runs in the background; waits for it (or gives up after a few seconds). */
    private fun DiskCache.awaitSize(bytes: Long): Long {
        val deadline = System.currentTimeMillis() + 5_000
        while (size != bytes && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return size
    }

    private fun DiskCache.put(key: String) {
        val editor = openEditor(key)!!
        FileSystem.SYSTEM.write(editor.data) { write(ByteArray(entryBytes)) }
        editor.commit()
    }

    private fun DiskCache.has(key: String): Boolean = openSnapshot(key)?.use { true } ?: false

    @Test
    fun aCacheOverItsSizeIsTrimmedOldestFirstAndOneWithinItIsLeftAlone() {
        val large = open(1_000_000)
        (0 until 5).forEach { large.put("image-$it") }
        assertEquals(5L * entryBytes, large.size)
        large.shutdown()

        // The next start opens it smaller: Coil itself leaves it over the size until something is written.
        val small = open(250_000)
        assertTrue(small.size > small.maxSize)
        AppContainer.trimWhenOverSize(small)
        assertEquals(2L * entryBytes, small.awaitSize(2L * entryBytes))
        assertNull(small.openSnapshot("image-0"))
        assertTrue(small.has("image-3") && small.has("image-4"))

        // Within the size, nothing is removed.
        AppContainer.trimWhenOverSize(small)
        Thread.sleep(200)
        assertEquals(2L * entryBytes, small.size)
        assertNotNull(small.openSnapshot("image-4")?.also { it.close() })
        small.shutdown()
    }
}
