package com.murmur.reader.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Murmur"

/**
 * Wraps ExoPlayer for MP3 playback from Edge TTS audio chunks.
 *
 * ExoPlayer requires all calls to happen on the main thread.
 * Non-main-thread callers (e.g. Dispatchers.IO coroutines) must route
 * through [mainHandler] — all methods here do this internally.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var exoPlayer: ExoPlayer? = null
    private val chunkBuffer = ByteArrayOutputStream()
    private var tempFile: File? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** Called before streaming begins for a new synthesis session. */
    fun prepareForStreaming() {
        chunkBuffer.reset()
        mainHandler.post { releasePlayer() }
    }

    /** Appends an audio chunk received from Edge TTS. Thread-safe. */
    fun appendChunk(chunk: com.murmur.reader.tts.AudioChunk) {
        synchronized(chunkBuffer) {
            chunkBuffer.write(chunk.bytes)
        }
        Log.v(TAG, "AudioPlayer: buffered ${chunk.bytes.size} bytes (total=${chunkBuffer.size()})")
    }

    /**
     * Called when Edge TTS signals turn.end — flush buffer to file and start playback.
     * Safe to call from any thread.
     */
    fun finishStreaming() {
        val data = synchronized(chunkBuffer) { chunkBuffer.toByteArray() }
        if (data.isEmpty()) return

        // Write file off main thread, then switch to main for ExoPlayer
        val file = File(context.cacheDir, "murmur_tts_${System.currentTimeMillis()}.mp3")
        file.writeBytes(data)
        Log.d(TAG, "AudioPlayer: writing ${data.size} bytes to ${file.name}")

        mainHandler.post {
            releasePlayer()
            tempFile = file

            val player = ExoPlayer.Builder(context).build().also { exoPlayer = it }
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            Log.d(TAG, "AudioPlayer: playback started")
        }
    }

    fun pause() {
        // Don't set _isPlaying eagerly — onIsPlayingChanged listener will update it
        // once ExoPlayer has actually paused on the main thread.
        mainHandler.post { exoPlayer?.pause() }
    }

    fun resume() {
        mainHandler.post { exoPlayer?.play() }
    }

    fun stop() {
        chunkBuffer.reset()
        mainHandler.post {
            releasePlayer()
            deleteTempFile()
        }
    }

    fun currentPositionMs(): Long = exoPlayer?.currentPosition ?: 0L

    // Must be called on main thread
    private fun releasePlayer() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        _isPlaying.value = false
    }

    private fun deleteTempFile() {
        tempFile?.delete()
        tempFile = null
    }

    fun release() {
        stop()
    }
}
