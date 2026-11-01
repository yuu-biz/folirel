package io.github.yuubiz.folirel.data.backup

import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.data.preferences.CategoryNavigation
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.CustomColors
import io.github.yuubiz.folirel.data.preferences.SwipeActions
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.HiddenKeywords
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.network.UrlNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * JSON form of the backup records, and the checks of a single record. Reading is strict: a missing required field, a wrong type, an
 * unknown enum name, an invalid URL or a value out of range refuses the archive; nothing is rounded to a default (the one exception is
 * [ScopeRef]: a last selection that names something missing from the archive becomes All, see [BackupValidator]). Fields this version
 * does not know are ignored, so a later version can add optional fields without a new format version.
 *
 * Every check here is local to the record; duplicates and references between records are [BackupValidator]'s job.
 */
internal object BackupJson {
    /** Room ids are UUIDs; anything outside this alphabet is not an id Folirel wrote. */
    private val ID = Regex("[A-Za-z0-9._:-]{1,128}")

    /**
     * Safety bounds only, far above the parser's limits: text an older version stored (or a long name the reader typed) must never make
     * the reader's own backup unrestorable. The entry size limits bound the archive as a whole.
     */
    private const val TEXT_CHARS = 64 * 1024
    private const val KEY_CHARS = 64 * 1024
    private const val MAX_CATEGORY_DEPTH = 64

    /** Timestamps are UTC epoch milliseconds; a feed's own publication date may lie far back (year 1). */
    private const val MIN_EPOCH_MS = -62_135_596_800_000L
    private const val MAX_EPOCH_MS = 253_402_300_799_999L // 9999-12-31T23:59:59.999Z

    // ---- writing ----

    fun manifest(m: BackupManifest): JsonObject = buildJsonObject {
        put("format", m.format)
        put("formatVersion", m.formatVersion)
        put("createdAt", m.createdAt)
        put("createdAtUtc", Instant.ofEpochMilli(m.createdAt).toString())
        put("account", BackupFormat.ACCOUNT_LOCAL)
        put("app", buildJsonObject {
            put("versionName", m.appVersionName)
            put("versionCode", m.appVersionCode)
        })
        put("schemaVersion", m.schemaVersion)
        put("counts", buildJsonObject {
            val c = m.counts
            put("feeds", c.feeds)
            put("subscribedFeeds", c.subscribedFeeds)
            put("categories", c.categories)
            put("memberships", c.memberships)
            put("articles", c.articles)
            put("readArticles", c.readArticles)
            put("savedArticles", c.savedArticles)
            put("prunedEntries", c.prunedEntries)
            put("hiddenKeywords", c.hiddenKeywords)
        })
    }

    fun settings(s: AppSettings): JsonObject = buildJsonObject {
        put("theme", s.theme.name)
        put("palette", s.palette.name)
        put("customSeed", CustomColors.hex(s.customSeed))
        put("customLight", s.customLight?.let(::colors) ?: JsonNull)
        put("customDark", s.customDark?.let(::colors) ?: JsonNull)
        put("listMode", s.listMode.name)
        put("textScalePercent", AppSettings.textScalePercent(s.textScale))
        put("cardColumns", s.cardColumns)
        put("cardStyle", s.cardStyle.name)
        put("showImages", s.showImages)
        put("categoryNavigation", s.categoryNavigation.name)
        put("browserMode", s.browserMode.name)
        put("swipeActions", s.swipeActions.name)
        put("refreshIntervalMinutes", s.refreshIntervalMinutes)
        put("startupRefresh", s.startupRefresh)
        put("unmeteredOnly", s.unmeteredOnly)
        put("allowHttp", s.allowHttp)
        put("confirmHttpOpen", s.confirmHttpOpen)
        put("confirmMeteredRefresh", s.confirmMeteredRefresh)
        put("restoreLastScope", s.restoreLastScope)
        put("lastScope", s.lastScope.encode())
        put("lastFilter", s.lastFilter.name)
        put("sortOrder", s.sortOrder.name)
        put("periodFilter", s.periodFilter.name)
        put("hiddenKeywords", buildJsonArray { s.hiddenKeywords.forEach { add(JsonPrimitive(it)) } })
        put("hiddenKeywordsEnabled", s.hiddenKeywordsEnabled)
        put("retentionLimit", s.retentionLimit)
        put("imageCacheMb", s.imageCacheMb)
    }

