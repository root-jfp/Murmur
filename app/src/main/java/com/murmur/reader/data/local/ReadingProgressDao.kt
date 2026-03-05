package com.murmur.reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE documentUri = :uri LIMIT 1")
    suspend fun getProgress(uri: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY lastReadTimestamp DESC")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("DELETE FROM reading_progress WHERE documentUri = :uri")
    suspend fun delete(uri: String)
}
