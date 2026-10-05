package io.github.yuubiz.folirel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        AccountEntity::class,
        FeedEntity::class,
        FeedFetchStateEntity::class,
        CategoryEntity::class,
        FeedCategoryEntity::class,
        ArticleEntity::class,
        ArticleStateEntity::class,
        FeedDiagnosticsEntity::class,
        PrunedEntryEntity::class,
        FeedHttpsMigrationEntity::class,
        FeedIconEntity::class,
        FeedUrlMoveEntity::class,
        CategoryPreferenceEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun articleDao(): ArticleDao
    abstract fun diagnosticsDao(): DiagnosticsDao
    abstract fun iconDao(): FeedIconDao

    companion object {
        const val VERSION = 4
        const val NAME = "folirel.db"

        /** Explicit migrations only. Destructive fallback is forbidden (docs/data-and-import.md section 6). */
        val MIGRATIONS: Array<Migration> = arrayOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)

        /** Database files on disk (main, WAL, shared memory) for the size display. */
        fun fileBytes(context: Context): Long =
            listOf(NAME, "$NAME-wal", "$NAME-shm").sumOf { context.getDatabasePath(it).length() }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
