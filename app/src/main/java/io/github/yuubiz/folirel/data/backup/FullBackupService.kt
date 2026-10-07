package io.github.yuubiz.folirel.data.backup

import androidx.room.withTransaction
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.data.db.ArticleEntity
import io.github.yuubiz.folirel.data.db.ArticleStateEntity
import io.github.yuubiz.folirel.data.db.CategoryEntity
import io.github.yuubiz.folirel.data.db.CategoryPreferenceEntity
import io.github.yuubiz.folirel.data.db.FeedCategoryEntity
import io.github.yuubiz.folirel.data.db.FeedEntity
import io.github.yuubiz.folirel.data.db.FeedFetchStateEntity
import io.github.yuubiz.folirel.data.db.FeedHttpsMigrationEntity
import io.github.yuubiz.folirel.data.db.FeedUrlMoveEntity
import io.github.yuubiz.folirel.data.db.PrunedEntryEntity
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.CategoryPaths
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock

/** The app's own background work that writes data or uses the network (refresh, icon lookups, the HTTPS check). */
interface BackgroundWork {
    /**
     * Lets no new background work start and waits for running work to stop. False: something is still running; the caller changes
     * nothing and calls [resume].
     */
    suspend fun pause(): Boolean

    fun resume()

    companion object {
        val NONE: BackgroundWork = object : BackgroundWork {
            override suspend fun pause() = true
            override fun resume() = Unit
        }
    }
}

/** What a validated archive holds and what it would replace, shown before the reader decides. */
data class RestorePreview(
    internal val file: File,
    val manifest: BackupManifest,
    /** The settings that will be applied (last selection already resolved against the archive). */
    val settings: AppSettings,
    val counts: BackupCounts,
    /** Pruned entries still within their lifetime; older ones are forgotten by the restore like by the cleanup. */
    val prunedKept: Int,
    /** What is on the device now and will be replaced. */
    val current: BackupCounts,
) {
    val unsubscribedFeeds: Int get() = counts.feeds - counts.subscribedFeeds
    val unreadArticles: Int get() = counts.articles - counts.readArticles
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome

    /** Background work did not stop in time; nothing was changed. */
    data object Busy : RestoreOutcome

    /**
     * The restore could not be applied; the data is unchanged. [settingsKept] false: the old settings could not be written back now; the
     * restore's note stays and the next app start puts them back.
     */
    data class Failed(val settingsKept: Boolean) : RestoreOutcome
}

enum class RestoreRecovery { NONE, COMPLETED, ROLLED_BACK }

data class AppVersion(val name: String, val code: Int)

/**
 * Full local backup and restore (docs/backup-format.md).
 *
 * Backup: the settings are read first, then every table in one Room transaction (a consistent snapshot, whatever refresh or the reader
 * do meanwhile), streamed into an archive file in app-private storage; the caller copies that file to the document the reader picked.
 *
 * Restore replaces everything and has three steps, none of which changes anything before the reader confirmed:
 * 1. [inspect]: the archive (already copied to app-private storage) is read completely and validated; the preview is made from it.
 * 2. The reader confirms; [restore] stops background work (no refresh, no icon lookup, no network while it runs).
 * 3. A note with the old settings is written, then the new settings (one DataStore write), then the database is replaced in one
 *    transaction from a second read of the same file. A failure of the database step puts the old settings back; a failure of the
 *    settings step changed nothing. If the app dies in between, [recoverInterruptedRestore] finishes the job at the next start.
 *
 * Nothing here touches the network: URLs in an archive are only checked as text. No refresh is started after a restore.
 */
