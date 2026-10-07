package io.github.yuubiz.folirel.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.db.ArticleEntity
import io.github.yuubiz.folirel.data.db.ArticleStateEntity
import io.github.yuubiz.folirel.data.db.FeedEntity
import io.github.yuubiz.folirel.data.db.FeedFetchStateEntity
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3C round trips: what a backup holds (T01–T12), a snapshot under concurrent changes (T13), restore into a fresh and into a used
 * install (T30, T31), identity history, articles, categories and settings exactly as they were (T32–T36), fresh fetch bookkeeping (T37),
 * the decisive rich state → backup → fresh state → restore → logical comparison (T43), backup → restore → backup (T44), and a large
 * realistic archive (§17).
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private fun devices(vararg d: BackupDevice, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        try {
            block()
        } finally {
            d.forEach { it.close() }
        }
    }

    /** T01: an install with nothing in it backs up and restores; the archive has exactly the seven entries in their order. */
    @Test
    fun t01_emptyInstall() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "b")
        devices(a, b) {
            val bytes = a.backupBytes()
            assertEquals(BackupFormat.ENTRIES, Zips.entries(bytes).keys.toList())
            val preview = b.inspect(bytes)
            assertEquals(BackupCounts(0, 0, 0, 0, 0, 0, 0, 0, 0), preview.counts)
            assertEquals(RestoreOutcome.Restored, b.service.restore(preview))
            assertEquals(LogicalState.of(a), LogicalState.of(b))
        }
    }

    /**
     * T43 (with T02–T11, T30, T32–T36): a used install, backed up and restored into a fresh one, is the same install: feeds and their
     * history, nested categories and memberships, order, pin and layouts, articles, read and saved, pruned entries and every setting.
     */
    @Test
    fun t43_richStateRestoresIdenticallyIntoAFreshInstall() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        devices(a, b) {
            val ids = RichState.build(a)
            val expected = LogicalState.of(a)
            b.clock.now = a.clock.now
            val bytes = a.backupBytes()
            assertEquals(RestoreOutcome.Restored, b.restore(bytes))
            assertEquals(expected, LogicalState.of(b))

            // The parts the reader would look at, spelled out.
            val s = b.settings.current()
            assertEquals(ColorPalette.CUSTOM, s.palette)
            assertEquals(AppSettings.opaque(0xFF8E24AA.toInt()), s.customSeed)
            assertEquals(RichState.light, s.customLight)
            assertEquals(RichState.dark, s.customDark)
            assertEquals(4, s.cardColumns)
            assertEquals(AppSettings.textScaleOf(115), s.textScale)
            assertEquals(listOf("広告", "Sponsored PR"), s.hiddenKeywords)
            assertTrue(s.hiddenKeywordsEnabled)
            assertEquals(ArticleScope.Category(ids.categories.getValue("domestic")), s.lastScope)
            assertTrue("a restored install is set up", s.onboardingDone)
            val prefs = b.env.db.subscriptionDao().categoryPreferences(b.account()).associateBy { it.categoryId }
            assertEquals(5, prefs.getValue(ids.categories.getValue("tech")).cardColumnsOverride)
            assertEquals(0, prefs.getValue(ids.categories.getValue("news")).cardColumnsOverride)
            assertTrue(prefs.getValue(ids.categories.getValue("domestic")).isPinned)
            // A saved article of a feed that was unsubscribed is still there and saved.
            val goneSaved = b.env.db.articleDao().state(ids.removedFeedSaved)!!
            assertTrue(goneSaved.isSaved)
            assertTrue(b.env.db.articleDao().state(ids.savedArticle)!!.isSaved)
        }
    }

    /** T44: backup → restore → backup gives the same archive apart from the creation time. */
    @Test
    fun t44_backupOfARestoreEqualsTheOriginalBackup() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        devices(a, b) {
            RichState.build(a)
            b.clock.now = a.clock.now
            val first = a.backupBytes()
            assertEquals(RestoreOutcome.Restored, b.restore(first))
            b.clock.advanceMinutes(30)
            val second = b.backupBytes()
            val e1 = Zips.entries(first)
            val e2 = Zips.entries(second)
            assertEquals(e1.keys, e2.keys)
            for (name in BackupFormat.ENTRIES - BackupFormat.MANIFEST) assertEquals(name, e1[name], e2[name])
            fun manifest(text: String) = JsonObject(Json.parseToJsonElement(text).jsonObject - "createdAt" - "createdAtUtc")
            assertEquals(manifest(e1.getValue(BackupFormat.MANIFEST)), manifest(e2.getValue(BackupFormat.MANIFEST)))
            assertNotEquals(e1[BackupFormat.MANIFEST], e2[BackupFormat.MANIFEST])
        }
    }

    /** T12: no cache, icon, diagnostics, validators, failure or wait, pending request, or install bookkeeping in the archive. */
    @Test
    fun t12_bookkeepingIsNotBackedUp() {
        val a = BackupDevice()
        devices(a) {
            RichState.build(a)
            val text = Zips.entries(a.backupBytes()).values.joinToString("\n")
            for (absent in listOf("etag", "validator", "lastModified", "req-1", "RATE_LIMITED", "retryAfter", "failureCount", "pending", "png", "icon", "lastRefresh", "onboarding", "diagnostic")) {
                assertFalse("archive mentions $absent", text.contains(absent, ignoreCase = true))
            }
        }
    }

    /** T37: after a restore every feed starts with clean fetch bookkeeping; its addresses and history are kept. */
    @Test
    fun t37_fetchBookkeepingStartsFresh() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        devices(a, b) {
            val ids = RichState.build(a)
            assertEquals(RestoreOutcome.Restored, b.restore(a.backupBytes()))
            val states = b.env.db.subscriptionDao().fetchStates(b.account())
            assertEquals(ids.feeds.size, states.size)
            for (st in states) {
                assertEquals(FeedFetchStateEntity(feedId = st.feedId, initialized = true), st)
            }
            assertTrue(b.env.db.diagnosticsDao().pendingFeedIds(b.account()).isEmpty())
            assertEquals(0, b.env.db.iconDao().count("OK"))
            val old = b.env.db.subscriptionDao().feed(ids.feeds[2])!!
            assertEquals(RichState.HTTP_FEED, old.subscriptionUrl)
            assertEquals(RichState.HTTPS_FEED, old.fetchUrl)
        }
    }

    /** T31: restoring over a used install replaces it completely; nothing of the replaced data stays, the device bookkeeping does. */
    @Test
    fun t31_replacesANonEmptyInstall() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "other")
        devices(a, b) {
            RichState.build(a)
            val expected = LogicalState.of(a)
            // B: its own feeds, categories, articles and settings.
            val account = b.account()
            b.subscriptions.addFeed(account, "https://b-only.example.com/feed")
            b.subscriptions.createCategory(account, null, "B only")
            b.settings.setTheme(io.github.yuubiz.folirel.data.preferences.ThemeMode.LIGHT)
            b.settings.addHiddenKeyword("b-keyword")
            b.settings.setLastRefreshAttempt(42)
            b.clock.now = a.clock.now
            assertEquals(RestoreOutcome.Restored, b.restore(a.backupBytes()))
            assertEquals(expected, LogicalState.of(b))
            assertNull(b.subscriptions.findFeed(account, "https://b-only.example.com/feed"))
            assertEquals("the install's own refresh bookkeeping is kept", 42L, b.settings.current().lastRefreshAttemptAt)
            assertEquals("the account of this install is kept", account, b.account())
        }
    }

    /** T32: after a restore an old OPML matches the restored feeds (no duplicates) and does not move a feed back to HTTP. */
    @Test
    fun t32_identityHistoryWorksAfterRestore() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        devices(a, b) {
            RichState.build(a)
            assertEquals(RestoreOutcome.Restored, b.restore(a.backupBytes()))
            val account = b.account()
            val before = b.env.db.backupDao().countFeeds(account)
            val opml = OpmlParser.parse(
                ("<opml version=\"2.0\"><body><outline text=\"old\" xmlUrl=\"${RichState.HTTP_FEED}\"/>" +
                    "<outline text=\"moved\" xmlUrl=\"${RichState.MOVED_FROM}\"/><outline text=\"moved new\" xmlUrl=\"${RichState.MOVED_TO}\"/></body></opml>").toByteArray(),
            )
            val preview = b.subscriptions.previewImport(account, opml, allowHttp = true)
            assertEquals(0, preview.newFeeds)
            val result = b.subscriptions.commitImport(account, opml)
            assertEquals(0, result.createdFeeds)
            assertEquals(before, b.env.db.backupDao().countFeeds(account))
            val old = b.subscriptions.findFeed(account, RichState.HTTP_FEED)!!
            assertEquals(RichState.HTTPS_FEED, old.fetchUrl)
        }
    }

    /** T08: pruned entries travel and still keep a removed article from coming back unread; past their lifetime they are forgotten. */
    @Test
    fun t08_prunedEntries() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        val c = BackupDevice(idPrefix = "late")
        devices(a, b, c) {
            val ids = RichState.build(a)
            val bytes = a.backupBytes()
            val pruned = a.env.db.backupDao().pruned(a.account())
            assertTrue(pruned.isNotEmpty())

            b.clock.now = a.clock.now
            assertEquals(RestoreOutcome.Restored, b.restore(bytes))
            assertEquals(pruned, b.env.db.backupDao().pruned(b.account()))
            // The feed returns a removed entry again: it is not inserted as a new unread article.
            val victim = pruned.first()
            assertTrue(victim.feedId in ids.feeds)
            val result = b.env.store.applySuccess(
                victim.feedId,
                NormalizedFeed("again", null, listOf(NormalizedEntry(victim.entryKey, "again", null, "", null, null, null))),
                FetchMetadata(null, null, "https://x.example/", null),
            )
            assertEquals(0, (result as io.github.yuubiz.folirel.data.repository.ApplyResult.Applied).newArticles)

            // 91 days later the cleanup's own rule forgets them.
            c.clock.now = a.clock.now.plusSeconds(91L * 24 * 3600)
            val preview = c.inspect(bytes)
            assertEquals(pruned.size, preview.counts.prunedEntries)
            assertEquals(0, preview.prunedKept)
            assertEquals(RestoreOutcome.Restored, c.service.restore(preview))
            assertTrue(c.env.db.backupDao().pruned(c.account()).isEmpty())
        }
    }

    /** T27: the preview's counts are exactly those of the backed-up install, and it shows what is on the device now. */
    @Test
    fun t27_previewCountsAreExact() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "other")
        devices(a, b) {
            RichState.build(a)
            b.subscriptions.addFeed(b.account(), "https://b-only.example.com/feed")
            val source = a.service.currentCounts()
            val preview = b.inspect(a.backupBytes())
            assertEquals(source, preview.counts)
            assertEquals(6, preview.counts.feeds)
            assertEquals(1, preview.unsubscribedFeeds)
            assertEquals(4, preview.counts.categories)
            assertEquals(5, preview.counts.memberships)
            assertEquals(2, preview.counts.savedArticles)
            assertEquals(2, preview.counts.hiddenKeywords)
            assertEquals(preview.counts.articles - preview.counts.readArticles, preview.unreadArticles)
            assertEquals(1, preview.current.feeds)
            assertEquals(BackupFormat.VERSION, preview.manifest.formatVersion)
            assertEquals("0.10.0-test", preview.manifest.appVersionName)
        }
    }

    /** T28 / T29: looking at a backup and cancelling changes nothing, and the private copy of the file is gone. */
    @Test
    fun t28_t29_previewAndCancelChangeNothing() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "other")
        devices(a, b) {
            RichState.build(a)
            RichState.build(b)
            val before = LogicalState.of(b)
            val preview = b.inspect(a.backupBytes())
            assertEquals("no change before the confirmation", before, LogicalState.of(b))
            assertTrue(preview.file.exists())
            b.service.discard(preview)
            assertFalse(preview.file.exists())
            assertEquals(before, LogicalState.of(b))
        }
    }

    /** T13: backups taken while the reader and refresh change data are each a consistent snapshot (references and counts check out). */
    @Test
    fun t13_snapshotIsConsistentUnderConcurrentChanges() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "check")
        devices(a, b) {
            val ids = RichState.build(a)
            val account = a.account()
            val mutator = launch(Dispatchers.IO) {
                var i = 0
                while (isActive) {
                    i++
                    val feed = a.subscriptions.addFeed(account, "https://busy.example.com/$i")
                    val id = (feed as io.github.yuubiz.folirel.data.repository.AddFeedResult.Added).feedId
                    a.env.store.applySuccess(id, NormalizedFeed("busy $i", null, (1..5).map { NormalizedEntry("id:$i-$it", "t", null, "s", null, null, null) }), FetchMetadata(null, null, "https://busy.example.com/", null))
                    a.subscriptions.setFeedCategories(id, setOf(ids.categories.getValue("tech")))
                    a.env.db.articleDao().articlesOfFeed(id).forEach { a.env.articles.setRead(it.id, true) }
                    if (i % 3 == 0) a.subscriptions.createCategory(account, null, "busy $i")
                    a.settings.setLastFilter(if (i % 2 == 0) io.github.yuubiz.folirel.model.ReadFilter.UNREAD else io.github.yuubiz.folirel.model.ReadFilter.SAVED)
                }
            }
            try {
                repeat(5) {
                    delay(20)
                    val bytes = withContext(Dispatchers.IO) { a.backupBytes() }
                    // inspect checks every reference and every count of the manifest.
                    val preview = b.inspect(bytes)
                    b.service.discard(preview)
                    assertTrue(preview.counts.feeds >= 6)
                }
            } finally {
                mutator.cancel()
            }
        }
    }

    /** §17: a large install (1,000 feeds, 30,000 articles, Japanese and JSON special characters, long summaries) round-trips exactly. */
    @Test
    fun largeInstallRoundTrips() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        devices(a, b) {
            val account = a.account()
            val dao = a.env.db.backupDao()
            val feeds = (1..1_000).map { i ->
                FeedEntity("feed-$i", account, "https://f$i.example.jp/rss?id=$i", "https://f$i.example.jp/rss?id=$i", "https://f$i.example.jp/", "フィード「$i」\"\\", null, i % 10 != 0, i % 50 == 0, 1_700_000_000_000L + i)
            }
            a.env.db.backupDao().insertFeeds(feeds)
            dao.insertFetchStates(feeds.map { FeedFetchStateEntity(feedId = it.id, initialized = true) })
            // The emoji stays at the front so that cutting the text never splits it.
            val summary = "😀 " + "日本語の長い要約です。\"引用\" \\ / \u0001 タブ\t改行\n。".repeat(60)
            for (chunk in (1..30_000).chunked(1_000)) {
                dao.insertArticles(chunk.map { n ->
                    ArticleEntity("article-$n", account, "feed-${n % 1_000 + 1}", "id:urn:$n", null, "https://f.example.jp/a/$n", "タイトル $n <b>&amp;</b>", summary.take(200 + n % 1_500), if (n % 4 == 0) null else "著者$n", 1_700_000_000_000L + n, 1_700_000_000_000L + n, 1_700_000_000_000L + n, null, 1_700_000_000_000L + n)
                })
                dao.insertArticleStates(chunk.map { n -> ArticleStateEntity("article-$n", isRead = n % 3 != 0, isSaved = n % 97 == 0, updatedAt = 1_700_000_000_000L + n) })
            }
            val started = System.nanoTime()
            val bytes = a.backupBytes()
            val written = System.nanoTime()
            b.clock.now = a.clock.now
            assertEquals(RestoreOutcome.Restored, b.restore(bytes))
            val restored = System.nanoTime()
            println("large backup: ${bytes.size / 1024} KiB, write ${(written - started) / 1_000_000} ms, check+restore ${(restored - written) / 1_000_000} ms")
            assertEquals(LogicalState.of(a), LogicalState.of(b))
            assertEquals(30_000, b.env.db.backupDao().countArticles(b.account()))
        }
    }

    /** The last selection names a category of the archive: it is restored. One that names nothing restored opens All (T39). */
    @Test
    fun t39_missingLastScopeOpensAll() {
        val a = BackupDevice()
        val b = BackupDevice(idPrefix = "fresh")
        devices(a, b) {
            RichState.build(a)
            a.settings.setLastScope(ArticleScope.Category("no-such-category"))
            assertEquals(RestoreOutcome.Restored, b.restore(a.backupBytes()))
            assertEquals(ArticleScope.All, b.settings.current().lastScope)
            a.settings.setLastScope(ArticleScope.Feed("no-such-feed"))
            assertEquals(ArticleScope.All, b.inspect(a.backupBytes()).settings.lastScope)
            val feed = a.env.db.backupDao().feeds(a.account()).first().id
            a.settings.setLastScope(ArticleScope.Feed(feed))
            assertEquals(ArticleScope.Feed(feed), b.inspect(a.backupBytes()).settings.lastScope)
        }
    }

    /** The archive is UTF-8 JSON a person can read: Japanese is written as it is, one record per line. */
    @Test
    fun archiveIsReadableJson() {
        val a = BackupDevice()
        devices(a) {
            RichState.build(a)
            val e = Zips.entries(a.backupBytes())
            assertTrue(e.getValue(BackupFormat.CATEGORIES).contains("国内 / 地方"))
            val subscriptions = Json.parseToJsonElement(e.getValue(BackupFormat.SUBSCRIPTIONS)).jsonArray
            assertEquals(6, subscriptions.size)
            val manifest = Json.parseToJsonElement(e.getValue(BackupFormat.MANIFEST)).jsonObject
            assertEquals("\"folirel-backup\"", manifest["format"].toString())
            assertEquals("1", manifest["formatVersion"].toString())
        }
    }

    /** A backup while another coroutine waits on the same database does not block it for longer than the snapshot. */
    @Test
    fun backupDoesNotBreakAConcurrentWriter() {
        val a = BackupDevice()
        devices(a) {
            RichState.build(a)
            val writer = async(Dispatchers.IO) { a.subscriptions.addFeed(a.account(), "https://during.example.com/feed") }
            a.backupBytes()
            writer.await()
            assertTrue(a.subscriptions.findFeed(a.account(), "https://during.example.com/feed") != null)
        }
    }
}
