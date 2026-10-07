package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.backend.local.opml.OpmlRejectedException
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OPML is the only way subscriptions leave the device, so export and import are checked together through the same code the
 * Subscriptions screen uses ([SubscriptionRepository.exportOpml]): empty and broken files, a realistic large file, feeds whose
 * address changed, and unsubscribed feeds.
 */
@RunWith(AndroidJUnit4::class)
class OpmlPortabilityTest {
    private val env = TestEnv()
    private val others = mutableListOf<TestEnv>()

    @After
    fun tearDown() {
        env.close()
        others.forEach { it.close() }
    }

    private fun freshEnv() = TestEnv().also { others += it }

    private suspend fun account(e: TestEnv) = e.subscriptions.ensureLocalAccount()

    private suspend fun import(e: TestEnv, xml: String) = e.subscriptions.commitImport(account(e), OpmlParser.parse(xml.toByteArray()))

    /** (subscription URL, direct category path) of every subscribed feed; uncategorized feeds have an empty path. */
    private suspend fun subscriptionSet(e: TestEnv): Set<Pair<String, List<String>>> {
        val a = account(e)
        val dao = e.db.subscriptionDao()
        val paths = dao.categories(a).associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
        val byFeed = dao.memberships(a).groupBy({ it.feedId }, { paths.getValue(it.categoryId) })
        return dao.feeds(a).filter { it.isSubscribed }.flatMap { f ->
            byFeed[f.id].orEmpty().ifEmpty { listOf(emptyList()) }.map { f.subscriptionUrl to it }
        }.toSet()
    }

    private fun rejection(bytes: ByteArray) = (runCatching { OpmlParser.parse(bytes) }.exceptionOrNull() as? OpmlRejectedException)?.reason

