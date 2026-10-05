package io.github.yuubiz.folirel

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T38: migration test infrastructure. Creates a database from the exported v1 schema with raw SQL, then opens
 * it through the production builder (explicit migrations only, no destructive fallback) and reads it back.
 * v2 (N25): the explicit 1→2 migration is validated against the exported v2 schema with the data kept.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val db = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1DataIsReadableByCurrentBuild() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 1).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('acc', 'LOCAL', 'Local', 0)")
            execSQL(
                "INSERT INTO feeds (id, accountId, subscriptionUrl, fetchUrl, siteUrl, feedTitle, userTitle, isSubscribed, isPaused, createdAt) " +
                    "VALUES ('f1', 'acc', 'https://example.com/a.xml', 'https://example.com/a.xml', NULL, 'A', NULL, 1, 0, 0)",
            )
            execSQL("INSERT INTO categories (id, accountId, name, pathSegmentsJson) VALUES ('c1', 'acc', 'Tech', '[\"Tech\"]')")
            execSQL("INSERT INTO feed_categories (feedId, categoryId) VALUES ('f1', 'c1')")
            execSQL(
                "INSERT INTO articles (id, accountId, feedId, entryKey, externalEntryId, linkUrl, title, summaryText, author, publishedAt, firstSeenAt, sortAt, imageUrl, lastSeenAt) " +
                    "VALUES ('a1', 'acc', 'f1', 'id:1', NULL, 'https://example.com/1', 't', 's', NULL, 1, 1, 1, NULL, 1)",
            )
            execSQL("INSERT INTO article_states (articleId, isRead, isSaved, updatedAt) VALUES ('a1', 1, 1, 1)")
            close()
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, db).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            val state = room.articleDao().state("a1")!!
            assertTrue(state.isRead && state.isSaved)
            assertEquals("Tech", room.subscriptionDao().categories("acc").single().name)
            assertEquals(1, room.subscriptionDao().memberships("acc").size)
        } finally {
            room.close()
            context.deleteDatabase(db)
        }
    }

    @Test
    fun migration1To2IsValidAndKeepsData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 1).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('acc', 'LOCAL', 'Local', 0)")
            execSQL(
                "INSERT INTO feeds (id, accountId, subscriptionUrl, fetchUrl, siteUrl, feedTitle, userTitle, isSubscribed, isPaused, createdAt) " +
                    "VALUES ('f1', 'acc', 'http://example.com/a.xml', 'http://example.com/a.xml', NULL, 'A', NULL, 1, 0, 0)",
            )
            execSQL(
                "INSERT INTO articles (id, accountId, feedId, entryKey, externalEntryId, linkUrl, title, summaryText, author, publishedAt, firstSeenAt, sortAt, imageUrl, lastSeenAt) " +
                    "VALUES ('a1', 'acc', 'f1', 'id:1', NULL, 'https://example.com/1', 't', 's', NULL, 1, 1, 1, NULL, 1)",
            )
            execSQL("INSERT INTO article_states (articleId, isRead, isSaved, updatedAt) VALUES ('a1', 1, 1, 1)")
            close()
        }
        helper.runMigrationsAndValidate(db, 2, true, io.github.yuubiz.folirel.data.db.Migrations.MIGRATION_1_2).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, db).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            val state = runBlocking { room.articleDao().state("a1")!! }
            assertTrue(state.isRead && state.isSaved)
        } finally {
            room.close()
            context.deleteDatabase(db)
        }
    }

    /** R01 / DB v3: 2→3 only adds tables; subscriptions, articles and reading state are kept, and 1→3 chains. */
    @Test
    fun migration2To3IsValidAndKeepsData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 2).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('acc', 'LOCAL', 'Local', 0)")
            execSQL(
                "INSERT INTO feeds (id, accountId, subscriptionUrl, fetchUrl, siteUrl, feedTitle, userTitle, isSubscribed, isPaused, createdAt) " +
                    "VALUES ('f1', 'acc', 'https://example.com/a.xml', 'https://example.com/a.xml', NULL, 'A', NULL, 1, 0, 0)",
            )
            execSQL(
                "INSERT INTO articles (id, accountId, feedId, entryKey, externalEntryId, linkUrl, title, summaryText, author, publishedAt, firstSeenAt, sortAt, imageUrl, lastSeenAt) " +
                    "VALUES ('a1', 'acc', 'f1', 'id:1', NULL, 'https://example.com/1', 't', 's', NULL, 1, 1, 1, NULL, 1)",
            )
            execSQL("INSERT INTO article_states (articleId, isRead, isSaved, updatedAt) VALUES ('a1', 1, 1, 1)")
            close()
        }
        helper.runMigrationsAndValidate(db, 3, true, io.github.yuubiz.folirel.data.db.Migrations.MIGRATION_2_3).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, db).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                val state = room.articleDao().state("a1")!!
                assertTrue(state.isRead && state.isSaved)
                assertEquals("A", room.subscriptionDao().feed("f1")!!.feedTitle)
                assertEquals(0, room.iconDao().count("OK"))
                assertEquals(emptyList<Any>(), room.subscriptionDao().urlMoves("acc"))
            }
        } finally {
            room.close()
            context.deleteDatabase(db)
        }
    }

    @Test
    fun migration1To3ChainsBothSteps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 1).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('acc', 'LOCAL', 'Local', 0)")
            close()
        }
        helper.runMigrationsAndValidate(
            db, 3, true,
            io.github.yuubiz.folirel.data.db.Migrations.MIGRATION_1_2,
            io.github.yuubiz.folirel.data.db.Migrations.MIGRATION_2_3,
        ).close()
        context.deleteDatabase(db)
    }

    /**
     * P1-T30 / DB v4: 3→4 only adds the category preference table. A database as 0.4.2 leaves it (subscriptions in two categories,
     * articles with reading and saved state, icons) opens validated against the exported schema 4 with every row kept, and every
     * category has the defaults: no saved place, not pinned, nothing overridden.
     */
    @Test
    fun migration3To4IsValidAndKeepsTheDataOfARealVersion3Database() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 3).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('acc', 'LOCAL', 'Local', 0)")
            for (i in 1..3) {
                execSQL(
                    "INSERT INTO feeds (id, accountId, subscriptionUrl, fetchUrl, siteUrl, feedTitle, userTitle, isSubscribed, isPaused, createdAt) " +
                        "VALUES ('f$i', 'acc', 'https://example.com/$i.xml', 'https://example.com/$i.xml', NULL, 'Feed $i', NULL, 1, 0, $i)",
                )
                execSQL("INSERT INTO feed_fetch_state (feedId, failureCount, initialized) VALUES ('f$i', 0, 1)")
            }
            execSQL("INSERT INTO categories (id, accountId, name, pathSegmentsJson) VALUES ('c1', 'acc', 'Tech', '[\"Tech\"]')")
            execSQL("INSERT INTO categories (id, accountId, name, pathSegmentsJson) VALUES ('c2', 'acc', 'Web', '[\"Tech\",\"Web\"]')")
            execSQL("INSERT INTO feed_categories (feedId, categoryId) VALUES ('f1', 'c1')")
            execSQL("INSERT INTO feed_categories (feedId, categoryId) VALUES ('f1', 'c2')")
            execSQL("INSERT INTO feed_categories (feedId, categoryId) VALUES ('f2', 'c2')")
            for (i in 1..6) {
                execSQL(
                    "INSERT INTO articles (id, accountId, feedId, entryKey, externalEntryId, linkUrl, title, summaryText, author, publishedAt, firstSeenAt, sortAt, imageUrl, lastSeenAt) " +
                        "VALUES ('a$i', 'acc', 'f${(i - 1) % 3 + 1}', 'id:$i', NULL, 'https://example.com/$i', 't$i', 's', NULL, $i, $i, $i, NULL, $i)",
                )
                execSQL("INSERT INTO article_states (articleId, isRead, isSaved, updatedAt) VALUES ('a$i', ${i % 2}, ${if (i % 3 == 0) 1 else 0}, $i)")
            }
            execSQL("INSERT INTO feed_icons (feedId, status, nextAttemptAt, failureCount) VALUES ('f1', 'NONE', 0, 0)")
            close()
        }
        helper.runMigrationsAndValidate(db, 4, true, io.github.yuubiz.folirel.data.db.Migrations.MIGRATION_3_4).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, db).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                val d = room.subscriptionDao()
                assertEquals(listOf("c1", "c2"), d.categories("acc").map { it.id }.sorted())
                assertEquals(3, d.memberships("acc").size)
                assertEquals(3, d.feeds("acc").size)
                val states = (1..6).map { room.articleDao().state("a$it")!! }
                assertEquals(listOf(1, 0, 1, 0, 1, 0), states.map { if (it.isRead) 1 else 0 })
                assertEquals(listOf(false, false, true, false, false, true), states.map { it.isSaved })
                assertEquals("no category has a row: every default", emptyList<Any>(), d.categoryPreferences("acc"))
                // The new table works against the migrated database, and deleting a category removes its row.
                d.insertPreferenceIfAbsent(io.github.yuubiz.folirel.data.db.CategoryPreferenceEntity("c1"))
                d.setPinned("c1", true)
                assertTrue(d.categoryPreferences("acc").single().isPinned)
                d.deleteCategories(listOf("c1", "c2"))
                assertEquals(emptyList<Any>(), d.categoryPreferences("acc"))
            }
        } finally {
            room.close()
            context.deleteDatabase(db)
        }
    }

    @Test
    fun migration1To4ChainsAllSteps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 1).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('acc', 'LOCAL', 'Local', 0)")
            close()
        }
        helper.runMigrationsAndValidate(db, 4, true, *io.github.yuubiz.folirel.data.db.AppDatabase.MIGRATIONS).close()
        context.deleteDatabase(db)
    }

    @Test
    fun unknownNewerVersionFailsInsteadOfWiping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(db)
        helper.createDatabase(db, 1).apply {
            execSQL("INSERT INTO accounts (id, backendType, displayName, createdAt) VALUES ('keep', 'LOCAL', 'Local', 0)")
            execSQL("PRAGMA user_version = 99")
            close()
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, db).addMigrations(*AppDatabase.MIGRATIONS).build()
        val error = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
        room.close()
        assertTrue("expected a failure, not a destructive reset: $error", error != null)
        // The data is still on disk.
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(db).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        raw.rawQuery("SELECT COUNT(*) FROM accounts", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        raw.close()
        context.deleteDatabase(db)
    }
}
