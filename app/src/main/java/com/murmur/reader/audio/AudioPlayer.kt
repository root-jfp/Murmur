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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "Murmur"

/**
 * Wraps ExoPlayer for MP3 playback from Edge TTS audio chunks.
 *
 * Chunk-based workflow:
 *  1. [prepareForChunk] — reset the byte buffer
 *  2. [appendChunk]     — called as binary frames arrive from the WebSocket
 *  3. [playBuffer]      — flush buffer to temp file, start ExoPlayer, suspend until playing
 *  4. [awaitCompletion] — suspend until ExoPlayer reaches STATE_ENDED
 *
 * [currentPositionMs] can be polled from any thread to drive word-boundary highlighting.
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

    // Position polled on main thread, read from any thread for word-boundary sync
    @Volatile
    private var _cachedPositionMs: Long = 0L
    private val positionPoller = object : Runnable {
        override fun run() {
            _cachedPositionMs = exoPlayer?.currentPosition ?: 0L
            if (exoPlayer != null) {
                mainHandler.postDelayed(this, 10) // ~100 Hz, stays ahead of 60 Hz consumers
            }
        }
    }

    /** Resets the byte buffer before synthesising a new text chunk. */
    fun prepareForChunk() {
        chunkBuffer.reset()
    }

    /** Appends raw MP3 bytes received from Edge TTS. Thread-safe. */
    fun appendChunk(chunk: com.murmur.reader.tts.AudioChunk) {
        synchronized(chunkBuffer) {
            chunkBuffer.write(chunk.bytes)
        }
    }

    /**
     * Flushes the buffered audio to a temp file, creates an ExoPlayer,
     * and **suspends until playback actually starts** (or completes if the
     * clip is very short).
     *
     * Returns `false` if there was nothing to play.
     */
    suspend fun playBuffer(): Boolean {
        val data = synchronized(chunkBuffer) { chunkBuffer.toByteArray() }
        if (data.isEmpty()) return false

        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "murmur_tts_${System.currentTimeMillis()}.mp3").also {
                it.writeBytes(data)
            }
        }
        Log.d(TAG, "AudioPlayer: wrote ${data.size} bytes → ${file.name}")

        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                releasePlayer()
                deleteTempFile()
                tempFile = file

                val player = ExoPlayer.Builder(context).build().also { exoPlayer = it }
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing && cont.isActive) cont.resume(Unit)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Clip may end before isPlaying ever flips to true
                        if (playbackState == Player.STATE_ENDED && cont.isActive) {
                            cont.resume(Unit)
                        }
                    }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
                startPositionPoller()
                Log.d(TAG, "AudioPlayer: playback started")
            }
            cont.invokeOnCancellation {
                mainHandler.post { releasePlayer(); deleteTempFile() }
            }
        }
        return true
    }

    /**
     * Plays an existing MP3 file directly via ExoPlayer.
     * Does NOT delete the file when done (it's a cache file).
     * Returns `false` if the file doesn't exist.
     */
    suspend fun playFile(file: File): Boolean {
        if (!file.exists()) return false
        Log.d(TAG, "AudioPlayer: playing cached file ${file.name}")

        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                releasePlayer()
                // Don't delete temp files from previous buffer-based playback here —
                // the cache file passed in is managed by AudioCacheRepository.
                deleteTempFile()

                val player = ExoPlayer.Builder(context).build().also { exoPlayer = it }
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing && cont.isActive) cont.resume(Unit)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && cont.isActive) {
                            cont.resume(Unit)
                        }
                    }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
                startPositionPoller()
            }
            cont.invokeOnCancellation {
                mainHandler.post { releasePlayer() }
            }
        }
        return true
    }

    /** Suspends until ExoPlayer reaches [Player.STATE_ENDED]. */
    suspend fun awaitCompletion() {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val player = exoPlayer
                if (player == null ||
                    player.playbackState == Player.STATE_ENDED ||
                    player.playbackState == Player.STATE_IDLE
                ) {
                    if (cont.isActive) cont.resume(Unit)
                    return@post
                }
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation {
                    mainHandler.post { player.removeListener(listener) }
                }
            }
        }
    }

    /**
     * Current playback position in milliseconds.
     * Safe to call from any thread — backed by a volatile field updated on the main thread.
     */
    fun currentPositionMs(): Long = _cachedPositionMs

    fun seekTo(positionMs: Long) {
        mainHandler.post { exoPlayer?.seekTo(positionMs) }
    }

    fun pause() {
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

    // Must be called on main thread
    private fun startPositionPoller() {
        mainHandler.removeCallbacks(positionPoller)
        positionPoller.run() // immediate first read + schedules next
    }

    // Must be called on main thread
    private fun releasePlayer() {
        mainHandler.removeCallbacks(positionPoller)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        _isPlaying.value = false
        _cachedPositionMs = 0L
    }

    private fun deleteTempFile() {
        tempFile?.delete()
        tempFile = null
    }

    fun release() {
        stop()
    }
}