    private fun colors(c: CustomColors): JsonObject = buildJsonObject {
        put("accent", CustomColors.hex(c.accent))
        put("container", CustomColors.hex(c.accentContainer))
        put("secondary", CustomColors.hex(c.secondary))
        put("background", CustomColors.hex(c.background))
        put("text", CustomColors.hex(c.text))
    }

    fun feed(f: BackupFeed): JsonObject = buildJsonObject {
        put("id", f.id)
        put("subscriptionUrl", f.subscriptionUrl)
        put("fetchUrl", f.fetchUrl)
        put("siteUrl", f.siteUrl)
        put("feedTitle", f.feedTitle)
        put("userTitle", f.userTitle)
        put("subscribed", f.subscribed)
        put("paused", f.paused)
        put("createdAt", f.createdAt)
        put("fetchedBefore", f.fetchedBefore)
        put("httpsMigration", f.httpsMigration?.let { m ->
            buildJsonObject {
                put("httpUrl", m.httpUrl)
                put("httpsUrl", m.httpsUrl)
                put("migratedAt", m.migratedAt)
            }
        } ?: JsonNull)
        put("urlMoves", buildJsonArray {
            f.urlMoves.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    put("fromUrl", m.fromUrl)
                    put("toUrl", m.toUrl)
                    put("movedAt", m.movedAt)
                })
            }
        })
    }

    fun category(c: BackupCategory): JsonObject = buildJsonObject {
        put("id", c.id)
        put("path", buildJsonArray { c.path.forEach { add(JsonPrimitive(it)) } })
        put("feedIds", buildJsonArray { c.feedIds.forEach { add(JsonPrimitive(it)) } })
        put("tabOrder", c.tabOrder)
        put("pinned", c.pinned)
        put("listMode", c.listMode?.name)
        put("cardStyle", c.cardStyle?.name)
        put("cardColumns", c.cardColumns)
    }

    fun article(a: BackupArticle): JsonObject = buildJsonObject {
        put("id", a.id)
        put("feedId", a.feedId)
        put("entryKey", a.entryKey)
        put("externalEntryId", a.externalEntryId)
        put("linkUrl", a.linkUrl)
        put("title", a.title)
        put("summaryText", a.summaryText)
        put("author", a.author)
        put("publishedAt", a.publishedAt)
        put("firstSeenAt", a.firstSeenAt)
        put("sortAt", a.sortAt)
        put("imageUrl", a.imageUrl)
        put("lastSeenAt", a.lastSeenAt)
    }

    fun state(s: BackupArticleState): JsonObject = buildJsonObject {
        put("articleId", s.articleId)
        put("read", s.read)
        put("saved", s.saved)
        put("updatedAt", s.updatedAt)
    }

    fun pruned(p: BackupPrunedEntry): JsonObject = buildJsonObject {
        put("feedId", p.feedId)
        put("entryKey", p.entryKey)
        put("prunedAt", p.prunedAt)
        put("lastSeenAt", p.lastSeenAt)
    }

    // ---- reading ----

    /** The manifest's format and version only, so that a future archive is refused as such before anything else is read. */
    fun manifestVersion(e: JsonElement): Int {
        val o = e as? JsonObject ?: refuse(BackupProblem.NOT_A_BACKUP, "manifest is not an object")
        val format = (o["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != BackupFormat.FORMAT) refuse(BackupProblem.NOT_A_BACKUP, "not a Folirel backup")
        val version = (o["formatVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: refuse(BackupProblem.DAMAGED, "formatVersion")
        return version
    }

    fun readManifest(e: JsonElement): BackupManifest {
        val o = Obj(e, "manifest")
        val account = o.string("account")
        if (account != BackupFormat.ACCOUNT_LOCAL) invalid("account $account")
        val app = o.obj("app")
        val counts = o.obj("counts")
        return BackupManifest(
            format = o.string("format"),
            formatVersion = o.int("formatVersion"),
            createdAt = o.time("createdAt"),
            appVersionName = app.string("versionName").also { if (it.length > 64) invalid("app.versionName too long") },
            appVersionCode = app.int("versionCode"),
            schemaVersion = o.int("schemaVersion"),
            counts = BackupCounts(
                feeds = counts.count("feeds"),
                subscribedFeeds = counts.count("subscribedFeeds"),
                categories = counts.count("categories"),
                memberships = counts.count("memberships"),
                articles = counts.count("articles"),
                readArticles = counts.count("readArticles"),
                savedArticles = counts.count("savedArticles"),
                prunedEntries = counts.count("prunedEntries"),
                hiddenKeywords = counts.count("hiddenKeywords"),
            ),
        )
    }

    /** The settings, with [AppSettings.lastScope] still unchecked against the archive's feeds and categories. */
    fun readSettings(e: JsonElement): AppSettings {
        val o = Obj(e, "settings")
        val keywords = o.array("hiddenKeywords").map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("hidden keyword") }
        checkKeywords(keywords)
        val textPercent = o.int("textScalePercent")
        val textScale = AppSettings.textScaleOf(textPercent)
        if (!AppSettings.isTextScale(textScale)) invalid("textScalePercent $textPercent")
        val defaults = AppSettings()
        return defaults.copy(
            theme = o.enum<ThemeMode>("theme"),
            palette = o.enum<ColorPalette>("palette"),
            customSeed = o.color("customSeed"),
            customLight = o.nullableObj("customLight")?.let(::readColors),
            customDark = o.nullableObj("customDark")?.let(::readColors),
            listMode = o.enum<ListMode>("listMode"),
            textScale = textScale,
            cardColumns = o.int("cardColumns").also { if (it !in AppSettings.CARD_COLUMNS) invalid("cardColumns $it") },
            cardStyle = o.enum<CardLayoutStyle>("cardStyle"),
            showImages = o.bool("showImages"),
            categoryNavigation = o.enum<CategoryNavigation>("categoryNavigation"),
            browserMode = o.enum<BrowserMode>("browserMode"),
            // Added in 0.14, after format version 1 was first written: absent reads as the default.
            swipeActions = o.optionalEnum("swipeActions", defaults.swipeActions),
            refreshIntervalMinutes = o.int("refreshIntervalMinutes").also { if (it !in AppSettings.REFRESH_INTERVALS_MINUTES) invalid("refreshIntervalMinutes $it") },
            startupRefresh = o.bool("startupRefresh"),
            unmeteredOnly = o.bool("unmeteredOnly"),
            allowHttp = o.bool("allowHttp"),
            confirmHttpOpen = o.bool("confirmHttpOpen"),
            confirmMeteredRefresh = o.bool("confirmMeteredRefresh"),
            // Added in 0.10.1, after format version 1 was first written (0.10.0 archives do not have it): absent reads as the default.
            restoreLastScope = o.optionalBool("restoreLastScope", defaults.restoreLastScope),
            lastScope = readScope(o.string("lastScope")),
            lastFilter = o.enum<ReadFilter>("lastFilter"),
            sortOrder = o.enum<SortOrder>("sortOrder"),
            periodFilter = o.enum<PeriodFilter>("periodFilter"),
            hiddenKeywords = keywords,
            hiddenKeywordsEnabled = o.bool("hiddenKeywordsEnabled"),
            retentionLimit = o.int("retentionLimit").also { if (it !in AppSettings.RETENTION_LIMITS) invalid("retentionLimit $it") },
            imageCacheMb = o.int("imageCacheMb").also { if (it !in AppSettings.IMAGE_CACHE_MB) invalid("imageCacheMb $it") },
            // Device bookkeeping, never in an archive: kept from the device / set by the restore itself.
            onboardingDone = defaults.onboardingDone,
            lastRefreshAttemptAt = defaults.lastRefreshAttemptAt,
        )
    }

    /** The keywords exactly as the app would have stored them: normalized, unique, within the count and length limits. */
    private fun checkKeywords(keywords: List<String>) {
        if (keywords.size > HiddenKeywords.MAX_COUNT) invalid("too many hidden keywords")
        var list = emptyList<String>()
        for (k in keywords) {
            val added = HiddenKeywords.add(list, k) as? HiddenKeywords.AddResult.Added ?: invalid("hidden keyword")
            if (added.list.last() != k) invalid("hidden keyword not normalized")
            list = added.list
        }
    }

    private fun readColors(o: Obj): CustomColors = CustomColors(
        accent = o.color("accent"),
        accentContainer = o.color("container"),
        secondary = o.color("secondary"),
        background = o.color("background"),
        text = o.color("text"),
    )

    /** The forms [ArticleScope.encode] writes; anything else is refused (decode would turn it into All without a word). */
    private fun readScope(value: String): ArticleScope = when {
        value == "all" -> ArticleScope.All
        value == "uncategorized" -> ArticleScope.Uncategorized
        value.startsWith("category:") -> ArticleScope.Category(checkId(value.removePrefix("category:")))
        value.startsWith("feed:") -> ArticleScope.Feed(checkId(value.removePrefix("feed:")))
        else -> invalid("lastScope")
    }

    fun readFeed(e: JsonElement): BackupFeed {
        val o = Obj(e, "feed")
        val migration = o.nullableObj("httpsMigration")?.let { m ->
            BackupHttpsMigration(
                httpUrl = m.url("httpUrl").also { if (!it.startsWith("http://")) invalid("httpsMigration.httpUrl") },
                httpsUrl = m.url("httpsUrl").also { if (!it.startsWith("https://")) invalid("httpsMigration.httpsUrl") },
                migratedAt = m.time("migratedAt"),
            )
        }
        val moves = o.array("urlMoves").map { el ->
            val m = Obj(el, "urlMove")
            BackupUrlMove(m.id("id"), m.url("fromUrl"), m.url("toUrl"), m.time("movedAt"))
        }
        return BackupFeed(
            id = o.id("id"),
            subscriptionUrl = o.url("subscriptionUrl"),
            fetchUrl = o.url("fetchUrl"),
            siteUrl = o.nullableUrl("siteUrl"),
            feedTitle = o.nullableText("feedTitle"),
            userTitle = o.nullableText("userTitle"),
            subscribed = o.bool("subscribed"),
            paused = o.bool("paused"),
            createdAt = o.time("createdAt"),
            fetchedBefore = o.bool("fetchedBefore"),
            httpsMigration = migration,
            urlMoves = moves,
        )
    }

    fun readCategory(e: JsonElement): BackupCategory {
        val o = Obj(e, "category")
        val path = o.array("path").map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("category path") }
        if (path.isEmpty() || path.size > MAX_CATEGORY_DEPTH) invalid("category path depth")
        for (segment in path) {
            // Only what the structure needs: the database keeps the path as it is, and a name stored by an older version (whatever its
            // spaces or normalization) must not make the reader's own backup unrestorable.
            if (segment.isEmpty()) invalid("empty category segment")
            checkText(segment, "category segment")
        }
        return BackupCategory(
            id = o.id("id"),
            path = path,
            feedIds = o.array("feedIds").map { checkId((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("feedIds")) },
            tabOrder = o.nullableInt("tabOrder"),
            pinned = o.bool("pinned"),
            listMode = o.nullableEnum<ListMode>("listMode"),
            cardStyle = o.nullableEnum<CardLayoutStyle>("cardStyle"),
            cardColumns = o.nullableInt("cardColumns")?.also { if (it !in AppSettings.CARD_COLUMNS) invalid("category cardColumns $it") },
        )
    }

    fun readArticle(e: JsonElement): BackupArticle {
        val o = Obj(e, "article")
        return BackupArticle(
            id = o.id("id"),
            feedId = o.id("feedId"),
            entryKey = o.key("entryKey"),
            externalEntryId = o.nullableString("externalEntryId")?.also { checkKey(it) },
            linkUrl = o.nullableUrl("linkUrl"),
            title = o.text("title"),
            summaryText = o.text("summaryText"),
            author = o.nullableText("author"),
            publishedAt = o.nullableLong("publishedAt")?.also { checkTime(it, MIN_EPOCH_MS, "publishedAt") },
            firstSeenAt = o.time("firstSeenAt"),
            sortAt = o.long("sortAt").also { checkTime(it, MIN_EPOCH_MS, "sortAt") },
            imageUrl = o.nullableUrl("imageUrl"),
            lastSeenAt = o.time("lastSeenAt"),
        )
    }

    fun readState(e: JsonElement): BackupArticleState {
        val o = Obj(e, "article state")
        return BackupArticleState(o.id("articleId"), o.bool("read"), o.bool("saved"), o.time("updatedAt"))
    }

    fun readPruned(e: JsonElement): BackupPrunedEntry {
        val o = Obj(e, "pruned entry")
        return BackupPrunedEntry(o.id("feedId"), o.key("entryKey"), o.time("prunedAt"), o.time("lastSeenAt"))
    }

    private fun checkId(value: String): String = value.takeIf { ID.matches(it) } ?: invalid("id")

    private fun checkKey(value: String) {
        if (value.isEmpty() || value.length > KEY_CHARS) invalid("key length")
    }

    private fun checkText(value: String, what: String) {
        if (value.length > TEXT_CHARS) invalid("$what too long")
    }

    private fun checkTime(value: Long, min: Long, what: String) {
        if (value < min || value > MAX_EPOCH_MS) invalid("$what out of range")
    }

    /** Typed access to one JSON object; a missing field or a wrong type is a damaged archive, a bad value invalid data. */
    private class Obj(element: JsonElement, private val what: String) {
        private val o = element as? JsonObject ?: refuse(BackupProblem.DAMAGED, "$what is not an object")

        private fun field(name: String): JsonElement = o[name] ?: refuse(BackupProblem.DAMAGED, "$what.$name missing")

        private fun primitive(name: String): JsonPrimitive? = when (val v = field(name)) {
            JsonNull -> null
            is JsonPrimitive -> v
            else -> refuse(BackupProblem.DAMAGED, "$what.$name type")
        }

        fun nullableString(name: String): String? = primitive(name)?.let { p ->
            if (!p.isString) refuse(BackupProblem.DAMAGED, "$what.$name type")
            p.content
        }

        fun string(name: String): String = nullableString(name) ?: refuse(BackupProblem.DAMAGED, "$what.$name null")

        fun nullableLong(name: String): Long? = primitive(name)?.let { p ->
            if (p.isString) refuse(BackupProblem.DAMAGED, "$what.$name type")
            p.content.toLongOrNull() ?: refuse(BackupProblem.DAMAGED, "$what.$name not an integer")
        }

        fun long(name: String): Long = nullableLong(name) ?: refuse(BackupProblem.DAMAGED, "$what.$name null")

        fun nullableInt(name: String): Int? = nullableLong(name)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else invalid("$what.$name range") }

        fun int(name: String): Int = nullableInt(name) ?: refuse(BackupProblem.DAMAGED, "$what.$name null")

        fun count(name: String): Int = int(name).also { if (it < 0) invalid("$what.$name negative") }

        fun bool(name: String): Boolean {
            val p = primitive(name) ?: refuse(BackupProblem.DAMAGED, "$what.$name null")
            if (p.isString) refuse(BackupProblem.DAMAGED, "$what.$name type")
            return when (p.content) {
                "true" -> true
                "false" -> false
                else -> refuse(BackupProblem.DAMAGED, "$what.$name not a boolean")
            }
        }

        /** A field a later version added: absent is [default], present it is checked like any other. */
        fun optionalBool(name: String, default: Boolean): Boolean = if (name in o) bool(name) else default

        fun time(name: String): Long = long(name).also { checkTime(it, 0, "$what.$name") }

        fun id(name: String): String = checkId(string(name))

        fun key(name: String): String = string(name).also { checkKey(it) }

        fun text(name: String): String = string(name).also { checkText(it, "$what.$name") }

        fun nullableText(name: String): String? = nullableString(name)?.also { checkText(it, "$what.$name") }

        /** An http(s) address the URL rules accept (no other scheme, no user info, within the length limit). Read only, never fetched. */
        fun url(name: String): String = string(name).also { if (UrlNormalizer.normalize(it) !is UrlNormalizer.Result.Valid) invalid("$what.$name URL") }

        fun nullableUrl(name: String): String? = if (nullableString(name) == null) null else url(name)

        fun color(name: String): Int = CustomColors.parseHex(string(name))?.takeIf { string(name).startsWith("#") } ?: invalid("$what.$name colour")

        inline fun <reified E : Enum<E>> nullableEnum(name: String): E? =
            nullableString(name)?.let { v -> enumValues<E>().firstOrNull { it.name == v } ?: invalid("$what.$name unknown value $v") }

        inline fun <reified E : Enum<E>> enum(name: String): E = nullableEnum<E>(name) ?: refuse(BackupProblem.DAMAGED, "$what.$name null")

        /** An enum field a later version added: absent is [default], present it is checked like any other. */
        inline fun <reified E : Enum<E>> optionalEnum(name: String, default: E): E = if (name in o) enum<E>(name) else default

        fun obj(name: String): Obj = Obj(field(name), "$what.$name")

        fun nullableObj(name: String): Obj? = field(name).let { if (it == JsonNull) null else Obj(it, "$what.$name") }

        fun array(name: String): JsonArray = field(name) as? JsonArray ?: refuse(BackupProblem.DAMAGED, "$what.$name is not an array")
    }
}
