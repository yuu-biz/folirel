package io.github.yuubiz.folirel.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Restores a maintainer's own backup (never committed, kept in the git-ignored local-data/) with the current code and backs it up
 * again, to show that real historical data passes the validator and survives the round trip. Run with
 * `-Pfolirel.probe -Pfolirel.realBackup=<path to the zip>`; without the path it does nothing.
 */
@RunWith(AndroidJUnit4::class)
class RealBackupProbe {
    @Test
    fun aRealBackupIsAcceptedRestoredAndWrittenAgainWithTheSameCounts() = runBlocking<Unit> {
        val path = System.getProperty("folirel.realBackup")?.takeIf { it.isNotBlank() } ?: return@runBlocking println("probe|no real backup given")
        val bytes = File(path).readBytes()
        val device = BackupDevice()
        try {
            val preview = device.inspect(bytes)
            println("probe|manifest ${preview.manifest.copy(counts = preview.counts)}")
            println("probe|preview counts=${preview.counts} prunedKept=${preview.prunedKept}")
            assertEquals(RestoreOutcome.Restored, device.service.restore(preview))
            val again = device.backupBytes()
            val manifest = ZipInputStream(again.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == BackupFormat.MANIFEST }
                zip.readBytes().toString(Charsets.UTF_8)
            }
            val reread = device.inspect(again)
            println("probe|after restore counts=${reread.counts}")
            assertEquals(preview.counts.copy(prunedEntries = reread.counts.prunedEntries), reread.counts)
            assertTrue(manifest.contains("\"formatVersion\":1"))
        } finally {
            device.close()
        }
    }
}
