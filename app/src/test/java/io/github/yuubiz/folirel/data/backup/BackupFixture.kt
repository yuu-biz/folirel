package io.github.yuubiz.folirel.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.yuubiz.folirel.data.db.FeedIconEntity
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.data.preferences.CategoryNavigation
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.CustomColors
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.data.repository.RetentionManager
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.testutil.MutableClock
import io.github.yuubiz.folirel.testutil.SequentialIds
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One Folirel install for backup tests: the real repositories over an in-memory Room database, a file DataStore, and the backup service
 * with its own private work directory. [close] releases all of it.
 */
class BackupDevice(
    val clock: MutableClock = MutableClock(),
    idPrefix: String = "id",
    background: BackgroundWork = BackgroundWork.NONE,
    limits: BackupLimits = BackupLimits.DEFAULT,
    faults: suspend (FullBackupService.RestoreStep) -> Unit = {},
    storeWrapper: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dir: File = File(TestEnv.context().cacheDir, "backup-device-${System.nanoTime()}").apply { mkdirs() }
    val env = TestEnv(clock = clock, ids = SequentialIds(idPrefix))
    val store: DataStore<Preferences> = storeWrapper(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })
    val settings = SettingsRepository(store)
    val workDir = File(dir, "work")
    val service = FullBackupService(
        db = env.db,
        settings = settings,
        accountId = { env.subscriptions.ensureLocalAccount() },
        clock = clock,
        workDir = workDir,
        app = AppVersion("0.10.0-test", 24),
        background = background,
        limits = limits,
        faults = faults,
    )

    val subscriptions: SubscriptionRepository get() = env.subscriptions

    suspend fun account(): String = env.subscriptions.ensureLocalAccount()

    /** A full backup as bytes. */
    suspend fun backupBytes(): ByteArray = ByteArrayOutputStream().also { service.createBackup(it) }.toByteArray()

    /** [bytes] as a file in the work directory, as the app copies a picked document. */
    fun incoming(bytes: ByteArray): File = service.newWorkFile("incoming").apply { writeBytes(bytes) }

    suspend fun inspect(bytes: ByteArray): RestorePreview = service.inspect(incoming(bytes))

    suspend fun restore(bytes: ByteArray): RestoreOutcome = service.restore(inspect(bytes))

    fun close() {
        env.close()
        scope.cancel()
        dir.deleteRecursively()
    }
}

/** What a reader would call "their Folirel": every table and setting that a backup carries, without ids of this install's account. */
object LogicalState {
    suspend fun of(device: BackupDevice): List<String> {
        val db = device.env.db
        val account = device.account()
        val sub = db.subscriptionDao()
        val dao = db.backupDao()
        val lines = ArrayList<String>()
        val fetched = dao.fetchedFeedIds(account).toSet()
        dao.feeds(account).forEach {
            lines += "feed|${it.id}|${it.subscriptionUrl}|${it.fetchUrl}|${it.siteUrl}|${it.feedTitle}|${it.userTitle}|${it.isSubscribed}|${it.isPaused}|${it.createdAt}|${it.id in fetched}"
        }
        sub.httpsMigrations(account).forEach { lines += "https|${it.feedId}|${it.httpUrl}|${it.httpsUrl}|${it.migratedAt}" }
        sub.urlMoves(account).forEach { lines += "move|${it.id}|${it.feedId}|${it.fromUrl}|${it.toUrl}|${it.movedAt}" }
        dao.categories(account).forEach { lines += "category|${it.id}|${it.name}|${it.pathSegmentsJson}" }
        sub.categoryPreferences(account).forEach {
            // A row of defaults and no row mean the same.
            val defaults = it.tabOrder == null && !it.isPinned && it.listModeOverride == null && it.cardStyleOverride == null && it.cardColumnsOverride == null
            if (!defaults) lines += "preference|${it.categoryId}|${it.tabOrder}|${it.isPinned}|${it.listModeOverride}|${it.cardStyleOverride}|${it.cardColumnsOverride}"
        }
        dao.memberships(account).forEach { lines += "member|${it.feedId}|${it.categoryId}" }
        var after = ""
        while (true) {
            val page = dao.articlesAfter(account, after, 1000)
            page.forEach {
                lines += "article|${it.id}|${it.feedId}|${it.entryKey}|${it.externalEntryId}|${it.linkUrl}|${it.title}|${it.summaryText}|${it.author}|" +
                    "${it.publishedAt}|${it.firstSeenAt}|${it.sortAt}|${it.imageUrl}|${it.lastSeenAt}"
            }
            if (page.size < 1000) break
            after = page.last().id
        }
        after = ""
        while (true) {
            val page = dao.statesAfter(account, after, 1000)
            page.forEach { lines += "state|${it.articleId}|${it.isRead}|${it.isSaved}|${it.updatedAt}" }
            if (page.size < 1000) break
            after = page.last().articleId
        }
        dao.pruned(account).forEach { lines += "pruned|${it.feedId}|${it.entryKey}|${it.prunedAt}|${it.lastSeenAt}" }
        lines += "settings|" + userSettings(device.settings.current())
        return lines.sorted()
    }

