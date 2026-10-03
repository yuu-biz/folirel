package io.github.yuubiz.feeddeck.data.db

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
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun articleDao(): ArticleDao

    companion object {
        const val VERSION = 1
        const val NAME = "feeddeck.db"

        /** Explicit migrations only. Destructive fallback is forbidden (docs/03 section 6). */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
