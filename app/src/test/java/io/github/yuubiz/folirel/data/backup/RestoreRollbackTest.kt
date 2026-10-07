package io.github.yuubiz.folirel.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3C failure safety (T40, T41): a restore that cannot finish leaves the install as it was, data and settings, whether the settings
 * write fails, the database replace fails, putting the old settings back fails too, or the app dies at any step (the next start
 * resolves the restore's note). Busy background work stops a restore before anything changes.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRollbackTest {
    /** A DataStore whose writes fail while [fail] is set (a full disk). */
    private class FailingStore(private val inner: DataStore<Preferences>, val fail: AtomicBoolean) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (fail.get()) throw IOException("injected: no space left")
            return inner.updateData(transform)
        }
    }

    /** Stands for the process being killed: not an Exception, so nothing in the restore catches it. */
    private class ProcessDeath : Error("injected process death")

    private class Scenario(faults: suspend (FullBackupService.RestoreStep) -> Unit = {}, background: BackgroundWork = BackgroundWork.NONE) {
        val failWrites = AtomicBoolean(false)
        val source = BackupDevice()
        val target = BackupDevice(idPrefix = "target", faults = faults, background = background, storeWrapper = { FailingStore(it, failWrites) })
        lateinit var backup: ByteArray
        lateinit var before: List<String>
        lateinit var settingsBefore: Preferences
        lateinit var expectedAfter: List<String>

        suspend fun prepare() {
            RichState.build(source)
            RichState.build(target)
            target.settings.setLastRefreshAttempt(7)
            target.clock.now = source.clock.now
            backup = source.backupBytes()
            before = LogicalState.of(target)
            settingsBefore = target.settings.rawSnapshot()
            expectedAfter = LogicalState.of(source)
            assertNotEquals(before, expectedAfter)
        }

        suspend fun assertUnchanged() {
            assertEquals(before, LogicalState.of(target))
            assertEquals(settingsBefore, target.settings.rawSnapshot())
        }

        val journal: File get() = File(target.workDir, "restore-journal.json")

        fun close() {
            source.close()
            target.close()
        }
    }

    private fun scenario(s: Scenario, block: suspend Scenario.() -> Unit) = runBlocking {
        try {
            s.prepare()
            s.block()
        } finally {
            s.close()
        }
    }

    /** T40: the settings cannot be written: nothing changed, no note left. */
    @Test
    fun t40_settingsWriteFails() {
        val s = Scenario()
        scenario(s) {
            val preview = target.inspect(backup)
            failWrites.set(true)
            assertEquals(RestoreOutcome.Failed(settingsKept = true), target.service.restore(preview))
            failWrites.set(false)
            assertUnchanged()
            assertFalse(journal.exists())
        }
    }

    /** T41: the database replace fails at its very end (all rows already replaced in the transaction): data and settings as before. */
    @Test
    fun t41_databaseReplaceFails() {
        val s = Scenario(faults = { if (it == FullBackupService.RestoreStep.DATABASE_BEFORE_COMMIT) throw IllegalStateException("injected: disk I/O error") })
        scenario(s) {
            assertEquals(RestoreOutcome.Failed(settingsKept = true), target.restore(backup))
            assertUnchanged()
            assertFalse(journal.exists())
        }
    }

    /** T41: the database fails and the old settings cannot be written back now: the note stays and the next start puts them back. */
    @Test
    fun t41_databaseFailsAndSettingsCannotBeRevertedYet() {
        lateinit var holder: Scenario
        val s = Scenario(faults = {
            if (it == FullBackupService.RestoreStep.DATABASE_BEFORE_COMMIT) {
                holder.failWrites.set(true)
                throw IllegalStateException("injected")
            }
        })
        holder = s
        scenario(s) {
            assertEquals(RestoreOutcome.Failed(settingsKept = false), target.restore(backup))
            assertTrue(journal.exists())
            failWrites.set(false)
            // The next start.
            assertEquals(RestoreRecovery.ROLLED_BACK, target.service.recoverInterruptedRestore())
            assertUnchanged()
            assertFalse(journal.exists())
        }
    }

    /** The app dies after the new settings were written, inside the database transaction: the next start puts the old settings back. */
    @Test
    fun processDeathBeforeTheDatabaseCommit() {
        val s = Scenario(faults = { if (it == FullBackupService.RestoreStep.DATABASE_BEFORE_COMMIT) throw ProcessDeath() })
        scenario(s) {
            try {
                target.restore(backup)
                fail("the injected death did not stop the restore")
            } catch (e: ProcessDeath) {
                // Expected.
            }
            assertTrue(journal.exists())
            assertNotEquals("the new settings were already written", settingsBefore, target.settings.rawSnapshot())
            assertEquals(RestoreRecovery.ROLLED_BACK, target.service.recoverInterruptedRestore())
            assertUnchanged()
        }
    }

    /** The app dies between the settings and the database: the same. */
    @Test
    fun processDeathBetweenSettingsAndDatabase() {
        val s = Scenario(faults = { if (it == FullBackupService.RestoreStep.BEFORE_DATABASE) throw ProcessDeath() })
        scenario(s) {
            runCatching { target.restore(backup) }.exceptionOrNull() as ProcessDeath
            assertEquals(RestoreRecovery.ROLLED_BACK, target.service.recoverInterruptedRestore())
            assertUnchanged()
        }
    }

    /** The app dies before the settings write: nothing changed; the next start only removes the note. */
    @Test
    fun processDeathBeforeTheSettings() {
        val s = Scenario(faults = { if (it == FullBackupService.RestoreStep.BEFORE_SETTINGS) throw ProcessDeath() })
        scenario(s) {
            runCatching { target.restore(backup) }.exceptionOrNull() as ProcessDeath
            assertUnchanged()
            assertEquals(RestoreRecovery.ROLLED_BACK, target.service.recoverInterruptedRestore())
            assertUnchanged()
            assertFalse(journal.exists())
        }
    }

    /** The app dies after the database commit, before the note was removed: the next start keeps the restored state. */
    @Test
    fun processDeathAfterTheDatabaseCommit() {
        val s = Scenario(faults = { if (it == FullBackupService.RestoreStep.AFTER_DATABASE) throw ProcessDeath() })
        scenario(s) {
            runCatching { target.restore(backup) }.exceptionOrNull() as ProcessDeath
            assertTrue(journal.exists())
            assertEquals(RestoreRecovery.COMPLETED, target.service.recoverInterruptedRestore())
            assertEquals(expectedAfter, LogicalState.of(target))
            assertFalse(journal.exists())
            assertEquals("the install's own bookkeeping is kept", 7L, target.settings.current().lastRefreshAttemptAt)
        }
    }

    /** A start without a note does nothing. */
    @Test
    fun recoveryWithoutANoteDoesNothing() {
        val s = Scenario()
        scenario(s) {
            assertEquals(RestoreRecovery.NONE, target.service.recoverInterruptedRestore())
            assertUnchanged()
        }
    }

    /** Background work that does not stop: the restore changes nothing, and background work is allowed again afterwards. */
    @Test
    fun busyBackgroundWorkStopsTheRestoreBeforeAnyChange() {
        val resumed = AtomicInteger()
        val s = Scenario(background = object : BackgroundWork {
            override suspend fun pause() = false
            override fun resume() {
                resumed.incrementAndGet()
            }
        })
        scenario(s) {
            val preview = target.inspect(backup)
            assertEquals(RestoreOutcome.Busy, target.service.restore(preview))
            assertEquals(1, resumed.get())
            assertUnchanged()
            assertFalse(journal.exists())
            assertTrue("the preview can be confirmed again", preview.file.exists())
        }
    }

    /** Background work is paused for the whole restore and resumed after it, also when the restore fails. */
    @Test
    fun backgroundWorkIsPausedAroundTheRestore() {
        val events = ArrayList<String>()
        val s = Scenario(
            faults = { events += "step:$it" },
            background = object : BackgroundWork {
                override suspend fun pause(): Boolean {
                    events += "pause"
                    return true
                }

                override fun resume() {
                    events += "resume"
                }
            },
        )
        scenario(s) {
            assertEquals(RestoreOutcome.Restored, target.restore(backup))
            assertEquals(
                listOf("pause", "step:BEFORE_SETTINGS", "step:BEFORE_DATABASE", "step:DATABASE_BEFORE_COMMIT", "step:AFTER_DATABASE", "resume"),
                events,
            )
            assertEquals(expectedAfter, LogicalState.of(target))
        }
    }

    /** Two restores in a row: each commits with its own mark, and a later start does not undo anything. */
    @Test
    fun restoreTwiceThenRestart() {
        val s = Scenario()
        scenario(s) {
            assertEquals(RestoreOutcome.Restored, target.restore(backup))
            assertEquals(RestoreOutcome.Restored, target.restore(backup))
            assertEquals(RestoreRecovery.NONE, target.service.recoverInterruptedRestore())
            assertEquals(expectedAfter, LogicalState.of(target))
        }
    }
}
