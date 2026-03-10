package com.murmur.reader.data.local

import androidx.room.Entity

@Entity(
    tableName = "cached_chunks",
    primaryKeys = ["documentUri", "chunkIndex", "settingsKey"],
)
data class CachedChunkEntity(
    val documentUri: String,
    val chunkIndex: Int,
    val settingsKey: String,           // "voiceName|rate|pitch" literal
    val chunkTextHash: Int,            // chunk text hashCode for staleness detection
    val audioFileName: String,         // relative to cacheDir/tts_cache
    val wordBoundariesJson: String,    // serialized List<WordBoundary>
    val createdTimestamp: Long = System.currentTimeMillis(),
)