class FullBackupService(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val accountId: suspend () -> String,
    private val clock: Clock,
    /** App-private directory for the archive copies and the restore note. */
    private val workDir: File,
    private val app: AppVersion,
    private val background: BackgroundWork = BackgroundWork.NONE,
    private val limits: BackupLimits = BackupLimits.DEFAULT,
    /** Tests only: called at each step of a restore, to inject a failure there. */
    private val faults: suspend (RestoreStep) -> Unit = {},
) {
    enum class RestoreStep { BEFORE_SETTINGS, BEFORE_DATABASE, DATABASE_BEFORE_COMMIT, AFTER_DATABASE }

    private val dao = db.backupDao()
    private val subscriptionDao = db.subscriptionDao()
    private val journal = RestoreJournal(File(workDir, "restore-journal.json"))

    private val _replaced = MutableStateFlow(0L)

    /** Raised after each restore that replaced the data, so that screens drop what they held (selection, search, notices). */
    val replaced: StateFlow<Long> = _replaced.asStateFlow()

    // ---- backup ----

    /** A new empty file in app-private storage for an archive (written here, or copied in from a document). */
    fun newWorkFile(prefix: String): File {
        workDir.mkdirs()
        return File.createTempFile(prefix, ".zip", workDir)
    }

    /** Deletes archive copies left by an earlier run (never the restore note). */
    fun cleanWorkFiles() {
        workDir.listFiles()?.filter { it.name.endsWith(".zip") }?.forEach { it.delete() }
    }

    /** Writes a full backup to [out]. Returns its manifest (counts for the result message). */
    suspend fun createBackup(out: OutputStream): BackupManifest = withContext(Dispatchers.IO) {
        // Order (docs/backup-format.md): settings, then one Room transaction for all data, serialized while it is held.
        val s = settings.current()
        val account = accountId()
        val now = clock.millis()
        db.withTransaction {
            val counts = BackupCounts(
                feeds = dao.countFeeds(account),
                subscribedFeeds = dao.countSubscribed(account),
                categories = dao.countCategories(account),
                memberships = dao.countMemberships(account),
                articles = dao.countArticles(account),
                readArticles = dao.countRead(account),
                savedArticles = dao.countSaved(account),
                prunedEntries = dao.countPruned(account),
                hiddenKeywords = s.hiddenKeywords.size,
            )
            val manifest = BackupManifest(BackupFormat.FORMAT, BackupFormat.VERSION, now, app.name, app.code, AppDatabase.VERSION, counts)
            BackupZipWriter(out, now).use { w ->
                w.obj(BackupFormat.MANIFEST, BackupJson.manifest(manifest))
                w.obj(BackupFormat.SETTINGS, BackupJson.settings(s))
                val fetched = dao.fetchedFeedIds(account).toHashSet()
                val https = subscriptionDao.httpsMigrations(account).associateBy { it.feedId }
                val moves = subscriptionDao.urlMoves(account).groupBy { it.feedId }
                w.array(BackupFormat.SUBSCRIPTIONS) { emit ->
                    for (f in dao.feeds(account)) {
                        emit(BackupJson.feed(f.toBackup(f.id in fetched, https[f.id], moves[f.id].orEmpty())))
                    }
                }
                val members = dao.memberships(account).groupBy({ it.categoryId }, { it.feedId })
                val preferences = subscriptionDao.categoryPreferences(account).associateBy { it.categoryId }
                w.array(BackupFormat.CATEGORIES) { emit ->
                    for (c in dao.categories(account)) emit(BackupJson.category(c.toBackup(members[c.id].orEmpty(), preferences[c.id])))
                }
                w.array(BackupFormat.ARTICLES) { emit ->
                    var after = ""
                    while (true) {
                        val page = dao.articlesAfter(account, after, PAGE)
                        page.forEach { emit(BackupJson.article(it.toBackup())) }
                        if (page.size < PAGE) break
                        after = page.last().id
                    }
                }
                w.array(BackupFormat.ARTICLE_STATES) { emit ->
                    var after = ""
                    while (true) {
                        val page = dao.statesAfter(account, after, PAGE)
                        page.forEach { emit(BackupJson.state(BackupArticleState(it.articleId, it.isRead, it.isSaved, it.updatedAt))) }
                        if (page.size < PAGE) break
                        after = page.last().articleId
                    }
                }
                w.array(BackupFormat.PRUNED_ENTRIES) { emit ->
                    dao.pruned(account).forEach { emit(BackupJson.pruned(BackupPrunedEntry(it.feedId, it.entryKey, it.prunedAt, it.lastSeenAt))) }
                }
            }
            manifest
        }
    }

    // ---- restore ----

    /** Reads and validates the whole archive in [file] (app-private). Changes nothing. Throws [BackupException] for a refused archive. */
    suspend fun inspect(file: File): RestorePreview = withContext(Dispatchers.IO) {
        val validator = BackupValidator(clock.millis())
        readArchive(file, validator, sink = null)
        val result = validator.finish()
        RestorePreview(file, result.manifest, result.settings, result.counts, result.prunedKept, currentCounts())
    }

    /** What the device holds now. */
    suspend fun currentCounts(): BackupCounts {
        val account = accountId()
        return db.withTransaction {
            BackupCounts(
                feeds = dao.countFeeds(account),
                subscribedFeeds = dao.countSubscribed(account),
                categories = dao.countCategories(account),
                memberships = dao.countMemberships(account),
                articles = dao.countArticles(account),
                readArticles = dao.countRead(account),
                savedArticles = dao.countSaved(account),
                prunedEntries = dao.countPruned(account),
                hiddenKeywords = settings.current().hiddenKeywords.size,
            )
        }
    }

    /** The reader cancelled: the archive copy is deleted, nothing else happens. */
    fun discard(preview: RestorePreview) {
        preview.file.delete()
    }

    /** Replaces all data and settings with [preview]'s archive. Runs to its end even if the caller is cancelled. */
    suspend fun restore(preview: RestorePreview): RestoreOutcome = withContext(Dispatchers.IO + NonCancellable) {
        // A note left by a restore that died is resolved first, so that its old settings are not mistaken for the current ones.
        recoverInterruptedRestore()
        if (!background.pause()) {
            background.resume()
            return@withContext RestoreOutcome.Busy
        }
        try {
            replace(preview)
        } finally {
            background.resume()
        }
    }

    private suspend fun replace(preview: RestorePreview): RestoreOutcome {
        val old = settings.rawSnapshot()
        val token = newToken()
        try {
            journal.write(RestoreJournal.Entry(token, old))
            faults(RestoreStep.BEFORE_SETTINGS)
            settings.replaceForRestore(preview.settings)
        } catch (e: Exception) {
            // One DataStore write is all or nothing: the settings are as they were, the database was not touched.
            journal.delete()
            return RestoreOutcome.Failed(settingsKept = true)
        }
        try {
            faults(RestoreStep.BEFORE_DATABASE)
            replaceDatabase(preview.file, token)
        } catch (e: Exception) {
            // The transaction was rolled back: the data is as it was. Put the old settings back.
            val reverted = runCatching { settings.replaceRaw(old) }.isSuccess
            if (reverted) journal.delete()
            return RestoreOutcome.Failed(settingsKept = reverted)
        }
        faults(RestoreStep.AFTER_DATABASE)
        journal.delete()
        preview.file.delete()
        _replaced.value = _replaced.value + 1
        return RestoreOutcome.Restored
    }

    /** Deletes every row of the local data and inserts the archive's, in one transaction that also stores [token]. */
    private suspend fun replaceDatabase(file: File, token: Int) {
        val account = accountId()
        db.withTransaction {
            // Children before parents; only article_states, category_preferences and feed_categories cascade.
            dao.deleteArticleStates()
            dao.deleteArticles()
            dao.deletePruned()
            dao.deleteDiagnostics()
            dao.deleteFetchStates()
            dao.deleteIcons()
            dao.deleteHttpsMigrations()
            dao.deleteUrlMoves()
            dao.deleteMemberships()
            dao.deleteCategoryPreferences()
            dao.deleteCategories()
            dao.deleteFeeds()
            // The second read validates again record by record: what is inserted is exactly what was checked.
            val validator = BackupValidator(clock.millis())
            readArchive(file, validator, RoomSink(account))
            validator.finish()
            faults(RestoreStep.DATABASE_BEFORE_COMMIT)
            db.openHelper.writableDatabase.execSQL("PRAGMA application_id = $token")
        }
    }

    /**
     * Resolves a restore that the app did not live to finish (see [RestoreJournal]). Called once at app start, before anything reads the
     * settings, and before a new restore.
     */
    suspend fun recoverInterruptedRestore(): RestoreRecovery = withContext(Dispatchers.IO + NonCancellable) {
        if (!journal.exists()) return@withContext RestoreRecovery.NONE
        val entry = try {
            journal.read()
        } catch (e: Exception) {
            // Written through a temporary file and a rename, so a note is either complete or absent; this one cannot be used.
            journal.delete()
            return@withContext RestoreRecovery.NONE
        }
        val committed = applicationId() == entry.token
        if (!committed) settings.replaceRaw(entry.oldSettings)
        journal.delete()
        if (committed) {
            _replaced.value = _replaced.value + 1
            RestoreRecovery.COMPLETED
        } else {
            RestoreRecovery.ROLLED_BACK
        }
    }

    private fun applicationId(): Int = db.query("PRAGMA application_id", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun newToken(): Int {
        val current = applicationId()
        val random = SecureRandom()
        while (true) {
            val t = random.nextInt()
            if (t != 0 && t != current) return t
        }
    }

    /** One read of the archive, entry by entry; [sink] null = validation only. */
    private suspend fun readArchive(file: File, validator: BackupValidator, sink: RoomSink?) {
        BackupZipReader(file, limits).read { name, input -> visit(name, input, validator, sink) }
    }

    private suspend fun visit(name: String, input: InputStream, validator: BackupValidator, sink: RoomSink?) {
        when (name) {
            BackupFormat.MANIFEST -> {
                val value = BackupZipReader.readValue(input)
                val version = BackupJson.manifestVersion(value)
                if (version != BackupFormat.VERSION) refuse(BackupProblem.UNSUPPORTED_VERSION, "format version $version")
                validator.manifest(BackupJson.readManifest(value))
            }
            BackupFormat.SETTINGS -> validator.settings(BackupJson.readSettings(BackupZipReader.readValue(input)))
            BackupFormat.SUBSCRIPTIONS -> {
                BackupZipReader.forEachRecord(input) { e ->
                    val f = BackupJson.readFeed(e)
                    validator.feed(f)
                    sink?.feed(f)
                }
                sink?.flushFeeds()
            }
            BackupFormat.CATEGORIES -> {
                val list = ArrayList<BackupCategory>()
                BackupZipReader.forEachRecord(input) { list += BackupJson.readCategory(it) }
                validator.categories(list)
                sink?.categories(list)
            }
            BackupFormat.ARTICLES -> {
                BackupZipReader.forEachRecord(input) { e ->
                    val a = BackupJson.readArticle(e)
                    validator.article(a)
                    sink?.article(a)
                }
                sink?.flushArticles()
            }
            BackupFormat.ARTICLE_STATES -> {
                BackupZipReader.forEachRecord(input) { e ->
                    val s = BackupJson.readState(e)
                    validator.state(s)
                    sink?.state(s)
                }
                sink?.flushStates()
            }
            BackupFormat.PRUNED_ENTRIES -> {
                BackupZipReader.forEachRecord(input) { e ->
                    val p = BackupJson.readPruned(e)
                    if (validator.pruned(p)) sink?.pruned(p)
                }
                sink?.flushPruned()
            }
        }
    }

    /** Inserts records in batches, parents before children (the entry order guarantees the parents were inserted). */
    private inner class RoomSink(private val account: String) {
        private val feeds = ArrayList<BackupFeed>()
        private val articles = ArrayList<ArticleEntity>()
        private val states = ArrayList<ArticleStateEntity>()
        private val pruned = ArrayList<PrunedEntryEntity>()

        suspend fun feed(f: BackupFeed) {
            feeds += f
            if (feeds.size >= BATCH) flushFeeds()
        }

        suspend fun flushFeeds() {
            if (feeds.isEmpty()) return
            dao.insertFeeds(feeds.map { FeedEntity(it.id, account, it.subscriptionUrl, it.fetchUrl, it.siteUrl, it.feedTitle, it.userTitle, it.subscribed, it.paused, it.createdAt) })
            // Fetch bookkeeping starts fresh: no validators, errors, waits or diagnostics. Only "fetched before" is kept.
            dao.insertFetchStates(feeds.map { FeedFetchStateEntity(feedId = it.id, initialized = it.fetchedBefore) })
            dao.insertHttpsMigrations(feeds.mapNotNull { f -> f.httpsMigration?.let { FeedHttpsMigrationEntity(f.id, it.httpUrl, it.httpsUrl, it.migratedAt) } })
            dao.insertUrlMoves(feeds.flatMap { f -> f.urlMoves.map { FeedUrlMoveEntity(it.id, f.id, it.fromUrl, it.toUrl, it.movedAt) } })
            feeds.clear()
        }

        suspend fun categories(list: List<BackupCategory>) {
            for (chunk in list.chunked(BATCH)) {
                dao.insertCategories(chunk.map { CategoryEntity(it.id, account, it.path.last(), CategoryPaths.encode(it.path)) })
            }
            for (chunk in list.filter { it.hasPreference }.chunked(BATCH)) {
                dao.insertCategoryPreferences(
                    chunk.map { CategoryPreferenceEntity(it.id, it.tabOrder, it.pinned, it.listMode?.name, it.cardStyle?.name, it.cardColumns) },
                )
            }
            for (chunk in list.flatMap { c -> c.feedIds.map { FeedCategoryEntity(it, c.id) } }.chunked(BATCH)) dao.insertMemberships(chunk)
        }

        suspend fun article(a: BackupArticle) {
            articles += ArticleEntity(
                a.id, account, a.feedId, a.entryKey, a.externalEntryId, a.linkUrl, a.title, a.summaryText, a.author,
                a.publishedAt, a.firstSeenAt, a.sortAt, a.imageUrl, a.lastSeenAt,
            )
            if (articles.size >= BATCH) flushArticles()
        }

        suspend fun flushArticles() {
            if (articles.isNotEmpty()) dao.insertArticles(articles.toList())
            articles.clear()
        }

        suspend fun state(s: BackupArticleState) {
            states += ArticleStateEntity(s.articleId, s.read, s.saved, s.updatedAt)
            if (states.size >= BATCH) flushStates()
        }

        suspend fun flushStates() {
            if (states.isNotEmpty()) dao.insertArticleStates(states.toList())
            states.clear()
        }

        suspend fun pruned(p: BackupPrunedEntry) {
            pruned += PrunedEntryEntity(p.feedId, p.entryKey, p.prunedAt, p.lastSeenAt)
            if (pruned.size >= BATCH) flushPruned()
        }

        suspend fun flushPruned() {
            if (pruned.isNotEmpty()) dao.insertPruned(pruned.toList())
            pruned.clear()
        }
    }

    private fun FeedEntity.toBackup(fetched: Boolean, https: FeedHttpsMigrationEntity?, moves: List<FeedUrlMoveEntity>) = BackupFeed(
        id = id, subscriptionUrl = subscriptionUrl, fetchUrl = fetchUrl, siteUrl = siteUrl, feedTitle = feedTitle, userTitle = userTitle,
        subscribed = isSubscribed, paused = isPaused, createdAt = createdAt, fetchedBefore = fetched,
        httpsMigration = https?.let { BackupHttpsMigration(it.httpUrl, it.httpsUrl, it.migratedAt) },
        urlMoves = moves.map { BackupUrlMove(it.id, it.fromUrl, it.toUrl, it.movedAt) },
    )

    private fun CategoryEntity.toBackup(feedIds: List<String>, p: CategoryPreferenceEntity?) = BackupCategory(
        id = id,
        path = CategoryPaths.decode(pathSegmentsJson),
        feedIds = feedIds,
        tabOrder = p?.tabOrder,
        pinned = p?.isPinned == true,
        // Stored names this version does not know read as "inherit", as they do everywhere else.
        listMode = p?.listModeOverride?.let { v -> ListMode.entries.firstOrNull { it.name == v } },
        cardStyle = p?.cardStyleOverride?.let { v -> CardLayoutStyle.entries.firstOrNull { it.name == v } },
        cardColumns = p?.cardColumnsOverride?.takeIf { it in AppSettings.CARD_COLUMNS },
    )

    private fun ArticleEntity.toBackup() = BackupArticle(
        id, feedId, entryKey, externalEntryId, linkUrl, title, summaryText, author, publishedAt, firstSeenAt, sortAt, imageUrl, lastSeenAt,
    )

    private companion object {
        const val PAGE = 1_000
        const val BATCH = 500
    }
}
