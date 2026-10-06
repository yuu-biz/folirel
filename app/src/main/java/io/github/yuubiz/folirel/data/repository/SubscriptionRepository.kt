package io.github.yuubiz.folirel.data.repository

import androidx.room.withTransaction
import io.github.yuubiz.folirel.backend.local.opml.OpmlDocument
import io.github.yuubiz.folirel.data.db.AccountEntity
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.data.db.CategoryEntity
import io.github.yuubiz.folirel.data.db.CategoryPreferenceEntity
import io.github.yuubiz.folirel.data.db.FeedCategoryEntity
import io.github.yuubiz.folirel.data.db.FeedEntity
import io.github.yuubiz.folirel.data.db.FeedFetchStateEntity
import io.github.yuubiz.folirel.data.db.FeedDiagnosticsEntity
import io.github.yuubiz.folirel.data.db.FeedHttpsMigrationEntity
import io.github.yuubiz.folirel.data.db.FeedUrlMoveEntity
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.FeedDiagnostics
import io.github.yuubiz.folirel.model.FeedFetchInfo
import io.github.yuubiz.folirel.model.FeedUrlMove
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.model.TabOrder
import io.github.yuubiz.folirel.network.UrlNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock

/** Diff of an OPML document against the current DB, shown before anything is written (docs/product-and-ux.md S30). */
data class ImportPreview(
    val document: OpmlDocument,
    val totalUniqueFeeds: Int,
    val newFeeds: Int,
    val existingFeeds: Int,
    val resubscribeFeeds: Int,
    /** Names of (up to 10 of) the unsubscribed feeds this import would subscribe again, so it is never a surprise. */
    val resubscribeSamples: List<String> = emptyList(),
    val addedMemberships: Int,
    val multiCategoryFeeds: Int,
    /** Valid http:// feeds; registered, but not fetched while HTTP is disabled. */
    val httpFeeds: Int,
    val httpPending: Int,
    val invalidUrls: Int,
    val unsupportedItems: Int,
    val newCategories: Int,
    val emptyCategories: Int,
    val warnings: Int,
)

data class ImportResult(
    val createdFeeds: Int,
    val resubscribedFeeds: Int,
    val addedMemberships: Int,
    val createdCategories: Int,
    /** Feeds to fetch after the import (new and resubscribed), by local id. */
    val feedIdsToFetch: List<String>,
)

sealed interface AddFeedResult {
    data class Added(val feedId: String) : AddFeedResult
    data class Resubscribed(val feedId: String) : AddFeedResult
    data class AlreadySubscribed(val feedId: String) : AddFeedResult
    data class Invalid(val reason: UrlNormalizer.Reason) : AddFeedResult
}

