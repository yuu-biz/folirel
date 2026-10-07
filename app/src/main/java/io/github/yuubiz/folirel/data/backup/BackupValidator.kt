package io.github.yuubiz.folirel.data.backup

import io.github.yuubiz.folirel.data.db.PrunedEntryEntity
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.model.ArticleScope

/**
 * The checks between records of one archive, fed in entry order (feeds before the categories, articles and pruned entries that name
 * them; articles before their states). Unique keys are those of the database (subscription URL, category path, feed + entry key), so an
 * archive that passes can be inserted as it is. The manifest's counts must match what was read, which also catches a cut-off archive.
 *
 * Only ids and keys are kept, never article text, so a large archive is checked in little memory.
 */
internal class BackupValidator(private val now: Long) {
    private var manifest: BackupManifest? = null
    private var settings: AppSettings? = null

    private val feedIds = HashSet<String>()
    private var subscribedFeeds = 0
    private val subscriptionUrls = HashSet<String>()
    private val moveIds = HashSet<String>()
    private val categoryIds = HashSet<String>()
    private val categoryPaths = HashSet<List<String>>()
    private var memberships = 0
    private val articleIds = HashSet<String>()
    private val articleKeys = HashSet<String>()
    private val stateIds = HashSet<String>()
    private var readArticles = 0
    private var savedArticles = 0
    private val prunedKeys = HashSet<String>()
    private var prunedKept = 0

    fun manifest(m: BackupManifest) {
        manifest = m
    }

    fun settings(s: AppSettings) {
        settings = s
    }

    fun feed(f: BackupFeed) {
        if (!feedIds.add(f.id)) invalid("duplicate feed id")
        if (!subscriptionUrls.add(f.subscriptionUrl)) invalid("duplicate subscription URL")
        if (f.subscribed) subscribedFeeds++
        f.urlMoves.forEach { if (!moveIds.add(it.id)) invalid("duplicate URL move id") }
    }

    /** All categories of the entry, so that a parent may come after its child. */
    fun categories(list: List<BackupCategory>) {
        for (c in list) {
            if (!categoryIds.add(c.id)) invalid("duplicate category id")
            if (!categoryPaths.add(c.path)) invalid("duplicate category path")
            if (c.feedIds.toSet().size != c.feedIds.size) invalid("duplicate membership")
            c.feedIds.forEach { if (it !in feedIds) invalid("membership of an unknown feed") }
            memberships += c.feedIds.size
        }
        // The app never has a category without its parent (import and "new category" create every level, deleting takes the subtree).
        for (path in categoryPaths) {
            if (path.size > 1 && path.dropLast(1) !in categoryPaths) invalid("category without its parent")
        }
    }

    fun article(a: BackupArticle) {
        if (a.feedId !in feedIds) invalid("article of an unknown feed")
        if (!articleIds.add(a.id)) invalid("duplicate article id")
        if (!articleKeys.add(a.feedId + '\u0000' + a.entryKey)) invalid("duplicate article key")
    }

    fun state(s: BackupArticleState) {
        if (s.articleId !in articleIds) invalid("state of an unknown article")
        if (!stateIds.add(s.articleId)) invalid("duplicate article state")
        if (s.read) readArticles++
        if (s.saved) savedArticles++
    }

    /** True when the entry is still kept: the cleanup's own rule forgets entries not seen for [PrunedEntryEntity.PRUNED_TTL_DAYS] days. */
    fun pruned(p: BackupPrunedEntry): Boolean {
        if (p.feedId !in feedIds) invalid("pruned entry of an unknown feed")
        if (!prunedKeys.add(p.feedId + '\u0000' + p.entryKey)) invalid("duplicate pruned entry")
        val keep = p.lastSeenAt >= now - PrunedEntryEntity.PRUNED_TTL_DAYS * DAY_MS
        if (keep) prunedKept++
        return keep
    }

    /** After the last entry: completeness, the manifest's counts, and the last selection resolved against what the archive holds. */
    fun finish(): Result {
        val m = manifest ?: refuse(BackupProblem.DAMAGED, "manifest")
        val s = settings ?: refuse(BackupProblem.DAMAGED, "settings")
        if (stateIds.size != articleIds.size) invalid("article without state")
        val actual = BackupCounts(
            feeds = feedIds.size,
            subscribedFeeds = subscribedFeeds,
            categories = categoryIds.size,
            memberships = memberships,
            articles = articleIds.size,
            readArticles = readArticles,
            savedArticles = savedArticles,
            prunedEntries = prunedKeys.size,
            hiddenKeywords = s.hiddenKeywords.size,
        )
        if (actual != m.counts) invalid("counts differ from the manifest")
        // The one value that falls back: a last selection naming a category or feed the archive does not have opens All.
        val scope = when (val last = s.lastScope) {
            is ArticleScope.Category -> if (last.categoryId in categoryIds) last else ArticleScope.All
            is ArticleScope.Feed -> if (last.feedId in feedIds) last else ArticleScope.All
            else -> last
        }
        return Result(m, s.copy(lastScope = scope), actual, prunedKept)
    }

    data class Result(val manifest: BackupManifest, val settings: AppSettings, val counts: BackupCounts, val prunedKept: Int)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
