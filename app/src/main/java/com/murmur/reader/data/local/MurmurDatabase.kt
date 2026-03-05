package com.murmur.reader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingProgressEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MurmurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
}