class SubscriptionRepository(
    private val db: AppDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    private val dao = db.subscriptionDao()

    /** Feeds, categories, memberships and fetch states as one derived tree for drawer/tabs/management. */
    fun observeStructure(accountId: String): Flow<Structure> = combine(
        dao.observeFeeds(accountId),
        dao.observeCategories(accountId),
        dao.observeMemberships(accountId),
        dao.observeFetchStates(accountId),
        dao.observeCategoryPreferences(accountId),
    ) { feeds, categories, memberships, states, preferences -> Structure.build(feeds, categories, memberships, states, preferences) }

    suspend fun fetchState(feedId: String): FeedFetchStateEntity? = dao.fetchState(feedId)

    /** Per-feed fetch diagnostics of the account, by feed id. */
    fun observeDiagnostics(accountId: String): Flow<Map<String, FeedDiagnostics>> =
        db.diagnosticsDao().observeAllDiagnostics(accountId).map { rows -> rows.associate { it.feedId to it.toModel() } }

    fun observeFeedDiagnostics(feedId: String): Flow<FeedDiagnostics?> =
        db.diagnosticsDao().observeDiagnostics(feedId).map { it?.toModel() }

    fun observeFetchInfo(feedId: String): Flow<FeedFetchInfo?> = combine(
        dao.observeFeed(feedId),
        dao.observeFetchState(feedId),
        db.articleDao().observeFeedCounts(feedId),
        dao.observeHttpsMigration(feedId),
        dao.observeUrlMoves(feedId),
    ) { feed, state, counts, https, moves ->
        feed?.let {
            FeedFetchInfo(
                fetchUrl = it.fetchUrl, siteUrl = it.siteUrl, feedTitle = it.feedTitle, userTitle = it.userTitle,
                lastAttemptAt = state?.lastAttemptAt, lastSuccessAt = state?.lastSuccessAt, nextEligibleAt = state?.nextEligibleAt,
                retryAfterUntil = state?.retryAfterUntil, failureCount = state?.failureCount ?: 0,
                storedArticles = counts.total, unreadArticles = counts.unread, savedArticles = counts.saved,
                httpsFromUrl = https?.takeIf { m -> m.httpsUrl == it.fetchUrl }?.httpUrl,
                urlMoves = moves.map { m -> FeedUrlMove(m.fromUrl, m.toUrl, m.movedAt) },
            )
        }
    }

    private fun FeedDiagnosticsEntity.toModel() = FeedDiagnostics(
        feedId = feedId, requestReason = requestReason, requestScope = requestScope, requestForce = requestForce,
        requestedAt = requestedAt, result = FeedResult.fromStored(result), resultAt = resultAt, pendingSince = pendingSince,
        attemptStartedAt = attemptStartedAt, attemptFinishedAt = attemptFinishedAt, httpStatus = httpStatus,
        validatorsSent = validatorsSent, error = FetchError.fromStored(errorCode), errorDetail = errorDetail,
        bodyAt = bodyAt, bodyBytes = bodyBytes, rawEntries = rawEntries, uniqueEntries = uniqueEntries,
        undatedEntries = undatedEntries, oldestPublishedAt = oldestPublishedAt, newestPublishedAt = newestPublishedAt,
        insertedArticles = insertedArticles, updatedArticles = updatedArticles, skippedPruned = skippedPruned,
    )

    suspend fun feed(feedId: String): FeedEntity? = dao.feed(feedId)

    /** V1 has exactly one LOCAL account, created on first use. */
    suspend fun ensureLocalAccount(): String = db.withTransaction {
        dao.firstAccountOfType(LOCAL)?.id ?: ids.newId().also { id ->
            dao.insertAccount(AccountEntity(id, LOCAL, "Local", clock.millis()))
        }
    }

    suspend fun previewImport(accountId: String, document: OpmlDocument, allowHttp: Boolean): ImportPreview {
        // The same identity rule as commitImport (FeedLookup): registered URL, current fetch URL, or the https URL
        // a feed was migrated to. Counts therefore equal what the import will do.
        val lookup = feedLookup(accountId)
        val categoryIdByPath = dao.categories(accountId).associate { CategoryPaths.decode(it.pathSegmentsJson) to it.id }
        val existingMemberships = dao.memberships(accountId).map { it.feedId to it.categoryId }.toSet()

        val valid = document.occurrences.filter { it.url != null }
        val uniqueUrls = valid.map { it.url!! }.distinct()
        val matches = uniqueUrls.associateWith { lookup.find(it) }
        val newUrls = uniqueUrls.filter { matches.getValue(it) == null }
        // Several OPML URLs can resolve to one feed (http and https spelling): count that feed once.
        val existing = matches.values.filterNotNull().distinctBy { it.id }
        val wantedMemberships = valid.filter { it.categoryPath.isNotEmpty() }.map { it.url!! to it.categoryPath }.toSet()
        val added = wantedMemberships.count { (url, path) ->
            val feedId = matches[url]?.id
            val categoryId = categoryIdByPath[path]
            feedId == null || categoryId == null || (feedId to categoryId) !in existingMemberships
        }
        val httpUrls = uniqueUrls.filter { it.startsWith("http://") }
        // Held back only when the feed would really be fetched over http://: an existing feed that already moved to
        // https:// is not pending whatever scheme the OPML spells.
        val httpPending = if (allowHttp) 0 else uniqueUrls.count { url ->
            (matches.getValue(url)?.fetchUrl ?: url).startsWith("http://", ignoreCase = true)
        }
        val pathsWithFeeds = wantedMemberships.map { it.second }
        return ImportPreview(
            document = document,
            totalUniqueFeeds = uniqueUrls.size,
            newFeeds = newUrls.size,
            existingFeeds = existing.count { it.isSubscribed },
            resubscribeFeeds = existing.count { !it.isSubscribed },
            resubscribeSamples = existing.filter { !it.isSubscribed }.take(10).map { Structure.displayName(it) },
            addedMemberships = added,
            multiCategoryFeeds = wantedMemberships.groupBy { it.first }.count { it.value.size > 1 },
            httpFeeds = httpUrls.size,
            httpPending = httpPending,
            invalidUrls = document.occurrences.count { it.url == null },
            unsupportedItems = document.unsupported.size,
            newCategories = document.categoryPaths.count { it !in categoryIdByPath },
            emptyCategories = document.categoryPaths.count { p -> pathsWithFeeds.none { CategoryPaths.isSameOrDescendant(it, p) } },
            warnings = document.warnings.size,
        )
    }

    /**
     * Finds the existing feed of an OPML/add-feed URL: the registered URL first, then a feed already fetched from
     * that URL (moved to HTTPS or permanently redirected there). One implementation for preview and commit.
     */
    private class FeedLookup(feeds: List<FeedEntity>, migrations: List<FeedHttpsMigrationEntity>, moves: List<FeedUrlMoveEntity>) {
        private val byUrl = feeds.associateByTo(HashMap()) { it.subscriptionUrl }
        private val byFetchUrl = HashMap<String, FeedEntity>()

        init {
            val byId = feeds.associateBy { it.id }
            val order = compareBy<FeedEntity>({ it.createdAt }, { it.id })
            val candidates = feeds.map { it.fetchUrl to it } +
                migrations.mapNotNull { m -> byId[m.feedId]?.let { m.httpsUrl to it } } +
                // Every address a feed was moved from or to still identifies that feed (older OPML files).
                moves.flatMap { m -> byId[m.feedId]?.let { listOf(m.fromUrl to it, m.toUrl to it) }.orEmpty() }
            // Earliest feed wins, like the SQL lookup (ORDER BY createdAt, id LIMIT 1).
            candidates.sortedWith { a, b -> order.compare(a.second, b.second) }.forEach { (url, feed) -> byFetchUrl.putIfAbsent(url, feed) }
        }

        fun find(url: String): FeedEntity? = byUrl[url] ?: byFetchUrl[url]

        fun add(feed: FeedEntity) {
            byUrl.putIfAbsent(feed.subscriptionUrl, feed)
            byFetchUrl.putIfAbsent(feed.fetchUrl, feed)
        }
    }

    private suspend fun feedLookup(accountId: String) =
        FeedLookup(dao.feeds(accountId), dao.httpsMigrations(accountId), dao.urlMoves(accountId))

    /**
     * Applies the import in one transaction: add/merge only. Existing user titles, memberships and reading
     * state are never changed or removed; unsubscribed feeds with the same URL are re-enabled (same id).
     */
    suspend fun commitImport(accountId: String, document: OpmlDocument): ImportResult = db.withTransaction {
        val now = clock.millis()
        val categoryIdByPath = dao.categories(accountId)
            .associate { CategoryPaths.decode(it.pathSegmentsJson) to it.id }
            .toMutableMap()
        var createdCategories = 0
        val allPaths = LinkedHashSet<List<String>>()
        document.categoryPaths.forEach { path -> for (i in 1..path.size) allPaths += path.subList(0, i) }
        document.occurrences.forEach { o -> for (i in 1..o.categoryPath.size) allPaths += o.categoryPath.subList(0, i) }
        for (path in allPaths) {
            if (path !in categoryIdByPath) {
                val id = ids.newId()
                dao.insertCategory(CategoryEntity(id, accountId, path.last(), CategoryPaths.encode(path)))
                categoryIdByPath[path] = id
                createdCategories++
            }
        }

        var created = 0
        var resubscribed = 0
        var memberships = 0
        val toFetch = LinkedHashSet<String>()
        val feedIdByUrl = HashMap<String, String>()
        val lookup = feedLookup(accountId)
        for (occurrence in document.occurrences) {
            val url = occurrence.url ?: continue
            val feedId = feedIdByUrl.getOrPut(url) {
                // The registered URL, or a feed already fetched from this URL (moved to HTTPS / redirected).
                // Current subscribed state is read again: an earlier URL of this file may have re-enabled the feed.
                val existing = lookup.find(url)?.let { dao.feed(it.id) }
                when {
                    existing == null -> {
                        val id = ids.newId()
                        val entity = FeedEntity(
                            id = id, accountId = accountId, subscriptionUrl = url, fetchUrl = url,
                            siteUrl = occurrence.siteUrl, feedTitle = null,
                            // First valid label in the file becomes the initial name.
                            userTitle = occurrence.label, isSubscribed = true, isPaused = false, createdAt = now,
                        )
                        dao.insertFeed(entity)
                        lookup.add(entity)
                        dao.insertFetchState(FeedFetchStateEntity(feedId = id))
                        created++
                        toFetch += id
                        id
                    }
                    !existing.isSubscribed -> {
                        dao.setSubscribed(existing.id, true)
                        resubscribed++
                        toFetch += existing.id
                        existing.id
                    }
                    else -> existing.id
                }
            }
            if (occurrence.categoryPath.isNotEmpty()) {
                val categoryId = categoryIdByPath.getValue(occurrence.categoryPath)
                if (dao.insertMembership(FeedCategoryEntity(feedId, categoryId)) != -1L) memberships++
            }
        }
        ImportResult(created, resubscribed, memberships, createdCategories, toFetch.toList())
    }

    /** Adds one feed URL. A known URL is merged (re-subscribed when needed); HTTP URLs are allowed to be registered. */
    suspend fun addFeed(accountId: String, rawUrl: String, categoryIds: List<String> = emptyList()): AddFeedResult {
        val url = when (val result = UrlNormalizer.normalize(rawUrl)) {
            is UrlNormalizer.Result.Valid -> result.url
            is UrlNormalizer.Result.Invalid -> return AddFeedResult.Invalid(result.reason)
        }
        return db.withTransaction {
            val existing = feedLookup(accountId).find(url)
            val result = when {
                existing == null -> {
                    val id = ids.newId()
                    dao.insertFeed(FeedEntity(id, accountId, url, url, null, null, null, true, false, clock.millis()))
                    dao.insertFetchState(FeedFetchStateEntity(feedId = id))
                    AddFeedResult.Added(id)
                }
                !existing.isSubscribed -> {
                    dao.setSubscribed(existing.id, true)
                    AddFeedResult.Resubscribed(existing.id)
                }
                else -> AddFeedResult.AlreadySubscribed(existing.id)
            }
            val feedId = when (result) {
                is AddFeedResult.Added -> result.feedId
                is AddFeedResult.Resubscribed -> result.feedId
                is AddFeedResult.AlreadySubscribed -> result.feedId
                is AddFeedResult.Invalid -> null
            }
            if (feedId != null) categoryIds.forEach { dao.insertMembership(FeedCategoryEntity(feedId, it)) }
            result
        }
    }

    /** Soft unsubscribe: stops updates and hides the feed from normal lists; articles and saved state remain. */
    suspend fun unsubscribe(feedId: String) {
        dao.setSubscribed(feedId, false)
    }

    suspend fun resubscribe(feedId: String) {
        dao.setSubscribed(feedId, true)
    }

    /**
     * Soft-unsubscribes exactly [ids] in one transaction (SUB-BULK). Articles, reading and saved state and category
     * memberships stay; saved articles remain readable under "Saved". Feeds that are already unsubscribed are not
     * touched. Returns the ids that were really changed, so an undo restores exactly those.
     */
    suspend fun unsubscribeAll(ids: Collection<String>): List<String> = setSubscribedAll(ids, subscribed = false)

    /** The counterpart of [unsubscribeAll] (undo / bulk subscribe again). Returns the ids that were really changed. */
    suspend fun resubscribeAll(ids: Collection<String>): List<String> = setSubscribedAll(ids, subscribed = true)

    private suspend fun setSubscribedAll(ids: Collection<String>, subscribed: Boolean): List<String> = db.withTransaction {
        val changed = ArrayList<String>()
        ids.distinct().chunked(SQL_CHUNK).forEach { chunk ->
            changed += dao.idsWithSubscribed(chunk, !subscribed)
            dao.setSubscribedBulk(chunk, subscribed)
        }
        changed
    }

    suspend fun setPaused(feedId: String, paused: Boolean) {
        dao.setPaused(feedId, paused)
    }

    suspend fun renameFeed(feedId: String, userTitle: String?) {
        dao.setUserTitle(feedId, userTitle?.trim()?.ifEmpty { null })
    }

    /** Replaces the feed's direct memberships with [categoryIds] (same account only). */
    suspend fun setFeedCategories(feedId: String, categoryIds: Set<String>) = db.withTransaction {
        val feed = dao.feed(feedId) ?: return@withTransaction
        val valid = dao.categories(feed.accountId).map { it.id }.toSet()
        require(valid.containsAll(categoryIds)) { "Category of another account" }
        val current = dao.memberships(feed.accountId).filter { it.feedId == feedId }.map { it.categoryId }.toSet()
        (current - categoryIds).forEach { dao.deleteMembership(feedId, it) }
        (categoryIds - current).forEach { dao.insertMembership(FeedCategoryEntity(feedId, it)) }
    }

    sealed interface CategoryResult {
        data class Ok(val categoryId: String) : CategoryResult
        data object Conflict : CategoryResult
        data object InvalidName : CategoryResult
    }

    /** Creates a category under [parentId] (or at top level). An existing identical path is a conflict. */
    suspend fun createCategory(accountId: String, parentId: String?, name: String): CategoryResult = db.withTransaction {
        val segment = io.github.yuubiz.folirel.backend.local.parser.TextNormalizer.nfcTrim(name)
        if (segment.isEmpty()) return@withTransaction CategoryResult.InvalidName
        val all = dao.categories(accountId)
        val parentPath = parentId?.let { id -> all.firstOrNull { it.id == id }?.let { CategoryPaths.decode(it.pathSegmentsJson) } }.orEmpty()
        val path = parentPath + segment
        val json = CategoryPaths.encode(path)
        if (all.any { it.pathSegmentsJson == json }) return@withTransaction CategoryResult.Conflict
        val id = ids.newId()
        dao.insertCategory(CategoryEntity(id, accountId, segment, json))
        CategoryResult.Ok(id)
    }

    /**
     * Renames the last segment of [categoryId] and rewrites all descendant paths in one transaction, keeping
     * ids and memberships. If any resulting path collides with another category, nothing changes.
     */
    suspend fun renameCategory(categoryId: String, newName: String): CategoryResult = db.withTransaction {
        val segment = io.github.yuubiz.folirel.backend.local.parser.TextNormalizer.nfcTrim(newName)
        if (segment.isEmpty()) return@withTransaction CategoryResult.InvalidName
        val target = db.subscriptionDao().categories(accountOf(categoryId) ?: return@withTransaction CategoryResult.InvalidName)
        val byId = target.associateBy { it.id }
        val oldPath = CategoryPaths.decode(byId.getValue(categoryId).pathSegmentsJson)
        val newPath = oldPath.dropLast(1) + segment
        val affected = target.filter { CategoryPaths.isSameOrDescendant(CategoryPaths.decode(it.pathSegmentsJson), oldPath) }
        val renamed = affected.associate { c ->
            val path = CategoryPaths.decode(c.pathSegmentsJson)
            c.id to (newPath + path.drop(oldPath.size))
        }
        val untouchedPaths = target.filter { it.id !in renamed }.map { it.pathSegmentsJson }.toSet()
        if (renamed.values.any { CategoryPaths.encode(it) in untouchedPaths }) return@withTransaction CategoryResult.Conflict
        // Two-phase update avoids transient UNIQUE collisions between renamed rows.
        for (c in affected) dao.updateCategory(c.copy(pathSegmentsJson = "[\"\\u0000tmp\",\"${c.id}\"]"))
        for (c in affected) {
            val path = renamed.getValue(c.id)
            dao.updateCategory(c.copy(name = path.last(), pathSegmentsJson = CategoryPaths.encode(path)))
        }
        CategoryResult.Ok(categoryId)
    }

    /** Deletes the category and its descendants and their memberships. Feeds and articles are never deleted. */
    suspend fun deleteCategory(categoryId: String): Int = db.withTransaction {
        val account = accountOf(categoryId) ?: return@withTransaction 0
        val all = dao.categories(account)
        val root = CategoryPaths.decode(all.first { it.id == categoryId }.pathSegmentsJson)
        val ids = all.filter { CategoryPaths.isSameOrDescendant(CategoryPaths.decode(it.pathSegmentsJson), root) }.map { it.id }
        dao.deleteCategories(ids) // memberships cascade
    }

    // What the reader chose per category (v4). Every write checks that the category belongs to [accountId], changes only the field it
    // is about (a missing row is created with the defaults first), and says whether it changed anything.

    private suspend fun ownedCategories(accountId: String): Map<String, CategoryEntity> = dao.categories(accountId).associateBy { it.id }

    private suspend fun ensurePreference(categoryId: String) {
        dao.insertPreferenceIfAbsent(CategoryPreferenceEntity(categoryId))
    }

    /** Moves the category to the front group (pinned) or back to the others. Its place in the normal order is kept either way. */
    suspend fun setCategoryPinned(accountId: String, categoryId: String, pinned: Boolean): Boolean = db.withTransaction {
        if (categoryId !in ownedCategories(accountId)) return@withTransaction false
        ensurePreference(categoryId)
        dao.setPinned(categoryId, pinned)
        true
    }

    /**
     * Saves the new order of one group of tabs: [orderedIds] are the pinned categories (or the others) in the order the reader
     * left them in. Only that group's members change places among the places they held; the other group does not move. Ids that
     * are gone or belong to another account are ignored; members that appeared meanwhile keep their old order after the given
     * ones. Written in one transaction; false when nothing valid was given.
     */
    suspend fun reorderCategoryTabs(accountId: String, pinned: Boolean, orderedIds: List<String>): Boolean = db.withTransaction {
        val owned = ownedCategories(accountId)
        if (orderedIds.none { it in owned }) return@withTransaction false
        val prefs = dao.categoryPreferences(accountId).associate { it.categoryId to it }
        val structure = Structure.build(emptyList(), owned.values.toList(), emptyList(), emptyList(), prefs.values.toList())
        val sequence = TabOrder.reorderGroup(structure.categories, structure.preferences, pinned, orderedIds)
        sequence.forEachIndexed { index, id ->
            ensurePreference(id)
            dao.setTabOrder(id, index)
        }
        true
    }

    /**
     * Moves one category by [delta] places inside its own group (pinned or not), computed from the order that is stored when the
     * transaction runs, not from the order a screen showed: several presses made before the first result came back are all applied,
     * one after the other, and none of them is computed from an order another one has already replaced. False when the category is
     * not in [accountId]; true when nothing had to move (already at the end).
     */
    suspend fun moveCategoryTab(accountId: String, categoryId: String, delta: Int): Boolean =
        changeCategoryTabs(accountId, categoryId) { group -> TabOrder.move(group, categoryId, delta) }

    /**
     * Puts the category just before [beforeCategoryId] in its group (at the end when that is null, or gone, or in the other group),
     * also relative to the stored order: what a drag of a row ends in, expressed as "before this neighbour". Other moves that were
     * stored while the row was being dragged stay.
     */
    suspend fun placeCategoryTab(accountId: String, categoryId: String, beforeCategoryId: String?): Boolean =
        changeCategoryTabs(accountId, categoryId) { group -> TabOrder.moveBefore(group, categoryId, beforeCategoryId) }

    private suspend fun changeCategoryTabs(accountId: String, categoryId: String, change: (List<String>) -> List<String>?): Boolean = db.withTransaction {
        val owned = ownedCategories(accountId)
        if (categoryId !in owned) return@withTransaction false
        val prefs = dao.categoryPreferences(accountId)
        val structure = Structure.build(emptyList(), owned.values.toList(), emptyList(), emptyList(), prefs)
        val pinned = structure.preferences[categoryId]?.isPinned == true
        val group = TabOrder.normal(structure.categories, structure.preferences)
            .filter { (structure.preferences[it.id]?.isPinned == true) == pinned }.map { it.id }
        val moved = change(group) ?: return@withTransaction true
        val sequence = TabOrder.reorderGroup(structure.categories, structure.preferences, pinned, moved)
        sequence.forEachIndexed { index, id ->
            ensurePreference(id)
            dao.setTabOrder(id, index)
        }
        true
    }

    /** "Standard order": only the saved places are forgotten (the path order applies again). Pins and display settings stay. */
    suspend fun resetCategoryTabOrder(accountId: String) {
        dao.clearTabOrders(accountId)
    }

    suspend fun setCategoryListLayout(accountId: String, categoryId: String, mode: ListMode?): Boolean = db.withTransaction {
        if (categoryId !in ownedCategories(accountId)) return@withTransaction false
        ensurePreference(categoryId)
        dao.setListModeOverride(categoryId, mode?.name)
        true
    }

    suspend fun setCategoryCardStyle(accountId: String, categoryId: String, style: CardLayoutStyle?): Boolean = db.withTransaction {
        if (categoryId !in ownedCategories(accountId)) return@withTransaction false
        ensurePreference(categoryId)
        dao.setCardStyleOverride(categoryId, style?.name)
        true
    }

    /** [columns]: null inherits, 0 is automatic, 1..5 the most columns wanted. */
    suspend fun setCategoryCardColumns(accountId: String, categoryId: String, columns: Int?): Boolean = db.withTransaction {
        require(columns == null || columns in AppSettings.CARD_COLUMNS) { "Columns out of range" }
        if (categoryId !in ownedCategories(accountId)) return@withTransaction false
        ensurePreference(categoryId)
        dao.setCardColumnsOverride(categoryId, columns)
        true
    }

    /** The three display settings of the category go back to inheriting. Pin, place, subscriptions and reading state stay. */
    suspend fun resetCategoryLayout(accountId: String, categoryId: String): Boolean = db.withTransaction {
        if (categoryId !in ownedCategories(accountId)) return@withTransaction false
        dao.clearLayoutOverrides(categoryId)
        true
    }

    /** Subscribed feeds with their direct category paths, for OPML export. */
    suspend fun exportData(accountId: String): Pair<List<FeedEntity>, Map<String, List<List<String>>>> {
        // Feeds moved to HTTPS are exported with their current https:// fetch URL (it may have followed a later
        // permanent redirect); importing that file again finds the same feed by its fetch URL.
        val migrated = (dao.httpsMigrations(accountId).map { it.feedId } + dao.urlMoves(accountId).map { it.feedId }).toSet()
        val feeds = dao.feeds(accountId).filter { it.isSubscribed }
            .map { f -> if (f.id in migrated) f.copy(subscriptionUrl = f.fetchUrl) else f }
        val paths = dao.categories(accountId).associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
        val byFeed = dao.memberships(accountId).groupBy({ it.feedId }, { paths.getValue(it.categoryId) })
        return feeds to byFeed
    }

    // HTTP → HTTPS migration (after the user's consent and a successful HTTPS verification)

    /** Subscribed feeds still fetched over http://. */
    suspend fun httpFeeds(accountId: String): List<FeedEntity> =
        dao.feeds(accountId).filter { it.isSubscribed && it.fetchUrl.startsWith("http://", ignoreCase = true) }

    /** What discovery compares candidates with: the feed's current address, site, title, entries and article links. */
    class DiscoverySource(
        val fetchUrl: String,
        val siteUrl: String?,
        val feedTitle: String?,
        val displayName: String,
        val entryKeys: Set<String>,
        val articleLinks: List<String>,
    )

    suspend fun discoverySource(feedId: String): DiscoverySource? {
        val feed = dao.feed(feedId) ?: return null
        return DiscoverySource(
            fetchUrl = feed.fetchUrl,
            siteUrl = feed.siteUrl,
            feedTitle = feed.feedTitle,
            displayName = Structure.displayName(feed),
            entryKeys = db.articleDao().recentEntryKeys(feedId, 200).toSet(),
            articleLinks = db.iconDao().recentLinks(feedId, 20),
        )
    }

    /** Title and recent entry keys of a feed, to recognize the same feed over HTTPS. */
    suspend fun knownContent(feedId: String): Pair<String?, List<String>> =
        dao.feed(feedId)?.feedTitle to db.articleDao().recentEntryKeys(feedId, 200)

    /**
     * Moves the fetch URL of [feedId] from [httpUrl] to [httpsUrl]. The feed row (id, categories, articles,
     * reading state) is kept; the registered URL stays as the re-import identity and the pair is recorded.
     * Returns false when the feed changed meanwhile (unsubscribed, or no longer fetched from [httpUrl]).
     */
    suspend fun applyHttpsUpgrade(feedId: String, httpUrl: String, httpsUrl: String): Boolean = db.withTransaction {
        val feed = dao.feed(feedId)
        if (feed == null || !feed.isSubscribed || feed.fetchUrl != httpUrl) return@withTransaction false
        dao.setFetchUrl(feedId, httpsUrl)
        dao.insertHttpsMigration(FeedHttpsMigrationEntity(feedId, httpUrl, httpsUrl, clock.millis()))
        true
    }

    sealed interface MoveResult {
        data object Applied : MoveResult

        /** The feed was unsubscribed, or its fetch URL is no longer the one the candidate was found for. */
        data object Stale : MoveResult

        data object Invalid : MoveResult

        /** The new address already belongs to another subscription; nothing is merged automatically. */
        data class Conflict(val feedId: String, val name: String) : MoveResult
    }

    /**
     * Applies a user-approved move of the fetch URL of [feedId] from [fromUrl] to [toUrl] (SUB-DISCOVERY). The feed
     * row (id, categories, articles, reading and saved state) stays; the registered URL stays the OPML identity and
     * both addresses are recorded, so importing an older OPML still finds this feed. The errors and waits of the old
     * address are cleared so the new one is tried at the next refresh. A new address that already belongs to
     * another feed is refused.
     */
    suspend fun applyUrlMove(feedId: String, fromUrl: String, toUrl: String): MoveResult = db.withTransaction {
        val feed = dao.feed(feedId)
        if (feed == null || !feed.isSubscribed || feed.fetchUrl != fromUrl) return@withTransaction MoveResult.Stale
        val target = UrlNormalizer.normalizedOrNull(toUrl)
        if (target == null || target == fromUrl) return@withTransaction MoveResult.Invalid
        val owner = feedLookup(feed.accountId).find(target)
        if (owner != null && owner.id != feedId) return@withTransaction MoveResult.Conflict(owner.id, Structure.displayName(owner))
        dao.setFetchUrl(feedId, target)
        dao.insertUrlMove(FeedUrlMoveEntity(ids.newId(), feedId, fromUrl, target, clock.millis()))
        dao.clearFetchFailure(feedId)
        MoveResult.Applied
    }

    private suspend fun accountOf(categoryId: String): String? =
        db.subscriptionDao().categoryAccount(categoryId)

    companion object {
        const val LOCAL = "LOCAL"
        private const val SQL_CHUNK = 500
    }
}
