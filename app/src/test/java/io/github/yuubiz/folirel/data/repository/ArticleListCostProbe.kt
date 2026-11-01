package io.github.yuubiz.folirel.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.db.ArticleEntity
import io.github.yuubiz.folirel.data.db.ArticleStateEntity
import io.github.yuubiz.folirel.data.db.CategoryEntity
import io.github.yuubiz.folirel.data.db.FeedCategoryEntity
import io.github.yuubiz.folirel.data.db.FeedEntity
import io.github.yuubiz.folirel.data.db.FeedFetchStateEntity
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2 measurement: the cost of the Home list query on a large install (200 feeds in 20 categories, 30,000 articles). It prints the
 * numbers (`probe|...` lines in the test output) and asserts only that every measured query returned the rows it should, so a number
 * never comes from a query that did less work. Robolectric's SQLite on the JVM, not a phone: the numbers compare runs on one machine.
 */
@RunWith(AndroidJUnit4::class)
class ArticleListCostProbe {
    private val env = TestEnv()

    @After
    fun tearDown() = env.close()

    private val articleCount = 30_000
    private val feedCount = 200
    private val categoryCount = 20
    private val base = 1_760_000_000_000L

    private suspend fun seed(): String {
        val account = env.subscriptions.ensureLocalAccount()
        val dao = env.db.backupDao()
        dao.insertCategories((1..categoryCount).map { CategoryEntity("cat-$it", account, "カテゴリ $it", "[\"カテゴリ $it\"]") })
        val feeds = (1..feedCount).map { i ->
            FeedEntity("feed-$i", account, "https://f$i.example.jp/rss", "https://f$i.example.jp/rss", "https://f$i.example.jp/", "フィード $i", null, true, false, base + i)
        }
        dao.insertFeeds(feeds)
        dao.insertFetchStates(feeds.map { FeedFetchStateEntity(feedId = it.id, initialized = true) })
        dao.insertMemberships((1..feedCount).map { FeedCategoryEntity("feed-$it", "cat-${(it - 1) % categoryCount + 1}") })
        val summary = "日本語の要約です。記事の内容を短く伝える文章が続きます。Some English words too. ".repeat(40)
        for (chunk in (1..articleCount).chunked(1_000)) {
            dao.insertArticles(chunk.map { n ->
                val t = base + n * 60_000L
                ArticleEntity(
                    "article-$n", account, "feed-${(n - 1) % feedCount + 1}", "id:urn:$n", "urn:$n", "https://f.example.jp/articles/2026/10/$n-slug-of-the-article",
                    "記事のタイトル $n: 長めの見出しが一行に収まらないこともある", summary.take(80 + n % 1_200), if (n % 4 == 0) null else "著者 $n",
                    t, t, t, if (n % 2 == 0) "https://img.example.jp/$n/large.jpg" else null, t,
                )
            })
            dao.insertArticleStates(chunk.map { n -> ArticleStateEntity("article-$n", isRead = n % 3 != 0, isSaved = n % 97 == 0, updatedAt = base) })
        }
        return account
    }

    private fun spec(account: String, scope: ArticleScope, filter: ReadFilter = ReadFilter.ALL) =
        ArticleQuerySpec(account, scope, filter, SortOrder.NEWEST, categoryIds = (scope as? ArticleScope.Category)?.let { listOf(it.categoryId) }.orEmpty())

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    private fun stats(samples: List<Double>): String {
        val s = samples.sorted()
        return "median %.1f ms, range %.1f..%.1f ms, n=%d".format(s[s.size / 2], s.first(), s.last(), s.size)
    }

    private suspend fun timeRows(spec: ArticleQuerySpec, expected: Int, runs: Int = 7): String {
        repeat(2) { env.articles.rows(spec) }
        val samples = (1..runs).map {
            val t0 = System.nanoTime()
            val rows = env.articles.rows(spec)
            val t1 = System.nanoTime()
            assertEquals(expected, rows.size)
            ms(t1 - t0)
        }
        return stats(samples)
    }