    @Test
    fun emptyAndBrokenFilesChangeNothing() = runBlocking<Unit> {
        import(env, """<opml version="2.0"><body><outline text="Keep" xmlUrl="https://example.com/keep.xml"/></body></opml>""")
        val before = subscriptionSet(env)

        // An OPML file without any outline is valid; the preview offers nothing to import (the screen disables Import).
        for (empty in listOf("""<opml version="2.0"><head><title>x</title></head><body/></opml>""", """<?xml version="1.0"?><opml version="1.0"><body></body></opml>""")) {
            val preview = env.subscriptions.previewImport(account(env), OpmlParser.parse(empty.toByteArray()), allowHttp = false)
            assertEquals(0, preview.totalUniqueFeeds)
            assertEquals(0, preview.newCategories)
            val result = import(env, empty)
            assertEquals(0, result.createdFeeds + result.resubscribedFeeds + result.addedMemberships + result.createdCategories)
        }
        // Broken files are refused as a whole with a reason, never half-imported and never a crash.
        assertEquals(OpmlRejectedException.Reason.MALFORMED, rejection(ByteArray(0)))
        assertEquals(OpmlRejectedException.Reason.MALFORMED, rejection("""<opml version="2.0"><body><outline text="a" xmlUrl="https://example.com/a.xml">""".toByteArray()))
        assertEquals(OpmlRejectedException.Reason.NOT_OPML, rejection("""<html><body>not opml</body></html>""".toByteArray()))
        assertEquals(OpmlRejectedException.Reason.NOT_OPML, rejection("""<opml version="2.0"><head/></opml>""".toByteArray()))
        assertEquals(OpmlRejectedException.Reason.MALFORMED, rejection(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x7F))) // a ZIP picked by mistake
        assertEquals(before, subscriptionSet(env))
    }

    @Test
    fun aRealisticLargeExportComesBackCompleteAndReimportAddsNothing() = runBlocking<Unit> {
        // 1,200 feeds: Japanese and XML-special names, three folder levels, feeds in two categories, uncategorized feeds,
        // query strings with '&', site addresses. Written by the export itself, not by hand.
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><opml version="2.0"><head><title>t</title></head><body>""")
            for (top in 0 until 6) {
                append("""<outline text="ニュース &amp; 技術 $top">""")
                for (mid in 0 until 4) {
                    append("""<outline text="&lt;中&gt; &quot;$mid&quot; 'q'">""")
                    for (leaf in 0 until 2) {
                        append("""<outline text="葉 $leaf">""")
                        repeat(20) { n ->
                            val id = "$top-$mid-$leaf-$n"
                            append("""<outline type="rss" text="記事 $id &amp; more" xmlUrl="https://feeds$top.example.com/f/$id.xml?a=1&amp;b=$n" htmlUrl="https://site$top.example.com/$id/"/>""")
                        }
                        append("</outline>")
                    }
                    append("</outline>")
                }
                append("</outline>")
            }
            // The first 100 feeds also belong to a second category.
            append("""<outline text="Also">""")
            repeat(100) { n -> append("""<outline text="x" xmlUrl="https://feeds0.example.com/f/0-${n / 40}-${(n / 20) % 2}-${n % 20}.xml?a=1&amp;b=${n % 20}"/>""") }
            append("</outline>")
            repeat(240) { n -> append("""<outline text="Top $n" xmlUrl="https://top.example.org/$n/feed"/>""") }
            append("</body></opml>")
        }
        val first = import(env, xml)
        assertEquals(1_200, first.createdFeeds)
        val original = subscriptionSet(env)
        assertEquals(1_200 + 100, original.size)

        val exported = env.subscriptions.exportOpml(account(env), createdRfc822 = null)
        assertFalse(exported.contains("<!DOCTYPE"))

        // Fresh installation: the same subscriptions, categories and site addresses come back.
        val fresh = freshEnv()
        val restored = import(fresh, exported)
        assertEquals(1_200, restored.createdFeeds)
        assertEquals(original, subscriptionSet(fresh))
        val freshFeeds = fresh.db.subscriptionDao().feeds(account(fresh)).associateBy { it.subscriptionUrl }
        val sample = freshFeeds.getValue("https://feeds3.example.com/f/3-2-1-7.xml?a=1&b=7")
        assertEquals("https://site3.example.com/3-2-1-7/", sample.siteUrl)
        assertEquals("記事 3-2-1-7 & more", sample.userTitle)
        assertTrue(subscriptionSet(fresh).any { it.second == listOf("ニュース & 技術 0", "<中> \"1\" 'q'", "葉 0") })

        // Importing the export again, in either installation, adds nothing and duplicates nothing.
        for (e in listOf(env, fresh)) {
            val again = import(e, exported)
            assertEquals(0, again.createdFeeds + again.resubscribedFeeds + again.addedMemberships + again.createdCategories)
            assertEquals(1_200, e.db.subscriptionDao().feeds(account(e)).size)
            assertEquals(original, subscriptionSet(e))
        }
    }

    @Test
    fun aFeedThatMovedToHttpsByAPermanentRedirectIsExportedWithHttps() = runBlocking<Unit> {
        // Subscribed over http://, then the server answered 301 to https:// and the fetch succeeded there.
        import(env, """<opml version="2.0"><body><outline text="Tech"><outline text="Old" xmlUrl="http://example.com/feed.xml" htmlUrl="http://example.com/"/></outline></body></opml>""")
        val dao = env.db.subscriptionDao()
        val feed = dao.feedByUrl(account(env), "http://example.com/feed.xml")!!
        env.store.applySuccess(
            feed.id,
            NormalizedFeed("Old", "https://example.com/", listOf(NormalizedEntry("id:1", "t", null, "", null, null, null))),
            FetchMetadata(null, null, "https://example.com/feed.xml", permanentRedirectUrl = "https://example.com/feed.xml"),
        )
        assertEquals("https://example.com/feed.xml", dao.feed(feed.id)!!.fetchUrl)

        val exported = env.subscriptions.exportOpml(account(env), createdRfc822 = null)
        assertTrue(exported, exported.contains("xmlUrl=\"https://example.com/feed.xml\""))
        assertFalse(exported, exported.contains("http://example.com/feed.xml"))

        // On a new device with HTTP off nothing is held back, and the feed is in its category.
        val fresh = freshEnv()
        val preview = fresh.subscriptions.previewImport(account(fresh), OpmlParser.parse(exported.toByteArray()), allowHttp = false)
        assertEquals(0, preview.httpPending)
        import(fresh, exported)
        assertEquals(setOf("https://example.com/feed.xml" to listOf("Tech")), subscriptionSet(fresh))

        // Here, both the old http:// file and the new file still find the same feed: no duplicate, no step back to http://.
        for (file in listOf("""<opml version="2.0"><body><outline text="Old" xmlUrl="http://example.com/feed.xml"/></body></opml>""", exported)) {
            val again = import(env, file)
            assertEquals(0, again.createdFeeds)
            assertEquals(1, dao.feeds(account(env)).size)
            assertEquals("https://example.com/feed.xml", dao.feed(feed.id)!!.fetchUrl)
        }
    }

    @Test
    fun aFeedStillOnHttpOrRedirectedElsewhereKeepsItsRegisteredAddress() = runBlocking<Unit> {
        // Only an http:// to https:// step of the same address is written as https://: other permanent redirects keep the
        // address the reader subscribed to (it stays the import identity and still leads to the feed).
        import(
            env,
            """<opml version="2.0"><body>
               <outline text="Plain" xmlUrl="http://plain.example.com/rss"/>
               <outline text="Moved" xmlUrl="https://old.example.com/rss"/>
               </body></opml>""",
        )
        val dao = env.db.subscriptionDao()
        val moved = dao.feedByUrl(account(env), "https://old.example.com/rss")!!
        env.store.applySuccess(moved.id, NormalizedFeed("Moved", null, emptyList()), FetchMetadata(null, null, "https://new.example.net/rss", "https://new.example.net/rss"))

        val exported = env.subscriptions.exportOpml(account(env), createdRfc822 = null)
        assertTrue(exported, exported.contains("xmlUrl=\"http://plain.example.com/rss\""))
        assertTrue(exported, exported.contains("xmlUrl=\"https://old.example.com/rss\""))
        assertFalse(exported, exported.contains("new.example.net"))
    }

    @Test
    fun unsubscribedFeedsAreNotExportedAndAnOlderFileNamesThemAgainOnPurpose() = runBlocking<Unit> {
        val file = """<opml version="2.0"><body><outline text="C">
            <outline text="Stay" xmlUrl="https://example.com/stay.xml"/>
            <outline text="Gone" xmlUrl="https://example.com/gone.xml"/>
            </outline></body></opml>"""
        import(env, file)
        val dao = env.db.subscriptionDao()
        val gone = dao.feedByUrl(account(env), "https://example.com/gone.xml")!!
        env.subscriptions.unsubscribe(gone.id)

        val exported = env.subscriptions.exportOpml(account(env), createdRfc822 = null)
        assertFalse(exported.contains("gone.xml"))
        // Re-importing the new file leaves the unsubscribed feed alone.
        import(env, exported)
        assertFalse(dao.feed(gone.id)!!.isSubscribed)

        // The older file still names it: the preview says so before anything is written, and the same row comes back.
        val preview = env.subscriptions.previewImport(account(env), OpmlParser.parse(file.toByteArray()), allowHttp = false)
        assertEquals(1, preview.resubscribeFeeds)
        assertEquals(listOf("Gone"), preview.resubscribeSamples)
        val result = import(env, file)
        assertEquals(1, result.resubscribedFeeds)
        assertEquals(0, result.createdFeeds)
        assertTrue(dao.feed(gone.id)!!.isSubscribed)
        assertNull(dao.feedByUrl(account(env), "https://example.com/gone.xml")?.takeIf { it.id != gone.id })
    }
}
