package io.github.yuubiz.feeddeck.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** docs/03 section 1. All timestamps are UTC epoch milliseconds. */
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
)

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
