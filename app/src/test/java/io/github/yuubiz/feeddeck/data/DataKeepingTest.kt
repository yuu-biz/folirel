package io.github.yuubiz.feeddeck.data

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.data.db.AppDatabase
import io.github.yuubiz.feeddeck.data.preferences.SettingsRepository
import io.github.yuubiz.feeddeck.data.repository.RetentionManager
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import io.github.yuubiz.feeddeck.testutil.TestEnv
import io.github.yuubiz.feeddeck.ui.home.HomeViewModel
import io.github.yuubiz.feeddeck.ui.home.Selection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * N18 settings migration, N20/N21 retention (protection, no resurrection as unread), N25 DB v1→v2 migration
 * keeping data, and S02's display-condition rule for refresh targets.
 */
@RunWith(AndroidJUnit4::class)
class DataKeepingTest {

    /** N18: the old hours value becomes the same duration in minutes; 15/30 minutes can be stored. */
    @Test
    fun legacyHourIntervalIsMigratedToMinutes() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = File(TestEnv.context().cacheDir, "prefs-${System.nanoTime()}").apply { mkdirs() }
        for ((hours, minutes) in listOf(2 to 120, 6 to 360, 1 to 60, 24 to 1440, 0 to 0)) {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "h$hours.preferences_pb") }
            store.edit { it[intPreferencesKey("refresh_interval_hours")] = hours }
            val settings = SettingsRepository(store).current()
            assertEquals("$hours h", minutes, settings.refreshIntervalMinutes)
            // Startup refresh followed the automatic switch in v0.1.
            assertEquals(hours > 0, settings.startupRefresh)
        }
        val fresh = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "new.preferences_pb") })
        assertEquals(120, fresh.current().refreshIntervalMinutes)
        assertTrue(fresh.current().startupRefresh)
        fresh.setRefreshIntervalMinutes(15)
        assertEquals(15, fresh.current().refreshIntervalMinutes)
        fresh.setRefreshIntervalMinutes(30)
        assertEquals(30, fresh.current().refreshIntervalMinutes)
        assertEquals("retention default keeps everything", 0, fresh.current().retentionLimit)
        assertEquals(128, fresh.current().imageCacheMb)
        scope.cancel()
    }

    /** N20 / N21: only old read, unsaved articles go; refetching the same feed does not bring them back as unread. */
    @Test
    fun retentionProtectsUnreadAndSavedAndDoesNotResurrect() = runBlocking<Unit> {
        val server = TestEnv.startServer()
        val env = TestEnv()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body(
                    "<rss version=\"2.0\"><channel><title>f</title>" +
                        (1..10).joinToString("") { "<item><guid>g$it</guid><title>t$it</title><pubDate>Sat, 0$it Oct 2026 00:00:00 GMT</pubDate></item>".replace("0$it Oct", "${it.toString().padStart(2, '0')} Sep") } +
                        "</channel></rss>",
                ).build()
            }
            val account = env.subscriptions.ensureLocalAccount()
            val feedId = env.subscriptions.commitImport(
                account, OpmlParser.parse("<opml version=\"2.0\"><body><outline text=\"f\" xmlUrl=\"${server.url("/f")}\"/></body></opml>".toByteArray()),
            ).feedIdsToFetch.single()
            env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
            val articles = env.db.articleDao().articlesOfFeed(feedId).sortedBy { it.sortAt } // oldest first
            assertEquals(10, articles.size)
            // Oldest 6 read; the oldest of them also saved.
            articles.take(6).forEach { env.articles.setRead(it.id, true) }
            env.articles.setSaved(articles.first().id, true)

            val retention = RetentionManager(env.db, env.clock)
            val preview = retention.preview(account, 3)
            assertEquals(10, preview.total)
            assertEquals(5, preview.protectedCount) // 4 unread + 1 saved
            assertEquals(5, preview.toRemove)
            assertTrue(preview.overLimitBecauseProtected)
            assertEquals("nothing removed by preview", 10, env.db.articleDao().articlesOfFeed(feedId).size)

            assertEquals(5, retention.apply(account, 3))
            val left = env.db.articleDao().articlesOfFeed(feedId).map { it.id }.toSet()
            assertEquals(5, left.size)
            assertTrue("saved kept", articles.first().id in left)
            assertTrue("unread kept", articles.drop(6).all { it.id in left })
            assertTrue(env.db.articleDao().state(articles.first().id)!!.isSaved)

            env.clock.advanceMinutes(10)
            val report = env.backend.refresh(account, RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL) {}
            assertEquals("removed read articles are not inserted again", 0, report.newArticles)
            assertEquals(5, env.db.articleDao().articlesOfFeed(feedId).size)
            assertEquals(5, env.db.diagnosticsDao().diagnostics(feedId)!!.skippedPruned)
            val unread = env.articles.unreadIdsSnapshot(
                io.github.yuubiz.feeddeck.model.ArticleQuerySpec(account, ArticleScope.All, ReadFilter.ALL, SortOrder.NEWEST),
            )
            assertEquals(4, unread.size)
            // No limit: nothing to remove.
            assertEquals(0, retention.preview(account, 0).toRemove)
        } finally {
            env.close()
            server.close()
        }
    }

    /** N25: a v1 database (created from the exported v1 schema) opens through the v1→v2 migration with all data. */
    @Test
    fun version1DatabaseMigratesWithoutLosingData() = runBlocking<Unit> {
        val context = TestEnv.context()
        val name = "migrate-${System.nanoTime()}.db"
        val schema = JSONObject(File("schemas/io.github.yuubiz.feeddeck.data.db.AppDatabase/1.json").readText()).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        raw.execSQL("INSERT INTO accounts VALUES ('acc', 'LOCAL', 'Local', 0)")
        raw.execSQL("INSERT INTO feeds VALUES ('f1', 'acc', 'http://example.com/a.xml', 'http://example.com/a.xml', NULL, 'A', NULL, 1, 0, 0)")
        raw.execSQL("INSERT INTO feed_fetch_state (feedId, etag, lastModified, validatorUrl, lastAttemptAt, lastSuccessAt, lastErrorCode, failureCount, nextEligibleAt, initialized) VALUES ('f1', '\"e\"', NULL, 'http://example.com/a.xml', 5, 5, NULL, 0, NULL, 1)")
        raw.execSQL("INSERT INTO categories VALUES ('c1', 'acc', 'Tech', '[\"Tech\"]')")
        raw.execSQL("INSERT INTO feed_categories VALUES ('f1', 'c1')")
        raw.execSQL("INSERT INTO articles VALUES ('a1', 'acc', 'f1', 'id:1', NULL, 'https://example.com/1', 't', 's', NULL, 1, 1, 1, NULL, 1)")
        raw.execSQL("INSERT INTO article_states VALUES ('a1', 1, 1, 1)")
        raw.version = 1
        raw.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            // Room validates every table against the v2 schema while opening.
            val state = db.articleDao().state("a1")!!
            assertTrue(state.isRead && state.isSaved)
            assertEquals("Tech", db.subscriptionDao().categories("acc").single().name)
            assertEquals(1, db.subscriptionDao().memberships("acc").size)
            val fetch = db.subscriptionDao().fetchState("f1")!!
            assertEquals("\"e\"", fetch.etag)
            assertEquals(null, fetch.retryAfterUntil)
            assertEquals(null, db.diagnosticsDao().diagnostics("f1"))
            assertEquals(0, db.diagnosticsDao().prunedCount())
            assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** S02 (display part): filter, sort and search never narrow the refresh target. */
    @Test
    fun refreshTargetIgnoresDisplayConditions() {
        val base = Selection(scope = ArticleScope.Category("c1"))
        val variants = listOf(
            base,
            base.copy(filter = ReadFilter.UNREAD),
            base.copy(filter = ReadFilter.SAVED, sort = SortOrder.OLDEST),
            base.copy(search = "keyword"),
        )
        assertTrue(variants.all { HomeViewModel.refreshTargetFor(it) == RefreshScope.Category("c1") })
        assertEquals(RefreshScope.Feeds(listOf("f")), HomeViewModel.refreshTargetFor(Selection(scope = ArticleScope.Feed("f"), search = "x")))
        assertEquals(RefreshScope.All, HomeViewModel.refreshTargetFor(Selection(filter = ReadFilter.SAVED)))
        assertFalse(HomeViewModel.refreshTargetFor(Selection(scope = ArticleScope.Uncategorized)) == RefreshScope.All)
    }
}
