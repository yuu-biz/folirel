package io.github.yuubiz.folirel.data.repository

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2 measurement: the cost of the Home list query on a large install (200 feeds in 20 categories, 30,000 articles). It prints the
 * numbers (`probe|...` lines in the test output) and asserts only that every measured query returned the rows it should, so a number
 * never comes from a query that did less work. "one query" is the list read by a single cursor as before B2 (the same SQL, observed
 * on the same tables); "chunked" is [ArticleRepository]. The two sides alternate run by run. Robolectric's SQLite on the JVM, not a
 * phone: the numbers compare runs on one machine.
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
    private val tables = arrayOf("articles", "article_states", "feeds", "feed_categories", "categories")

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

    private enum class Side { ONE_QUERY, CHUNKED }

    private suspend fun load(side: Side, spec: ArticleQuerySpec): List<ArticleRow> = when (side) {
        Side.ONE_QUERY -> env.db.articleDao().rows(ArticleQuery.rows(spec))
        Side.CHUNKED -> env.articles.rows(spec)
    }

    private fun observe(side: Side, spec: ArticleQuerySpec): Flow<List<ArticleRow>> = when (side) {
        Side.ONE_QUERY -> env.db.invalidationTracker.createFlow(*tables).conflate().map { env.db.articleDao().rows(ArticleQuery.rows(spec)) }
        Side.CHUNKED -> env.articles.observe(spec)
    }

    private fun spec(account: String, scope: ArticleScope, filter: ReadFilter = ReadFilter.ALL) =
        ArticleQuerySpec(account, scope, filter, SortOrder.NEWEST, categoryIds = (scope as? ArticleScope.Category)?.let { listOf(it.categoryId) }.orEmpty())

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    private fun stats(samples: List<Double>): String {
        val s = samples.sorted()
        return "median %.1f ms, range %.1f..%.1f ms, n=%d".format(s[s.size / 2], s.first(), s.last(), s.size)
    }

    private fun report(name: String, samples: Map<Side, List<Double>>) {
        for ((side, values) in samples) println("probe|$name [${side.name.lowercase()}]: ${stats(values)}")
    }

    /** Both sides, alternating, after two warm-up runs each. */
    private suspend fun timeRows(spec: ArticleQuerySpec, expected: Int, runs: Int = 7): Map<Side, List<Double>> {
        val samples = Side.entries.associateWith { mutableListOf<Double>() }
        repeat(2) { Side.entries.forEach { load(it, spec) } }
        repeat(runs) {
            for (side in Side.entries) {
                val t0 = System.nanoTime()
                val rows = load(side, spec)
                val t1 = System.nanoTime()
                assertEquals(expected, rows.size)
                samples.getValue(side) += ms(t1 - t0)
            }
        }
        return samples
    }

    private fun wrapped(q: SupportSQLiteQuery, sql: (String) -> String) = object : SupportSQLiteQuery {
        override val sql = sql(q.sql)
        override val argCount = q.argCount
        override fun bindTo(statement: SupportSQLiteProgram) = q.bindTo(statement)
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

    /** Rows of one query cut at [limit]: how the cost per row grows with the size of one cursor. */
    private suspend fun timeLimited(spec: ArticleQuerySpec, limit: Int, runs: Int = 7): String {
        val q = ArticleQuery.rows(spec, limit = limit)
        repeat(2) { env.db.articleDao().rows(q) }
        val samples = (1..runs).map {
            val t0 = System.nanoTime()
            val rows = env.db.articleDao().rows(q)
            val t1 = System.nanoTime()
            assertEquals(limit, rows.size)
            ms(t1 - t0)
        }
        return "${stats(samples)}, %.1f us/row".format(samples.sorted()[runs / 2] * 1000 / limit)
    }

    private fun usedHeap(): Long {
        val r = Runtime.getRuntime()
        repeat(4) { System.gc() }
        return r.totalMemory() - r.freeMemory()
    }

    /**
     * Heap held by one loaded list: measured while it is held and again after it is released. Not a suspend function: a suspended
     * caller keeps the last result of a call reachable, so the list would never be released.
     */
    private fun heapOf(side: Side, spec: ArticleQuerySpec): Pair<Long, Long> {
        var held: List<ArticleRow>? = runBlocking { load(side, spec) }
        assertEquals(articleCount, held!!.size)
        val estimated = estimatedBytes(held)
        val holding = usedHeap()
        held = null
        return (holding - usedHeap()) to estimated
    }

    /** Character data of the rows (UTF-16 upper bound per char) plus a fixed object overhead per row and per string. */
    private fun estimatedBytes(rows: List<ArticleRow>): Long = rows.sumOf { r ->
        val strings = listOfNotNull(r.id, r.feedId, r.title, r.summaryText, r.linkUrl, r.imageUrl, r.author, r.feedName, r.feedUrl)
        80L + strings.sumOf { 40L + 2L * it.length }
    }

    /**
     * Time from a read toggle (the DAO write returning is the start) to the emission of each observed list that shows the new state.
     * Rounds alternate the sides; every round starts its own collectors and waits for their first emission before the toggle.
     */
    private suspend fun toggleLatency(account: String, observed: List<ArticleScope>, rounds: Int = 14): Map<Side, List<Double>> = coroutineScope {
        val target = "article-${feedCount + 1}" // feed-1, so in All and in cat-1
        val samples = Side.entries.associateWith { mutableListOf<Double>() }
        val perList = observed.associateWith { mutableListOf<Pair<Side, Double>>() }
        for (round in 0 until rounds) {
            val side = Side.entries[round % 2]
            val channels = observed.map { Channel<Pair<Long, List<ArticleRow>>>(Channel.UNLIMITED) }
            val jobs = observed.mapIndexed { i, scope ->
                launch(Dispatchers.Default) { observe(side, spec(account, scope)).collect { channels[i].send(System.nanoTime() to it) } }
            }
            channels.forEach { withTimeout(60_000) { it.receive() } }
            val read = env.articles.rows(spec(account, ArticleScope.All)).first { it.id == target }.isRead.not()
            val t0 = System.nanoTime()
            env.articles.setRead(target, read)
            val done = channels.map { ch ->
                var e = withTimeout(60_000) { ch.receive() }
                while (e.second.any { it.id == target } && e.second.first { it.id == target }.isRead != read) e = withTimeout(60_000) { ch.receive() }
                e.first
            }
            jobs.forEach { it.cancel() }
            observed.forEachIndexed { i, scope -> perList.getValue(scope) += side to ms(done[i] - t0) }
            samples.getValue(side) += ms(done.max() - t0)
        }
        for ((scope, values) in perList) for (side in Side.entries) {
            println("probe|  ${scope.encode()} [${side.name.lowercase()}]: ${stats(values.filter { it.first == side }.map { it.second })}")
        }
        samples
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

        val plans = listOf(
            "all" to ArticleQuery.rows(all),
            "category" to ArticleQuery.rows(category),
            "chunk after a row" to ArticleQuery.rows(all, env.articles.rows(all)[999], 1_000),
        )
        for ((name, q) in plans) {
            env.db.query(wrapped(q) { "EXPLAIN QUERY PLAN $it" }).use { c ->
                while (c.moveToNext()) println("probe|plan $name: " + c.getString(3))
            }
        }

        report("rows All ($articleCount rows)", timeRows(all, articleCount))
        println("probe|sql only All (COUNT over the same query): ${timeCount(all, articleCount)}")
        report("rows All unread ($unreadTotal rows)", timeRows(unread, unreadTotal))
        report("rows category ($perCategory rows)", timeRows(category, perCategory))
        println("probe|sql only category: ${timeCount(category, perCategory)}")
        for (limit in listOf(300, 1_000, 2_000, 5_000, 10_000, 20_000, 30_000)) println("probe|one query All LIMIT $limit: ${timeLimited(all, limit)}")

        for (side in Side.entries) {
            val (measured, estimated) = heapOf(side, all)
            println("probe|heap All rows [${side.name.lowercase()}]: measured ${measured / 1024} KiB, estimated ${estimated / 1024} KiB for $articleCount rows")
        }

        report("toggle to emission, All only", toggleLatency(account, listOf(ArticleScope.All)))
        report(
            "toggle to emission, last of pager lists (All, cat-1, cat-2)",
            toggleLatency(account, listOf(ArticleScope.All, ArticleScope.Category("cat-1"), ArticleScope.Category("cat-2"))),
        )
    }
}
