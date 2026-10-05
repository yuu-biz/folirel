package io.github.yuubiz.folirel.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** docs/data-and-import.md section 1. All timestamps are UTC epoch milliseconds. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val backendType: String,
    val displayName: String,
    val createdAt: Long,
)

@Entity(
    tableName = "feeds",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])],
    indices = [Index(value = ["accountId", "subscriptionUrl"], unique = true)],
)
data class FeedEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    /** Registered URL; OPML re-import identity key. Never changed automatically. */
    val subscriptionUrl: String,
    /** Current fetch target (may follow permanent redirects). */
    val fetchUrl: String,
    val siteUrl: String?,
    val feedTitle: String?,
    val userTitle: String?,
    val isSubscribed: Boolean,
    val isPaused: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "feed_fetch_state",
    foreignKeys = [ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"])],
)
data class FeedFetchStateEntity(
    @PrimaryKey val feedId: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val validatorUrl: String? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastErrorCode: String? = null,
    val failureCount: Int = 0,
    val nextEligibleAt: Long? = null,
    val initialized: Boolean = false,
    /** Valid server Retry-After (429/503): no request of any kind before this time (v2). */
    val retryAfterUntil: Long? = null,
)

/**
 * Per-feed fetch diagnostics (v2). Diagnostic only:
 * refresh scheduling never depends on it except [pendingSince] for "retry unfinished".
 * The "last request" fields change on every request that targeted the feed; the "attempt" fields only when an
 * HTTP request was actually made; the "body" fields only after a 200 was parsed and applied.
 */
@Entity(
    tableName = "feed_diagnostics",
    foreignKeys = [ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"])],
)
data class FeedDiagnosticsEntity(
    @PrimaryKey val feedId: String,
    // Last request that targeted this feed
    val requestId: String? = null,
    val requestReason: String? = null,
    val requestScope: String? = null,
    val requestForce: Boolean = false,
    val requestedAt: Long? = null,
    /** Result for that request (FeedResult name). */
    val result: String? = null,
    val resultAt: Long? = null,
    /** Accepted by a request and not finished yet (survives process death). */
    val pendingSince: Long? = null,
    // Last actual HTTP attempt
    val attemptStartedAt: Long? = null,
    val attemptFinishedAt: Long? = null,
    val httpStatus: Int? = null,
    val validatorsSent: Boolean = false,
    val errorCode: String? = null,
    /** Which limit / parse problem (e.g. BODY_SIZE, ENTRY_COUNT, XML_DEPTH). */
    val errorDetail: String? = null,
    // Last successfully parsed and applied body (kept on 304 and failures)
    val bodyAt: Long? = null,
    val bodyBytes: Long? = null,
    val rawEntries: Int? = null,
    val uniqueEntries: Int? = null,
    val undatedEntries: Int? = null,
    val oldestPublishedAt: Long? = null,
    val newestPublishedAt: Long? = null,
    val insertedArticles: Int? = null,
    val updatedArticles: Int? = null,
    /** Entries skipped because the user's retention cleanup already removed them as read. */
    val skippedPruned: Int? = null,
)

/**
 * Lightweight record of articles removed by the retention cleanup (v2, CFG-02): only read, unsaved articles are
 * removed, and a removed entry that the feed still returns is not inserted again as a new unread article.
 * Rows not seen in any response for [PRUNED_TTL_DAYS] days are forgotten.
 */
@Entity(
    tableName = "pruned_entries",
    primaryKeys = ["feedId", "entryKey"],
    foreignKeys = [ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"])],
    indices = [Index(value = ["lastSeenAt"])],
)
data class PrunedEntryEntity(
    val feedId: String,
    val entryKey: String,
    val prunedAt: Long,
    val lastSeenAt: Long,
) {
    companion object {
        const val PRUNED_TTL_DAYS = 90L
    }
}

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])],
    indices = [Index(value = ["accountId", "pathSegmentsJson"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    /** Last path segment, for display. */
    val name: String,
    /** JSON array of NFC-normalized segments; a segment may itself contain "/". */
    val pathSegmentsJson: String,
)

/**
 * What the reader chose for one category (v4): the place of its tab, the pin, and the three display settings it overrides. The id
 * is the category's own, so a rename keeps all of it; deleting the category deletes the row. No row = every default: no saved
 * place, not pinned, everything inherited. [tabOrder] null = no saved place; [cardColumnsOverride] null = inherit, 0 = automatic.
 */
@Entity(
    tableName = "category_preferences",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CategoryPreferenceEntity(
    @PrimaryKey val categoryId: String,
    val tabOrder: Int? = null,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    val listModeOverride: String? = null,
    val cardStyleOverride: String? = null,
    val cardColumnsOverride: Int? = null,
)

@Entity(
    tableName = "feed_categories",
    primaryKeys = ["feedId", "categoryId"],
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"]),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId", "feedId"])],
)
data class FeedCategoryEntity(
    val feedId: String,
    val categoryId: String,
)

