package com.murmur.reader.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE documentUri = :uri ORDER BY characterPosition")
    fun observeForDocument(uri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdTimestamp DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>
}