    /** The same WHERE and ORDER BY stepped by SQLite without building rows: what the SQL itself costs. */
    private suspend fun timeCount(spec: ArticleQuerySpec, expected: Int, runs: Int = 7): String {
        val count = wrapped(ArticleQuery.rows(spec)) { "SELECT COUNT(*) FROM ($it)" }
        val samples = (1..runs + 2).map {
            val t0 = System.nanoTime()
            val n = env.db.articleDao().ids(count).single().toInt()
            val t1 = System.nanoTime()
            assertEquals(expected, n)
            ms(t1 - t0)
        }.drop(2)
        return stats(samples)
    }

    private fun wrapped(q: SupportSQLiteQuery, sql: (String) -> String) = object : SupportSQLiteQuery {
        override val sql = sql(q.sql)
        override val argCount = q.argCount
        override fun bindTo(statement: SupportSQLiteProgram) = q.bindTo(statement)
    }

    private fun usedHeap(): Long {
        val r = Runtime.getRuntime()
        repeat(4) { System.gc() }
        return r.totalMemory() - r.freeMemory()
    }

    /** Character data of the rows (UTF-16 upper bound per char) plus a fixed object overhead per row and per string. */
    private fun estimatedBytes(rows: List<ArticleRow>): Long = rows.sumOf { r ->
        val strings = listOfNotNull(r.id, r.feedId, r.title, r.summaryText, r.linkUrl, r.imageUrl, r.author, r.feedName, r.feedUrl)
        80L + strings.sumOf { 40L + 2L * it.length }
    }

    /** Time from a read toggle (the DAO write returning is the start) to the emission of each observed list that shows it. */
    private suspend fun toggleLatency(account: String, observed: List<ArticleScope>, rounds: Int = 9): String = kotlinx.coroutines.coroutineScope {
        val specs = observed.map { spec(account, it) }
        val channels = specs.map { Channel<Pair<Long, List<ArticleRow>>>(Channel.UNLIMITED) }
        val jobs = specs.mapIndexed { i, s ->
            launch(Dispatchers.Default) { env.articles.observe(s).collect { channels[i].send(System.nanoTime() to it) } }
        }
        channels.forEach { withTimeout(60_000) { it.receive() } }
        // An article of cat-1 (feed-1) is in the All list and in the cat-1 list.
        val perList = observed.map { mutableListOf<Double>() }
        val all = mutableListOf<Double>()
        for (round in 0 until rounds) {
            val read = round % 2 == 0
            val t0 = System.nanoTime()
            env.articles.setRead("article-${feedCount + 1}", read)
            val done = channels.map { ch ->
                var e = withTimeout(60_000) { ch.receive() }
                // Wait for the emission that shows the new state (an earlier, stale one can still be in flight).
                while (e.second.firstOrNull { it.id == "article-${feedCount + 1}" }?.isRead != read && e.second.any { it.id == "article-${feedCount + 1}" }) {
                    e = withTimeout(60_000) { ch.receive() }
                }
                e.first
            }
            done.forEachIndexed { i, t -> perList[i] += ms(t - t0) }
            all += ms(done.max() - t0)
        }
        jobs.forEach { it.cancel() }
        observed.indices.joinToString("; ") { "${observed[it].encode()} ${stats(perList[it])}" } + "; last of ${observed.size} lists ${stats(all)}"
    }

    /** The same rows read in keyset chunks of [chunk] rows inside one transaction: separates the cost per row from the size of one cursor. */
    private suspend fun timeChunked(spec: ArticleQuerySpec, chunk: Int, expected: Int, runs: Int = 7): String {
        val base = ArticleQuery.rows(spec)
        val (head, order) = base.sql.split(" ORDER BY ")
        suspend fun load(): List<ArticleRow> = env.db.withTransaction {
            val out = ArrayList<ArticleRow>()
            do {
                val last = out.lastOrNull()
                val q = object : SupportSQLiteQuery {
                    override val sql = head + (if (last == null) "" else " AND (a.sortAt < ? OR (a.sortAt = ? AND a.id < ?))") + " ORDER BY $order LIMIT $chunk"
                    override val argCount = base.argCount + if (last == null) 0 else 3
                    override fun bindTo(statement: SupportSQLiteProgram) {
                        base.bindTo(statement)
                        if (last != null) {
                            statement.bindLong(base.argCount + 1, last.sortAt)
                            statement.bindLong(base.argCount + 2, last.sortAt)
                            statement.bindString(base.argCount + 3, last.id)
                        }
                    }
                }
                val page = env.db.articleDao().rows(q)
                out += page
            } while (page.size == chunk)
            out
        }
        repeat(2) { load() }
        val samples = (1..runs).map {
            val t0 = System.nanoTime()
            val rows = load()
            val t1 = System.nanoTime()
            assertEquals(expected, rows.size)
            assertEquals(rows.map { it.id }.distinct().size, rows.size)
            ms(t1 - t0)
        }
        return stats(samples)
    }

