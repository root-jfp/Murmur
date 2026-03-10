package com.murmur.reader.data

import android.content.Context
import com.murmur.reader.data.local.CachedChunkDao
import com.murmur.reader.data.local.CachedChunkEntity
import com.murmur.reader.tts.WordBoundary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CachedAudioChunk(val audioFile: File, val wordBoundaries: List<WordBoundary>)

@Singleton
class AudioCacheRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CachedChunkDao,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir by lazy { File(context.cacheDir, "tts_cache").also { it.mkdirs() } }

    fun settingsKey(voice: String, rate: String, pitch: String): String = "$voice|$rate|$pitch"

    suspend fun getCachedIndices(uri: String, key: String): Set<Int> =
        dao.getCachedIndices(uri, key).toSet()

    suspend fun getChunk(uri: String, idx: Int, key: String, chunkTextHash: Int): CachedAudioChunk? {
        val entity = dao.getChunk(uri, idx, key) ?: return null
        if (entity.chunkTextHash != chunkTextHash) return null  // text changed → stale
        val file = File(cacheDir, entity.audioFileName)
        if (!file.exists()) return null  // evicted by Android
        val boundaries = json.decodeFromString<List<WordBoundary>>(entity.wordBoundariesJson)
        return CachedAudioChunk(file, boundaries)
    }

    suspend fun saveChunk(
        uri: String,
        idx: Int,
        key: String,
        chunkTextHash: Int,
        audioBytes: ByteArray,
        boundaries: List<WordBoundary>,
    ) = withContext(Dispatchers.IO) {
        val fileName = "${uri.hashCode().toUInt().toString(16)}_${idx}_${key.hashCode().toUInt().toString(16)}.mp3"
        val tmpFile = File(cacheDir, "$fileName.tmp")
        val finalFile = File(cacheDir, fileName)
        tmpFile.writeBytes(audioBytes)
        dao.upsert(
            CachedChunkEntity(
                documentUri = uri,
                chunkIndex = idx,
                settingsKey = key,
                chunkTextHash = chunkTextHash,
                audioFileName = fileName,
                wordBoundariesJson = json.encodeToString(boundaries),
            )
        )
        tmpFile.renameTo(finalFile)
    }

    suspend fun invalidateStale(uri: String, currentKey: String) = withContext(Dispatchers.IO) {
        val files = dao.getStaleFileNames(uri, currentKey)
        dao.deleteStale(uri, currentKey)
        files.forEach { File(cacheDir, it).delete() }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        dao.deleteAll()
    }
}
