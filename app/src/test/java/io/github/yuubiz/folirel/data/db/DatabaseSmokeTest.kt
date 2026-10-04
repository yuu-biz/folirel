package io.github.yuubiz.folirel.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java,
    ).build()

    @After
    fun close() = db.close()

    @Test
    fun insertAndReadFeedWithRealSqlite() = runTest {
        val dao = db.subscriptionDao()
        dao.insertAccount(AccountEntity("acc", "LOCAL", "Local", 0))
        dao.insertFeed(FeedEntity("f1", "acc", "https://example.com/a.xml", "https://example.com/a.xml", null, null, null, true, false, 0))
        assertEquals("https://example.com/a.xml", dao.feed("f1")!!.subscriptionUrl)
        // Foreign keys are enforced: a feed for an unknown account is rejected.
        val rejected = runCatching {
            dao.insertFeed(FeedEntity("f2", "missing", "https://example.com/b.xml", "https://example.com/b.xml", null, null, null, true, false, 0))
        }
        assertTrue(rejected.isFailure)
    }
}