    /** Rows of the All query cut at [limit]: how the cost per row grows with the size of the result. */
    private suspend fun timeLimited(spec: ArticleQuerySpec, limit: Int, runs: Int = 7): String {
        val q = wrapped(ArticleQuery.rows(spec)) { "$it LIMIT $limit" }
        repeat(2) { env.db.articleDao().rows(q) }
        val samples = (1..runs).map {
            val t0 = System.nanoTime()
            val rows = env.db.articleDao().rows(q)
            val t1 = System.nanoTime()
            assertEquals(limit, rows.size)
            ms(t1 - t0)
        }
        val median = samples.sorted()[runs / 2]
        return "${stats(samples)}, %.1f us/row".format(median * 1000 / limit)
    }

    @Test
    fun measureListCost() = runBlocking(Dispatchers.IO) {
        val account = seed()
        val version = env.db.articleDao().ids(SimpleSQLiteQuery("SELECT sqlite_version()")).single()
        println("probe|sqlite $version, ${Runtime.getRuntime().availableProcessors()} cpus, max heap ${Runtime.getRuntime().maxMemory() / 1_048_576} MiB")

        val all = spec(account, ArticleScope.All)
        val unread = spec(account, ArticleScope.All, ReadFilter.UNREAD)
        val category = spec(account, ArticleScope.Category("cat-1"))
        val perCategory = articleCount / categoryCount
        val unreadTotal = articleCount / 3

        val plan = env.db.query(ArticleQuery.rows(all)).use { it.count }
        assertEquals(articleCount, plan)
        for ((name, s) in listOf("all" to all, "category" to category)) {
            env.db.query(wrapped(ArticleQuery.rows(s)) { "EXPLAIN QUERY PLAN $it" }).use { c ->
                while (c.moveToNext()) println("probe|plan $name: " + c.getString(3))
            }
        }

        println("probe|rows All: ${timeRows(all, articleCount)} ($articleCount rows)")
        println("probe|sql only All (COUNT over the same query): ${timeCount(all, articleCount)}")
        println("probe|rows All unread: ${timeRows(unread, unreadTotal)} ($unreadTotal rows)")
        for (limit in listOf(300, 2_000, 5_000, 10_000, 20_000, 30_000)) println("probe|rows All LIMIT $limit: ${timeLimited(all, limit)}")
        for (chunk in listOf(500, 1_000, 1_500, 2_000, 3_000)) println("probe|rows All in chunks of $chunk: ${timeChunked(all, chunk, articleCount)}")
        println("probe|rows category: ${timeRows(category, perCategory)} ($perCategory rows)")
        println("probe|sql only category: ${timeCount(category, perCategory)}")

        val heapBefore = usedHeap()
        val held = env.articles.rows(all)
        val heapAfter = usedHeap()
        assertTrue(held.size == articleCount)
        println("probe|heap All rows: measured ${(heapAfter - heapBefore) / 1024} KiB, estimated ${estimatedBytes(held) / 1024} KiB for ${held.size} rows")

        println("probe|toggle All only: ${toggleLatency(account, listOf(ArticleScope.All))}")
        println("probe|toggle pager (All, cat-1, cat-2): ${toggleLatency(account, listOf(ArticleScope.All, ArticleScope.Category("cat-1"), ArticleScope.Category("cat-2")))}")
    }
}