    /** The settings a backup carries: everything except this install's bookkeeping. */
    fun userSettings(s: AppSettings): String = s.copy(lastRefreshAttemptAt = 0, onboardingDone = false).toString()
}

/** Builds the state of a reader who has used Folirel for a while, through the app's own write paths. Returns ids worth checking. */
object RichState {
    const val HTTP_FEED = "http://old.example.org/feed.xml"
    const val HTTPS_FEED = "https://old.example.org/feed.xml"
    const val MOVED_FROM = "https://moved.example.net/rss"
    const val MOVED_TO = "https://moved.example.net/new/rss"

    val light = CustomColors(0xFF123456.toInt(), 0xFFABCDEF.toInt(), 0xFF00FF00.toInt(), 0xFFFAFAFA.toInt(), 0xFF101010.toInt())
    val dark = CustomColors(0xFFFEDCBA.toInt(), 0xFF202020.toInt(), 0xFF808000.toInt(), 0xFF000000.toInt(), 0xFFEEEEEE.toInt())

    data class Ids(val feeds: List<String>, val categories: Map<String, String>, val savedArticle: String, val removedFeedSaved: String)

    suspend fun build(d: BackupDevice): Ids {
        val account = d.account()
        val subs = d.subscriptions
        fun added(r: AddFeedResult) = (r as AddFeedResult.Added).feedId
        val news = added(subs.addFeed(account, "https://news.example.jp/rss.xml"))
        val tech = added(subs.addFeed(account, "https://tech.example.com/atom?lang=ja&x=\"q\""))
        val old = added(subs.addFeed(account, HTTP_FEED))
        val moved = added(subs.addFeed(account, MOVED_FROM))
        val paused = added(subs.addFeed(account, "https://paused.example.com/feed"))
        val gone = added(subs.addFeed(account, "https://gone.example.com/feed"))

        // Nested categories, a feed in two categories, a feed in none.
        fun ok(r: SubscriptionRepository.CategoryResult) = (r as SubscriptionRepository.CategoryResult.Ok).categoryId
        val catNews = ok(subs.createCategory(account, null, "ニュース"))
        val catDomestic = ok(subs.createCategory(account, catNews, "国内 / 地方"))
        val catTech = ok(subs.createCategory(account, null, "Tech"))
        val catEmpty = ok(subs.createCategory(account, null, "Empty \"quoted\""))
        subs.setFeedCategories(news, setOf(catNews, catDomestic))
        subs.setFeedCategories(tech, setOf(catTech, catDomestic))
        subs.setFeedCategories(old, setOf(catTech))

        // Tab order, pin, per-category layout (columns 1..5 and automatic).
        subs.reorderCategoryTabs(account, pinned = false, orderedIds = listOf(catTech, catEmpty, catNews, catDomestic))
        subs.setCategoryPinned(account, catDomestic, true)
        subs.setCategoryListLayout(account, catTech, ListMode.MASONRY)
        subs.setCategoryCardStyle(account, catTech, CardLayoutStyle.EVEN)
        subs.setCategoryCardColumns(account, catTech, 5)
        subs.setCategoryCardColumns(account, catNews, 0)

        // Identity history.
        check(subs.applyHttpsUpgrade(old, HTTP_FEED, HTTPS_FEED))
        check(subs.applyUrlMove(moved, MOVED_FROM, MOVED_TO) == SubscriptionRepository.MoveResult.Applied)

        // Articles through the refresh path, with fetch bookkeeping a backup must not carry.
        val store = d.env.store
        fun entry(feed: String, n: Int) = NormalizedEntry(
            entryKey = "id:$feed-$n",
            title = "記事 $n \"引用\" \\ <tag> & 絵文字🎉",
            linkUrl = "https://example.com/$feed/$n?a=1&b=2",
            summaryText = "要約 $n\n改行と\ttab、" + "長い本文。".repeat(n % 7 * 40),
            author = if (n % 3 == 0) null else "著者 $n",
            publishedAt = if (n % 5 == 0) null else 1_759_000_000_000L + n * 60_000L,
            imageUrl = if (n % 2 == 0) "https://img.example.com/$feed/$n.jpg" else null,
        )
        for ((i, feed) in listOf(news, tech, old, moved, paused, gone).withIndex()) {
            d.clock.advanceMinutes(1)
            store.applySuccess(feed, NormalizedFeed("Feed $i", "https://site$i.example.com/", (1..12).map { entry("f$i", it) }), FetchMetadata("\"etag-$i\"", "Mon, 01 Jan 2026 00:00:00 GMT", "https://validator.example/$i", null))
        }
        // Bookkeeping that must not travel: a failure with Retry-After, a pending request, an icon.
        store.recordFailure(tech, FetchError.RATE_LIMITED, 3_600_000L)
        store.markRequested(listOf(news), "req-1", "MANUAL", "all", false)
        d.env.db.iconDao().insertIgnore(FeedIconEntity(feedId = news, status = "OK", png = byteArrayOf(1, 2, 3), width = 1, height = 1))

        suspend fun articles(feed: String) = d.env.db.articleDao().articlesOfFeed(feed)
        val newsArticles = articles(news)
        newsArticles.take(8).forEach { d.env.articles.setRead(it.id, true) }
        val saved = newsArticles[9].id
        d.env.articles.setSaved(saved, true)
        val goneSaved = articles(gone)[0].id
        d.env.articles.setSaved(goneSaved, true)
        d.env.articles.setRead(goneSaved, true)
        articles(tech).take(10).forEach { d.env.articles.setRead(it.id, true) }

        // Pruned entries: the cleanup removes old read articles and remembers them.
        d.clock.advanceMinutes(5)
        RetentionManager(d.env.db, d.clock).apply(account, 60)
        check(d.env.db.diagnosticsDao().prunedCount() > 0)

        subs.unsubscribe(gone)
        subs.setPaused(paused, true)
        subs.renameFeed(tech, "テック（自分の名前）")

        // Every setting away from its default, Custom with hand-set light and dark colours.
        val s = d.settings
        s.setTheme(ThemeMode.DARK)
        s.applyCustomPalette(0xFF8E24AA.toInt())
        s.setCustomColors(dark = false, colors = light)
        s.setCustomColors(dark = true, colors = dark)
        s.setPalette(ColorPalette.CUSTOM)
        s.setListMode(ListMode.BENTO)
        s.setTextScale(AppSettings.textScaleOf(115))
        s.setCardColumns(4)
        s.setCardStyle(CardLayoutStyle.EVEN)
        s.setShowImages(false)
        s.setCategoryNavigation(CategoryNavigation.PANE)
        s.setBrowserMode(BrowserMode.EXTERNAL)
        s.setRefreshIntervalMinutes(360)
        s.setStartupRefresh(false)
        s.setUnmeteredOnly(false)
        s.setAllowHttp(true)
        s.setConfirmHttpOpen(false)
        s.setConfirmMeteredRefresh(false)
        s.setOnboardingDone(true)
        s.setRestoreLastScope(true)
        s.setLastScope(ArticleScope.Category(catDomestic))
        s.setLastFilter(ReadFilter.UNREAD)
        s.setSortOrder(SortOrder.OLDEST)
        s.setPeriodFilter(PeriodFilter.DAYS_7)
        s.addHiddenKeyword("広告")
        s.addHiddenKeyword("Sponsored  PR")
        s.setRetentionLimit(1_000)
        s.setImageCacheMb(256)
        s.setLastRefreshAttempt(d.clock.millis())

        return Ids(listOf(news, tech, old, moved, paused, gone), mapOf("news" to catNews, "domestic" to catDomestic, "tech" to catTech, "empty" to catEmpty), saved, goneSaved)
    }
}

/** ZIP surgery for the refusal tests: entries in file order, as text. */
object Zips {
    fun entries(bytes: ByteArray): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    fun build(entries: List<Pair<String, ByteArray>>, method: Int = ZipEntry.DEFLATED): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.compressedSize = content.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    fun buildText(entries: Map<String, String>): ByteArray = build(entries.map { it.key to it.value.toByteArray(Charsets.UTF_8) })

    /** [bytes] with one entry's text changed. */
    fun edit(bytes: ByteArray, name: String, change: (String) -> String): ByteArray {
        val e = entries(bytes)
        e[name] = change(e.getValue(name))
        return buildText(e)
    }

    /** Every occurrence of [from] in the raw ZIP bytes replaced by [to] (same length): names a ZipOutputStream would refuse. */
    fun patch(bytes: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val f = from.toByteArray()
        val t = to.toByteArray()
        val out = bytes.copyOf()
        var i = 0
        var count = 0
        while (i <= out.size - f.size) {
            if ((f.indices).all { out[i + it] == f[it] }) {
                t.copyInto(out, i)
                count++
                i += f.size
            } else {
                i++
            }
        }
        assertEquals("patched in local header and central directory", 2, count)
        return out
    }
}
