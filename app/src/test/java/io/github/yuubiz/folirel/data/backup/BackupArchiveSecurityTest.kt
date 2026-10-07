package io.github.yuubiz.folirel.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.backend.local.opml.OpmlRejectedException
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.literalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 3C refusals (T14–T26, T46): every archive that is not a sound Folirel backup is refused while it is checked, with the reason,
 * and the install it was offered to is exactly as it was. Checking and restoring never touch the network.
 */
@RunWith(AndroidJUnit4::class)
class BackupArchiveSecurityTest {
    /** A small valid backup from a used install, and an install to offer the broken variants to. */
    private fun withBackup(block: suspend CoroutineScope.(bytes: ByteArray, target: BackupDevice) -> Unit) = runBlocking {
        val source = BackupDevice()
        val target = BackupDevice(idPrefix = "target")
        try {
            RichState.build(source)
            RichState.build(target)
            target.clock.now = source.clock.now
            block(source.backupBytes(), target)
        } finally {
            source.close()
            target.close()
        }
    }

    /** T25: the archive is refused for [problem] and the target is unchanged (data and settings). */
    private suspend fun assertRefused(target: BackupDevice, bytes: ByteArray, problem: BackupProblem, detail: String? = null) {
        val before = LogicalState.of(target)
        val settingsBefore = target.settings.rawSnapshot()
        try {
            target.inspect(bytes)
            fail("accepted an archive that should be refused for $problem")
        } catch (e: BackupException) {
            assertEquals(e.message, problem, e.problem)
            if (detail != null) assertTrue("${e.message} should mention $detail", e.message.orEmpty().contains(detail))
        }
        assertEquals(before, LogicalState.of(target))
        assertEquals(settingsBefore, target.settings.rawSnapshot())
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    /** Changes the first record of an array entry. */
    private fun editFirst(bytes: ByteArray, entry: String, change: (JsonObject) -> JsonObject): ByteArray = Zips.edit(bytes, entry) { text ->
        val array = json(text).jsonArray
        JsonArray(listOf(change(array[0].jsonObject)) + array.drop(1)).toString()
    }

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))

    /** T14 / T46: an OPML file, a text file, an empty file and a ZIP of something else are not Folirel backups. */
    @Test
    fun t14_wrongFiles() = withBackup { _, target ->
        assertRefused(target, "<?xml version=\"1.0\"?><opml version=\"2.0\"><body><outline xmlUrl=\"https://a.example/\"/></body></opml>".toByteArray(), BackupProblem.NOT_A_BACKUP)
        assertRefused(target, "just text".toByteArray(), BackupProblem.NOT_A_BACKUP)
        assertRefused(target, ByteArray(0), BackupProblem.NOT_A_BACKUP)
        assertRefused(target, Zips.buildText(mapOf("word/document.xml" to "<w/>")), BackupProblem.UNSAFE_ARCHIVE)
        assertRefused(target, Zips.buildText(mapOf("readme.txt" to "hello")), BackupProblem.NOT_A_BACKUP)
        assertRefused(target, Zips.buildText(mapOf(BackupFormat.MANIFEST to "{\"format\":\"something-else\",\"formatVersion\":1}")), BackupProblem.NOT_A_BACKUP)
    }

    /** T46: a backup ZIP given to the OPML import is refused as not OPML; nothing crashes. */
    @Test
    fun t46_backupIsNotAnOpml() = withBackup { bytes, _ ->
        try {
            OpmlParser.parse(bytes)
            fail("a ZIP parsed as OPML")
        } catch (e: OpmlRejectedException) {
            // Refused with a reason the import screen shows.
        }
    }

    /** T15: broken JSON in any entry. */
    @Test
    fun t15_malformedJson() = withBackup { bytes, target ->
        for (name in BackupFormat.ENTRIES) {
            assertRefused(target, Zips.edit(bytes, name) { it.dropLast(3) }, BackupProblem.DAMAGED)
        }
        assertRefused(target, Zips.edit(bytes, BackupFormat.ARTICLES) { "$it trailing" }, BackupProblem.DAMAGED)
        assertRefused(target, Zips.edit(bytes, BackupFormat.ARTICLES) { "{}" }, BackupProblem.DAMAGED)
        assertRefused(target, Zips.edit(bytes, BackupFormat.SETTINGS) { "[]" }, BackupProblem.DAMAGED)
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLES) { it.with("title", JsonPrimitive(12)) }, BackupProblem.DAMAGED)
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLE_STATES) { it.with("read", JsonPrimitive("true")) }, BackupProblem.DAMAGED)
        // A ZIP that ends in the middle.
        assertRefused(target, bytes.copyOf(bytes.size / 2), BackupProblem.DAMAGED)
    }

    /** T16: a missing entry or a missing required field. */
    @Test
    fun t16_missingEntryOrField() = withBackup { bytes, target ->
        for (name in BackupFormat.ENTRIES - BackupFormat.MANIFEST) {
            val entries = Zips.entries(bytes).apply { remove(name) }
            val refused = runCatching { target.inspect(Zips.buildText(entries)) }.exceptionOrNull() as BackupException
            // A later entry in place of the missing one is out of order; a missing last one is missing.
            assertTrue(refused.problem == BackupProblem.DAMAGED || refused.problem == BackupProblem.UNSAFE_ARCHIVE)
        }
        assertRefused(target, Zips.buildText(Zips.entries(bytes).apply { remove(BackupFormat.MANIFEST) }), BackupProblem.NOT_A_BACKUP)
        assertRefused(target, Zips.buildText(Zips.entries(bytes).apply { remove(BackupFormat.PRUNED_ENTRIES) }), BackupProblem.DAMAGED, "missing")
        assertRefused(target, editFirst(bytes, BackupFormat.SUBSCRIPTIONS) { JsonObject(it - "fetchUrl") }, BackupProblem.DAMAGED, "missing")
        assertRefused(target, Zips.edit(bytes, BackupFormat.SETTINGS) { JsonObject(json(it).jsonObject - "customDark").toString() }, BackupProblem.DAMAGED, "missing")
    }

    /** T17: a newer format version is refused as such, before anything else of it is read. */
    @Test
    fun t17_futureFormat() = withBackup { bytes, target ->
        val future = Zips.edit(bytes, BackupFormat.MANIFEST) { json(it).jsonObject.with("formatVersion", JsonPrimitive(2)).toString() }
        assertRefused(target, future, BackupProblem.UNSUPPORTED_VERSION)
        // Even with sections this version cannot read.
        val entries = Zips.entries(future)
        entries[BackupFormat.SETTINGS] = "{\"encrypted\":true}"
        assertRefused(target, Zips.buildText(entries), BackupProblem.UNSUPPORTED_VERSION)
        assertRefused(target, Zips.edit(bytes, BackupFormat.MANIFEST) { json(it).jsonObject.with("formatVersion", JsonPrimitive(0)).toString() }, BackupProblem.UNSUPPORTED_VERSION)
    }

    /** T18: an entry that appears twice (a ZIP the standard writer refuses to make, so the name is patched in). */
    @Test
    fun t18_duplicateEntry() = withBackup { bytes, target ->
        val entries = Zips.entries(bytes)
        // Write pruned_entries.json under a name of the same length, then rename it to the name of an earlier entry.
        val list = entries.entries.map { it.key to it.value.toByteArray() }.toMutableList()
        list += "article_states.jsoX" to "[]".toByteArray()
        val patched = Zips.patch(Zips.build(list), "article_states.jsoX", "article_states.json")
        assertRefused(target, patched, BackupProblem.UNSAFE_ARCHIVE, "duplicate")
        // The manifest twice.
        val twice = Zips.patch(Zips.build(listOf(BackupFormat.MANIFEST to entries.getValue(BackupFormat.MANIFEST).toByteArray(), "manifest.jsoX" to "{}".toByteArray()) + list.drop(1).dropLast(1)), "manifest.jsoX", "manifest.json")
        assertRefused(target, twice, BackupProblem.UNSAFE_ARCHIVE, "duplicate")
    }

    /** T19: paths, absolute names, directories and unknown entries. */
    @Test
    fun t19_pathsAndUnexpectedEntries() = withBackup { bytes, target ->
        val good = Zips.entries(bytes).entries.map { it.key to it.value.toByteArray() }
        for (bad in listOf("../manifest.json", "/etc/passwd", "a/../../x.json", "..\\x.json", "C:\\x.json", "dir/", "settings.json/", "extra.json", ".hidden")) {
            assertRefused(target, Zips.build(good + (bad to "[]".toByteArray())), BackupProblem.UNSAFE_ARCHIVE)
        }
        // A traversal entry in front of everything else.
        assertRefused(target, Zips.build(listOf("../../evil.json" to "[]".toByteArray()) + good), BackupProblem.UNSAFE_ARCHIVE)
        // Entries in another order.
        val swapped = good.toMutableList().apply { add(3, removeAt(4)) }
        assertRefused(target, Zips.build(swapped), BackupProblem.UNSAFE_ARCHIVE, "order")
        // Nothing was written outside the work directory.
        assertTrue(target.workDir.parentFile!!.listFiles()!!.none { it.name == "evil.json" || it.name == "x.json" })
    }

    /** T20: a file over the size limit, an entry that inflates past its limit, and a ZIP bomb by ratio. */
    @Test
    fun t20_sizeLimitsAndZipBomb() = runBlocking {
        val source = BackupDevice()
        try {
            RichState.build(source)
            val bytes = source.backupBytes()
            fun device(limits: BackupLimits) = BackupDevice(idPrefix = "t", limits = limits)
            for ((limits, what) in listOf(
                BackupLimits(fileBytes = bytes.size - 1L) to "archive file",
                BackupLimits(entryBytes = 2_000) to "entry size",
                BackupLimits(totalBytes = 10_000) to "total size",
                BackupLimits(smallEntryBytes = 100) to "entry size",
            )) {
                val d = device(limits)
                try {
                    assertRefused(d, bytes, BackupProblem.TOO_LARGE, what)
                } finally {
                    d.close()
                }
            }
            // 64 MB of the same character in the article entry: compresses about a thousand times.
            val bomb = ByteArrayOutputStream()
            ZipOutputStream(bomb).use { zip ->
                for ((name, text) in Zips.entries(bytes)) {
                    zip.putNextEntry(ZipEntry(name))
                    if (name == BackupFormat.ARTICLES) {
                        val block = ByteArray(1024 * 1024) { ' '.code.toByte() }
                        zip.write("[".toByteArray())
                        repeat(64) { zip.write(block) }
                        zip.write("]".toByteArray())
                    } else {
                        zip.write(text.toByteArray())
                    }
                    zip.closeEntry()
                }
            }
            val d = device(BackupLimits.DEFAULT)
            try {
                assertRefused(d, bomb.toByteArray(), BackupProblem.TOO_LARGE, "ratio")
            } finally {
                d.close()
            }
            // Stored (not compressed) entries are bounded the same way.
            val stored = Zips.build(Zips.entries(bytes).entries.map { it.key to it.value.toByteArray() }, ZipEntry.STORED)
            val small = device(BackupLimits(entryBytes = 2_000))
            try {
                assertRefused(small, stored, BackupProblem.TOO_LARGE, "entry size")
            } finally {
                small.close()
            }
        } finally {
            source.close()
        }
    }

    /** T21: duplicate ids and keys. */
    @Test
    fun t21_duplicateIds() = withBackup { bytes, target ->
        fun duplicateFirst(entry: String, change: (JsonObject) -> JsonObject = { it }) = Zips.edit(bytes, entry) { text ->
            val a = json(text).jsonArray
            JsonArray(a + change(a[0].jsonObject)).toString()
        }
        assertRefused(target, duplicateFirst(BackupFormat.SUBSCRIPTIONS), BackupProblem.INVALID_DATA, "duplicate feed id")
        assertRefused(target, duplicateFirst(BackupFormat.SUBSCRIPTIONS) { it.with("id", JsonPrimitive("other-id")) }, BackupProblem.INVALID_DATA, "duplicate subscription URL")
        assertRefused(target, duplicateFirst(BackupFormat.CATEGORIES) { it.with("feedIds", JsonArray(emptyList())) }, BackupProblem.INVALID_DATA, "duplicate category id")
        assertRefused(target, duplicateFirst(BackupFormat.CATEGORIES) { it.with("id", JsonPrimitive("other-id")).with("feedIds", JsonArray(emptyList())) }, BackupProblem.INVALID_DATA, "duplicate category path")
        assertRefused(target, duplicateFirst(BackupFormat.ARTICLES), BackupProblem.INVALID_DATA, "duplicate article id")
        assertRefused(target, duplicateFirst(BackupFormat.ARTICLES) { it.with("id", JsonPrimitive("other-id")) }, BackupProblem.INVALID_DATA, "duplicate article key")
        assertRefused(target, duplicateFirst(BackupFormat.ARTICLE_STATES), BackupProblem.INVALID_DATA, "duplicate article state")
        assertRefused(target, duplicateFirst(BackupFormat.PRUNED_ENTRIES), BackupProblem.INVALID_DATA, "duplicate pruned entry")
        // A feed listed twice in one category.
        val doubled = Zips.edit(bytes, BackupFormat.CATEGORIES) { text ->
            var done = false
            JsonArray(json(text).jsonArray.map { c ->
                val ids = c.jsonObject["feedIds"]!!.jsonArray
                if (!done && ids.isNotEmpty()) {
                    done = true
                    c.jsonObject.with("feedIds", JsonArray(ids + ids[0]))
                } else {
                    c
                }
            }).toString()
        }
        assertRefused(target, doubled, BackupProblem.INVALID_DATA, "duplicate membership")
    }

    /** T22: references to things the archive does not have, and a manifest that does not match the content. */
    @Test
    fun t22_brokenReferences() = withBackup { bytes, target ->
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLES) { it.with("feedId", JsonPrimitive("missing-feed")) }, BackupProblem.INVALID_DATA, "unknown feed")
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLE_STATES) { it.with("articleId", JsonPrimitive("missing-article")) }, BackupProblem.INVALID_DATA, "unknown article")
        assertRefused(target, editFirst(bytes, BackupFormat.PRUNED_ENTRIES) { it.with("feedId", JsonPrimitive("missing-feed")) }, BackupProblem.INVALID_DATA, "unknown feed")
        assertRefused(target, editFirst(bytes, BackupFormat.CATEGORIES) { it.with("feedIds", JsonArray(listOf(JsonPrimitive("missing-feed")))) }, BackupProblem.INVALID_DATA, "unknown feed")
        // A child without its parent.
        assertRefused(target, Zips.edit(bytes, BackupFormat.CATEGORIES) { text ->
            JsonArray(json(text).jsonArray.map { c -> val o = c.jsonObject; if (o["path"]!!.jsonArray.size == 1 && o["path"]!!.jsonArray[0] == JsonPrimitive("ニュース")) o.with("path", JsonArray(listOf(JsonPrimitive("Renamed")))) else o }).toString()
        }, BackupProblem.INVALID_DATA, "parent")
        // An article without a state.
        assertRefused(target, Zips.edit(bytes, BackupFormat.ARTICLE_STATES) { JsonArray(json(it).jsonArray.drop(1)).toString() }, BackupProblem.INVALID_DATA)
        // Counts that do not match (a cut-off or edited archive).
        assertRefused(target, Zips.edit(bytes, BackupFormat.MANIFEST) { m ->
            val o = json(m).jsonObject
            o.with("counts", o["counts"]!!.jsonObject.with("savedArticles", JsonPrimitive(99))).toString()
        }, BackupProblem.INVALID_DATA, "counts")
        assertRefused(target, Zips.edit(bytes, BackupFormat.MANIFEST) { json(it).jsonObject.with("account", JsonPrimitive("feedly")).toString() }, BackupProblem.INVALID_DATA, "account")
    }

    /** T23: addresses that are not http(s), carry credentials or are not addresses; ids outside the id alphabet. */
    @Test
    fun t23_invalidUrls() = withBackup { bytes, target ->
        for (bad in listOf("file:///data/data/x", "javascript:alert(1)", "content://x/y", "ftp://example.com/feed", "https://user:pw@example.com/feed", "not a url", "")) {
            assertRefused(target, editFirst(bytes, BackupFormat.SUBSCRIPTIONS) { it.with("fetchUrl", JsonPrimitive(bad)) }, BackupProblem.INVALID_DATA, "URL")
        }
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLES) { it.with("linkUrl", JsonPrimitive("javascript:alert(1)")) }, BackupProblem.INVALID_DATA, "URL")
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLES) { it.with("imageUrl", JsonPrimitive("file:///sdcard/x.png")) }, BackupProblem.INVALID_DATA, "URL")
        assertRefused(target, editFirst(bytes, BackupFormat.SUBSCRIPTIONS) { it.with("id", JsonPrimitive("../../x")) }, BackupProblem.INVALID_DATA, "id")
        assertRefused(target, editFirst(bytes, BackupFormat.SUBSCRIPTIONS) { it.with("httpsMigration", JsonObject(mapOf("httpUrl" to JsonPrimitive("https://a.example/"), "httpsUrl" to JsonPrimitive("https://a.example/"), "migratedAt" to JsonPrimitive(1)))) }, BackupProblem.INVALID_DATA, "httpUrl")
    }

    /** T24: enum names this version does not know, columns, text size, Custom colours, keywords and timestamps out of range. */
    @Test
    fun t24_invalidValues() = withBackup { bytes, target ->
        fun setting(key: String, value: kotlinx.serialization.json.JsonElement) =
            Zips.edit(bytes, BackupFormat.SETTINGS) { json(it).jsonObject.with(key, value).toString() }
        for ((key, value) in listOf(
            "theme" to JsonPrimitive("SEPIA"),
            "palette" to JsonPrimitive("RAINBOW"),
            "listMode" to JsonPrimitive("TIMELINE"),
            "periodFilter" to JsonPrimitive("DAYS_365"),
            "cardColumns" to JsonPrimitive(6),
            "cardColumns" to JsonPrimitive(-1),
            "textScalePercent" to JsonPrimitive(117),
            "textScalePercent" to JsonPrimitive(200),
            "refreshIntervalMinutes" to JsonPrimitive(7),
            "retentionLimit" to JsonPrimitive(123),
            "imageCacheMb" to JsonPrimitive(1),
            "customSeed" to JsonPrimitive("#12345"),
            "customSeed" to JsonPrimitive("teal"),
            "customLight" to JsonObject(mapOf("accent" to JsonPrimitive("#000000"))),
            "customDark" to JsonObject(listOf("accent", "container", "secondary", "background", "text").associateWith { JsonPrimitive("#GGGGGG") }),
            "lastScope" to JsonPrimitive("somewhere"),
            "hiddenKeywords" to JsonArray(List(33) { JsonPrimitive("k$it") }),
            "hiddenKeywords" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("A"))),
            "hiddenKeywords" to JsonArray(listOf(JsonPrimitive("x".repeat(65)))),
            "hiddenKeywords" to JsonArray(listOf(JsonPrimitive("  padded  "))),
        )) {
            val refused = runCatching { target.inspect(setting(key, value)) }.exceptionOrNull()
            assertTrue("$key=$value accepted", refused is BackupException)
        }
        assertRefused(target, setting("listMode", JsonPrimitive("TIMELINE")), BackupProblem.INVALID_DATA, "unknown value")
        assertRefused(target, editFirst(bytes, BackupFormat.CATEGORIES) { it.with("cardColumns", JsonPrimitive(9)) }, BackupProblem.INVALID_DATA, "cardColumns")
        assertRefused(target, editFirst(bytes, BackupFormat.CATEGORIES) { it.with("listMode", JsonPrimitive("TIMELINE")) }, BackupProblem.INVALID_DATA, "unknown value")
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLES) { it.with("firstSeenAt", JsonPrimitive(-5)) }, BackupProblem.INVALID_DATA, "range")
        assertRefused(target, editFirst(bytes, BackupFormat.ARTICLES) { it.with("title", JsonPrimitive("x".repeat(64 * 1024 + 1))) }, BackupProblem.INVALID_DATA, "too long")
        assertRefused(target, editFirst(bytes, BackupFormat.CATEGORIES) { it.with("path", JsonArray(listOf(JsonPrimitive("")))) }, BackupProblem.INVALID_DATA, "segment")
    }

    /** T26: checking and restoring an archive whose feeds point at a server never contacts it (no network during a restore). */
    @Test
    fun t26_noNetworkWhileCheckingOrRestoring() = runBlocking {
        val server = TestEnv.startServer()
        val source = BackupDevice()
        val target = BackupDevice(idPrefix = "t")
        try {
            val account = source.account()
            source.subscriptions.addFeed(account, server.literalUrl("/feed.xml").toString())
            source.subscriptions.addFeed(account, server.literalUrl("/other.xml").toString())
            val bytes = source.backupBytes()
            assertEquals(RestoreOutcome.Restored, target.restore(bytes))
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
            source.close()
            target.close()
        }
    }

    /** T26 (static): the backup code has no way to the network; URLs are only checked as text by the URL rules. */
    @Test
    fun t26_backupCodeHasNoNetworkPath() {
        val dir = File("src/main/java/io/github/yuubiz/folirel/data/backup")
        val files = dir.listFiles()!!.filter { it.extension == "kt" }
        assertTrue(files.size >= 5)
        val imports = files.flatMap { f -> f.readLines().filter { it.startsWith("import ") }.map { "${f.name}: $it" } }
        val forbidden = imports.filter { line ->
            (line.contains("okhttp3") || line.contains(".network.") || line.contains(".sync.") || line.contains(".backend.local.Feed") || line.contains("java.net.")) &&
                !line.endsWith("import io.github.yuubiz.folirel.network.UrlNormalizer")
        }
        assertEquals(emptyList<String>(), forbidden)
    }
}