@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"]),
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"]),
    ],
    indices = [
        Index(value = ["accountId", "feedId", "entryKey"], unique = true),
        Index(value = ["accountId", "sortAt", "id"]),
        Index(value = ["feedId", "sortAt"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val accountId: String,
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

/** Manual reading state. Independent from article metadata updates; created only on first insert. */
@Entity(
    tableName = "article_states",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["isSaved", "articleId"])],
)
data class ArticleStateEntity(
    @PrimaryKey val articleId: String,
    val isRead: Boolean,
    val isSaved: Boolean,
    val updatedAt: Long,
)

/**
 * A feed whose fetch URL was moved from http:// to https:// after the user's consent and a successful HTTPS
 * verification (v2). The feed row, its id, categories, articles and reading state are unchanged; the
 * registered [httpUrl] stays the OPML re-import identity, so importing the old OPML again matches this feed and
 * does not create a duplicate or move the fetch URL back to HTTP.
 */
@Entity(
    tableName = "feed_https_migrations",
    foreignKeys = [ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"])],
    indices = [Index(value = ["httpsUrl"])],
)
data class FeedHttpsMigrationEntity(
    @PrimaryKey val feedId: String,
    val httpUrl: String,
    val httpsUrl: String,
    val migratedAt: Long,
)

/** State of the source icon of one feed. */
enum class IconStatus {
    /** Not looked up yet (or the feed's declared icon changed). */
    PENDING,

    /** [FeedIconEntity.png] holds the icon. */
    OK,

    /** Looked up; the site has no usable icon. The initial letter is shown. */
    NONE,

    /** Network trouble or a blocked address; retried later. */
    FAILED,
}

/**
 * Source icon of a feed (v3, UX-ICON): resolved once per feed, normalized to a small PNG, cached here with the
 * time of the next lookup. Written by the icon coordinator and, for [declaredUrl], by [FeedStore]. Never part of the
 * fetch result of a feed: a failed icon lookup is not a feed failure.
 */
@Entity(
    tableName = "feed_icons",
    foreignKeys = [ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"])],
)
data class FeedIconEntity(
    @PrimaryKey val feedId: String,
    /** Icon URL the feed document declares (absolute), if any. A change makes the lookup run again. */
    val declaredUrl: String? = null,
    /** [IconStatus] name. */
    val status: String,
    /** Where the stored icon came from: FEED, HTML, FAVICON or BANNER (a wide logo, used only as a last resort). */
    val sourceKind: String? = null,
    val sourceUrl: String? = null,
    /** Normalized PNG, at most [MAX_SIDE_PX] on the long side. */
    val png: ByteArray? = null,
    val width: Int? = null,
    val height: Int? = null,
    val resolvedAt: Long? = null,
    val nextAttemptAt: Long = 0,
    val failureCount: Int = 0,
) {
    companion object {
        const val MAX_SIDE_PX = 96
    }
}

/**
 * A feed's fetch URL that was changed by the user to a discovered new address (v3, SUB-DISCOVERY). The feed row
 * (id, categories, articles, reading state) is kept; the registered URL stays the re-import identity and every
 * recorded old/new address maps back to this feed, so importing an older OPML does not create a duplicate.
 */
@Entity(
    tableName = "feed_url_moves",
    foreignKeys = [ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"])],
    indices = [Index(value = ["feedId"]), Index(value = ["fromUrl"]), Index(value = ["toUrl"])],
)
data class FeedUrlMoveEntity(
    @PrimaryKey val id: String,
    val feedId: String,
    val fromUrl: String,
    val toUrl: String,
    val movedAt: Long,
)
