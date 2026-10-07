package io.github.yuubiz.folirel.data.backup

import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode

/**
 * The Folirel full backup, format version 1 (docs/backup-format.md). A ZIP of UTF-8 JSON files holding the logical state of the app,
 * independent from the Room schema: these types are what the archive means, not database rows. Room ids are kept as stable backup ids.
 */
object BackupFormat {
    const val FORMAT = "folirel-backup"
    const val VERSION = 1

    /** The only backend a version 1 archive can describe. */
    const val ACCOUNT_LOCAL = "local"

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val SUBSCRIPTIONS = "subscriptions.json"
    const val CATEGORIES = "categories.json"
    const val ARTICLES = "articles.json"
    const val ARTICLE_STATES = "article_states.json"
    const val PRUNED_ENTRIES = "pruned_entries.json"

    /** Every entry of a version 1 archive, in the order it is written and must be read: the manifest first, then what refers to what came before. */
    val ENTRIES = listOf(MANIFEST, SETTINGS, SUBSCRIPTIONS, CATEGORIES, ARTICLES, ARTICLE_STATES, PRUNED_ENTRIES)

    const val MIME_TYPE = "application/zip"

    /** `folirel-backup-YYYYMMDD-HHmmss.zip` in the device's time zone (the name only; the manifest has the exact UTC time). */
    fun fileName(time: java.time.ZonedDateTime): String =
        "folirel-backup-" + time.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip"
}

data class BackupCounts(
    val feeds: Int,
    val subscribedFeeds: Int,
    val categories: Int,
    val memberships: Int,
    val articles: Int,
    val readArticles: Int,
    val savedArticles: Int,
    val prunedEntries: Int,
    val hiddenKeywords: Int,
)

data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val createdAt: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    /** Room schema of the app that wrote it; informational only (the archive is not tied to it). */
    val schemaVersion: Int,
    val counts: BackupCounts,
)

data class BackupHttpsMigration(val httpUrl: String, val httpsUrl: String, val migratedAt: Long)

data class BackupUrlMove(val id: String, val fromUrl: String, val toUrl: String, val movedAt: Long)

/**
 * One feed with its identity history. [fetchedBefore] says the feed had been fetched at least once: the first refresh after a restore
 * then counts its new articles as arrivals and the feed is not listed as "not fetched yet" (the rest of the fetch bookkeeping is reset).
 */
data class BackupFeed(
    val id: String,
    val subscriptionUrl: String,
    val fetchUrl: String,
    val siteUrl: String?,
    val feedTitle: String?,
    val userTitle: String?,
    val subscribed: Boolean,
    val paused: Boolean,
    val createdAt: Long,
    val fetchedBefore: Boolean,
    val httpsMigration: BackupHttpsMigration?,
    val urlMoves: List<BackupUrlMove>,
)

/** A category with its direct members and the reader's choices for it ([cardColumns] null = inherit, 0 = automatic). */
data class BackupCategory(
    val id: String,
    val path: List<String>,
    val feedIds: List<String>,
    val tabOrder: Int?,
    val pinned: Boolean,
    val listMode: ListMode?,
    val cardStyle: CardLayoutStyle?,
    val cardColumns: Int?,
) {
    /** Whether a category_preferences row says anything; a row with only defaults and no row mean the same. */
    val hasPreference: Boolean get() = tabOrder != null || pinned || listMode != null || cardStyle != null || cardColumns != null
}

data class BackupArticle(
    val id: String,
    val feedId: String,
    val entryKey: String,
    val externalEntryId: String?,
    val linkUrl: String?,
    val title: String,
    val summaryText: String,
    val author: String?,
    val publishedAt: Long?,
    val firstSeenAt: Long,
    val sortAt: Long,
    val imageUrl: String?,
    val lastSeenAt: Long,
)

data class BackupArticleState(val articleId: String, val read: Boolean, val saved: Boolean, val updatedAt: Long)

data class BackupPrunedEntry(val feedId: String, val entryKey: String, val prunedAt: Long, val lastSeenAt: Long)

/** Why an archive is refused. Nothing has been changed when one of these is reported. */
enum class BackupProblem {
    /** Not a ZIP, or a ZIP without a Folirel manifest (e.g. an OPML file). */
    NOT_A_BACKUP,

    /** A Folirel backup of a format version this app does not know (written by a newer app). */
    UNSUPPORTED_VERSION,

    /** Larger than the limits (file, entry, total, compression ratio). */
    TOO_LARGE,

    /** Unsafe or unexpected ZIP structure: paths, duplicate or unknown entries, wrong order, directories. */
    UNSAFE_ARCHIVE,

    /** Broken ZIP or JSON, a missing entry or field, a wrong type. */
    DAMAGED,

    /** Well-formed but not consistent: duplicate ids, broken references, invalid URLs, values out of range, wrong counts. */
    INVALID_DATA,
}

class BackupException(val problem: BackupProblem, detail: String) : Exception(detail)

internal fun refuse(problem: BackupProblem, detail: String): Nothing = throw BackupException(problem, detail)

internal fun invalid(detail: String): Nothing = refuse(BackupProblem.INVALID_DATA, detail)
