package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DownloadedVideoEntity::class,
        SubscribedChannelEntity::class,
        WatchHistoryEntity::class,
        LikedVideoEntity::class,
        SavedVideoEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun likedVideoDao(): LikedVideoDao
    abstract fun savedVideoDao(): SavedVideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `liked_videos` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `channelName` TEXT NOT NULL,
                        `channelAvatarUrl` TEXT NOT NULL,
                        `thumbnailUrl` TEXT NOT NULL,
                        `viewCountText` TEXT NOT NULL,
                        `publishedDateText` TEXT NOT NULL,
                        `durationText` TEXT NOT NULL,
                        `likedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_videos` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `channelName` TEXT NOT NULL,
                        `channelAvatarUrl` TEXT NOT NULL,
                        `thumbnailUrl` TEXT NOT NULL,
                        `viewCountText` TEXT NOT NULL,
                        `publishedDateText` TEXT NOT NULL,
                        `durationText` TEXT NOT NULL,
                        `savedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "streamtube_riz.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
