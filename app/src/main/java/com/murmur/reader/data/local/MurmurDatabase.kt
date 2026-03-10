package com.murmur.reader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReadingProgressEntity::class, BookmarkEntity::class, CachedChunkEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MurmurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cachedChunkDao(): CachedChunkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_chunks (
                        documentUri TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        settingsKey TEXT NOT NULL,
                        chunkTextHash INTEGER NOT NULL,
                        audioFileName TEXT NOT NULL,
                        wordBoundariesJson TEXT NOT NULL,
                        createdTimestamp INTEGER NOT NULL,
                        PRIMARY KEY (documentUri, chunkIndex, settingsKey)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
