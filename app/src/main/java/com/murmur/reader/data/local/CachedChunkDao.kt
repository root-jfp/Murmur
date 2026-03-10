package com.murmur.reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedChunkDao {

    @Query("SELECT * FROM cached_chunks WHERE documentUri = :uri AND chunkIndex = :idx AND settingsKey = :key LIMIT 1")
    suspend fun getChunk(uri: String, idx: Int, key: String): CachedChunkEntity?

    @Query("SELECT chunkIndex FROM cached_chunks WHERE documentUri = :uri AND settingsKey = :key")
    suspend fun getCachedIndices(uri: String, key: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedChunkEntity)

    @Query("SELECT audioFileName FROM cached_chunks WHERE documentUri = :uri AND settingsKey != :key")
    suspend fun getStaleFileNames(uri: String, key: String): List<String>

    @Query("DELETE FROM cached_chunks WHERE documentUri = :uri AND settingsKey != :key")
    suspend fun deleteStale(uri: String, key: String)

    @Query("DELETE FROM cached_chunks WHERE documentUri = :uri")
    suspend fun deleteForDocument(uri: String)

    @Query("SELECT audioFileName FROM cached_chunks")
    suspend fun getAllFileNames(): List<String>

    @Query("DELETE FROM cached_chunks")
    suspend fun deleteAll()
}
