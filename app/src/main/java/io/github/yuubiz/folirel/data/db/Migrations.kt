package io.github.yuubiz.folirel.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migrations only. Existing rows (subscriptions, articles, reading state) are never rewritten. */
object Migrations {
    /** v2: Retry-After hard wait, per-feed fetch diagnostics, pruned-entry record, HTTP→HTTPS migration record. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `feed_fetch_state` ADD COLUMN `retryAfterUntil` INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `feed_diagnostics` (`feedId` TEXT NOT NULL, `requestId` TEXT, `requestReason` TEXT, " +
                    "`requestScope` TEXT, `requestForce` INTEGER NOT NULL, `requestedAt` INTEGER, `result` TEXT, `resultAt` INTEGER, " +
                    "`pendingSince` INTEGER, `attemptStartedAt` INTEGER, `attemptFinishedAt` INTEGER, `httpStatus` INTEGER, " +
                    "`validatorsSent` INTEGER NOT NULL, `errorCode` TEXT, `errorDetail` TEXT, `bodyAt` INTEGER, `bodyBytes` INTEGER, " +
                    "`rawEntries` INTEGER, `uniqueEntries` INTEGER, `undatedEntries` INTEGER, `oldestPublishedAt` INTEGER, " +
                    "`newestPublishedAt` INTEGER, `insertedArticles` INTEGER, `updatedArticles` INTEGER, `skippedPruned` INTEGER, " +
                    "PRIMARY KEY(`feedId`), FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pruned_entries` (`feedId` TEXT NOT NULL, `entryKey` TEXT NOT NULL, `prunedAt` INTEGER NOT NULL, " +
                    "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`feedId`, `entryKey`), " +
                    "FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pruned_entries_lastSeenAt` ON `pruned_entries` (`lastSeenAt`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `feed_https_migrations` (`feedId` TEXT NOT NULL, `httpUrl` TEXT NOT NULL, " +
                    "`httpsUrl` TEXT NOT NULL, `migratedAt` INTEGER NOT NULL, PRIMARY KEY(`feedId`), " +
                    "FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_https_migrations_httpsUrl` ON `feed_https_migrations` (`httpsUrl`)")
        }
    }

    /** v3: per-feed source icon cache, and the record of user-approved feed URL moves. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `feed_icons` (`feedId` TEXT NOT NULL, `declaredUrl` TEXT, `status` TEXT NOT NULL, " +
                    "`sourceKind` TEXT, `sourceUrl` TEXT, `png` BLOB, `width` INTEGER, `height` INTEGER, `resolvedAt` INTEGER, " +
                    "`nextAttemptAt` INTEGER NOT NULL, `failureCount` INTEGER NOT NULL, PRIMARY KEY(`feedId`), " +
                    "FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `feed_url_moves` (`id` TEXT NOT NULL, `feedId` TEXT NOT NULL, `fromUrl` TEXT NOT NULL, " +
                    "`toUrl` TEXT NOT NULL, `movedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_url_moves_feedId` ON `feed_url_moves` (`feedId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_url_moves_fromUrl` ON `feed_url_moves` (`fromUrl`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_url_moves_toUrl` ON `feed_url_moves` (`toUrl`)")
        }
    }

    /**
     * v4: what the reader chose per category (tab order, pin, display overrides). One new table; no existing row is touched, and a
     * category without a row has every default, so the first start after the update changes nothing the reader can see.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `category_preferences` (`categoryId` TEXT NOT NULL, `tabOrder` INTEGER, " +
                    "`isPinned` INTEGER NOT NULL DEFAULT 0, `listModeOverride` TEXT, `cardStyleOverride` TEXT, " +
                    "`cardColumnsOverride` INTEGER, PRIMARY KEY(`categoryId`), " +
                    "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }
}
