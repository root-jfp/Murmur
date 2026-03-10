package com.murmur.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.murmur.reader.audio.AudioPlayer
import com.murmur.reader.data.AudioCacheRepository
import com.murmur.reader.data.preferences.UserPreferencesRepository
import com.murmur.reader.document.TextChunker
import com.murmur.reader.util.NetworkUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Murmur"

/**
 * High-level TTS orchestrator with background audio caching.
 *
 * When [speak] is called:
 * 1. A background coroutine pre-synthesises all chunks via Edge TTS and caches
 *    the MP3 + word boundaries to disk (skipping already-cached chunks).
 * 2. A playback coroutine awaits each chunk's readiness signal, then plays
 *    from the cache file — no re-synthesis needed on subsequent reads.
 *
 * Cache identity: (documentUri, chunkIndex, settingsKey) where
 * settingsKey = "voiceName|rate|pitch".
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val edgeTtsClient: EdgeTtsClient,
    private val audioPlayer: AudioPlayer,
    private val textChunker: TextChunker,
    private val networkUtil: NetworkUtil,
    private val prefsRepository: UserPreferencesRepository,
    private val audioCacheRepository: AudioCacheRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state

    private val _wordBoundaries = MutableSharedFlow<WordBoundary>(extraBufferCapacity = 64)
    val wordBoundaries: SharedFlow<WordBoundary> = _wordBoundaries

    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode

    // Android built-in TTS for offline fallback
    private var androidTts: TextToSpeech? = null

    // Current synthesis settings — kept in sync with DataStore preferences
    var voiceName: String = "en-US-AriaNeural"
        private set
    var rate: String = "+0%"
        private set
    var pitch: String = "+0Hz"
        private set

    init {
        initAndroidTts()

        prefsRepository.preferences
            .onEach { prefs ->
                voiceName = prefs.voiceName
                rate = prefsRepository.rateToSsml(prefs.ratePercent)
                pitch = prefsRepository.pitchToSsml(prefs.pitchHz)
            }
            .launchIn(scope)
    }

    private fun initAndroidTts() {
        androidTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.language = Locale.getDefault()
                Log.d(TAG, "TtsManager: Android TTS initialized")
            }
        }
    }

    /**
     * Starts reading the provided text.
     * If [documentUri] is non-null, audio chunks are cached to disk keyed by
     * (documentUri, chunkIndex, settingsKey).
     */
    fun speak(
        text: String,
        documentUri: String? = null,
        startChunkIndex: Int = 0,
        startCharOffset: Int = 0,
    ) {
        stopInternal()
        currentJob = scope.launch {
            // Snapshot mutable settings so they can't change mid-synthesis
            val curVoice = voiceName
            val curRate = rate
            val curPitch = pitch

            if (documentUri != null) {
                speakWithCaching(text, documentUri, curVoice, curRate, curPitch, startChunkIndex, startCharOffset)
            } else if (networkUtil.isNetworkAvailable()) {
                speakWithEdgeTtsNoCaching(text, curVoice, curRate, curPitch, startChunkIndex, startCharOffset)
            } else {
                _isOfflineMode.value = true
                if (startChunkIndex > 0 || startCharOffset > 0) {
                    val chunks = textChunker.chunk(text)
                    val remaining = chunks.drop(startChunkIndex).joinToString("\n\n")
                    speakWithAndroidTts(remaining)
                } else {
                    speakWithAndroidTts(text)
                }
            }
        }
    }

    /**
     * Cache-aware synthesis and playback.
     *
     * Uses [coroutineScope] for structured concurrency — the pre-synthesis child
     * is automatically cancelled when the parent coroutine is cancelled.
     */
    private suspend fun speakWithCaching(
        fullText: String,
        documentUri: String,
        curVoice: String,
        curRate: String,
        curPitch: String,
        startChunkIndex: Int = 0,
        startCharOffset: Int = 0,
    ) {
        _isOfflineMode.value = false
        val chunks = textChunker.chunk(fullText)
        val settingsKey = audioCacheRepository.settingsKey(curVoice, curRate, curPitch)

        // Invalidate stale cache entries (different voice/rate/pitch)
        audioCacheRepository.invalidateStale(documentUri, settingsKey)

        // Readiness signals — one per chunk
        val readySignals = Array(chunks.size) { CompletableDeferred<Unit>() }

        // Find which chunks are already cached (single batch query)
        val cachedIndices = audioCacheRepository.getCachedIndices(documentUri, settingsKey)
        // Verify cached chunks still have valid files + matching text hash
        for (idx in cachedIndices) {
            if (idx < chunks.size) {
                val hash = chunks[idx].hashCode()
                val cached = audioCacheRepository.getChunk(documentUri, idx, settingsKey, hash)
                if (cached != null) {
                    readySignals[idx].complete(Unit)
                }
            }
        }

        val allCached = readySignals.all { it.isCompleted }
        val hasNetwork = networkUtil.isNetworkAvailable()

        if (allCached) {
            Log.d(TAG, "TtsManager: all ${chunks.size} chunks cached, playing from cache")
        } else if (!hasNetwork) {
            // Some chunks missing + no network → fall back to Android TTS
            Log.d(TAG, "TtsManager: missing chunks and offline, falling back to Android TTS")
            _isOfflineMode.value = true
            speakWithAndroidTts(fullText)
            return
        }

        // Use coroutineScope for structured concurrency:
        // - preSynthesize runs in a child `launch` (auto-cancelled on parent cancel)
        // - playSequentially runs in the same scope
        // - coroutineScope waits for both to finish
        coroutineScope {
            if (!allCached) {
                launch {
                    try {
                        preSynthesize(chunks, documentUri, settingsKey, curVoice, curRate, curPitch, readySignals)
                    } catch (e: CancellationException) {
                        throw e // propagate cancellation properly
                    } catch (e: Exception) {
                        // preSynthesize already completed deferreds exceptionally;
                        // swallow so coroutineScope doesn't cancel playSequentially
                        Log.e(TAG, "TtsManager: pre-synthesis coroutine failed", e)
                    }
                }
            }

            // Playback loop — await each chunk, play from cache
            playSequentially(chunks, documentUri, settingsKey, readySignals, fullText, startChunkIndex, startCharOffset)
        }
    }

    /**
     * Background pre-synthesis: iterates chunks, skips cached, synthesises uncached,
     * saves to cache, signals readiness.
     */
    private suspend fun preSynthesize(
        chunks: List<String>,
        documentUri: String,
        settingsKey: String,
        curVoice: String,
        curRate: String,
        curPitch: String,
        readySignals: Array<CompletableDeferred<Unit>>,
    ) {
        for ((idx, chunk) in chunks.withIndex()) {
            if (readySignals[idx].isCompleted) continue // already cached

            try {
                Log.d(TAG, "TtsManager: synthesizing chunk $idx of ${chunks.size}")

                val audioBuffer = ByteArrayOutputStream()
                val boundaries = mutableListOf<WordBoundary>()

                val request = SynthesisRequest(
                    text = chunk,
                    voiceName = curVoice,
                    rate = curRate,
                    pitch = curPitch,
                )

                var hadError = false
                edgeTtsClient.synthesize(request).collect { event ->
                    when (event) {
                        is EdgeTtsClient.SynthesisEvent.AudioChunkReceived -> {
                            audioBuffer.write(event.chunk.bytes)
                        }
                        is EdgeTtsClient.SynthesisEvent.WordBoundary -> {
                            boundaries.add(event.boundary)
                        }
                        is EdgeTtsClient.SynthesisEvent.TurnEnd -> {
                            Log.d(TAG, "TtsManager: chunk $idx synthesised (${boundaries.size} word boundaries)")
                        }
                        is EdgeTtsClient.SynthesisEvent.Error -> {
                            Log.e(TAG, "TtsManager: Edge TTS error on chunk $idx: ${event.message}")
                            hadError = true
                        }
                    }
                }

                if (hadError) {
                    // Complete remaining deferreds exceptionally so playback can fall back
                    val ex = RuntimeException("Edge TTS synthesis failed at chunk $idx")
                    for (i in idx until readySignals.size) {
                        if (!readySignals[i].isCompleted) {
                            readySignals[i].completeExceptionally(ex)
                        }
                    }
                    return
                }

                // Save to cache
                audioCacheRepository.saveChunk(
                    uri = documentUri,
                    idx = idx,
                    key = settingsKey,
                    chunkTextHash = chunk.hashCode(),
                    audioBytes = audioBuffer.toByteArray(),
                    boundaries = boundaries,
                )

                readySignals[idx].complete(Unit)
            } catch (e: CancellationException) {
                // Don't swallow cancellation — let it propagate for proper cleanup
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TtsManager: pre-synthesis error at chunk $idx", e)
                for (i in idx until readySignals.size) {
                    if (!readySignals[i].isCompleted) {
                        readySignals[i].completeExceptionally(e)
                    }
                }
                return
            }
        }
    }

    /**
     * Playback loop: awaits each chunk's readiness, loads from cache, plays via
     * [AudioPlayer.playFile], emits word boundaries synced to ExoPlayer position.
     */
    private suspend fun playSequentially(
        chunks: List<String>,
        documentUri: String,
        settingsKey: String,
        readySignals: Array<CompletableDeferred<Unit>>,
        fullText: String,
        startChunkIndex: Int = 0,
        startCharOffset: Int = 0,
    ) {
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            if (chunkIndex < startChunkIndex) continue

            if (!readySignals[chunkIndex].isCompleted) {
                _state.value = TtsState.Synthesizing(chunkIndex, chunks.size)
            }

            try {
                readySignals[chunkIndex].await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TtsManager: chunk $chunkIndex not available, falling back to Android TTS")
                _isOfflineMode.value = true
                speakWithAndroidTts(chunks.drop(chunkIndex).joinToString("\n\n"))
                return
            }

            val cached = audioCacheRepository.getChunk(
                documentUri, chunkIndex, settingsKey, chunk.hashCode()
            )
            if (cached == null) {
                Log.w(TAG, "TtsManager: cache miss for chunk $chunkIndex despite ready signal")
                continue
            }

            val started = audioPlayer.playFile(cached.audioFile)
            if (!started) continue
            _state.value = TtsState.Playing

            val boundaries = cached.wordBoundaries
            val seekIdx = if (chunkIndex == startChunkIndex && startCharOffset > 0) {
                findBoundaryByCharOffset(boundaries, startCharOffset)
            } else -1

            if (seekIdx >= 0) {
                audioPlayer.seekTo(boundaries[seekIdx].offsetMs)
                for (i in seekIdx until boundaries.size) {
                    while (audioPlayer.isPlaying.value && audioPlayer.currentPositionMs() < boundaries[i].offsetMs) {
                        delay(16)
                    }
                    if (!audioPlayer.isPlaying.value) break
                    _wordBoundaries.emit(boundaries[i])
                }
            } else {
                for (boundary in boundaries) {
                    while (audioPlayer.isPlaying.value && audioPlayer.currentPositionMs() < boundary.offsetMs) {
                        delay(16)
                    }
                    if (!audioPlayer.isPlaying.value) break
                    _wordBoundaries.emit(boundary)
                }
            }

            audioPlayer.awaitCompletion()
        }

        if (_state.value is TtsState.Playing) {
            _state.value = TtsState.Idle
        }
    }

    /**
     * Finds the boundary index whose textOffset is closest to [charOffset]
     * without exceeding it. Falls back to the nearest boundary if none is before.
     */
    private fun findBoundaryByCharOffset(boundaries: List<WordBoundary>, charOffset: Int): Int {
        if (boundaries.isEmpty()) return -1
        // Find last boundary whose textOffset <= charOffset
        var best = 0
        for ((i, b) in boundaries.withIndex()) {
            if (b.textOffset <= charOffset) best = i
            else break
        }
        return best
    }

    /**
     * Non-caching Edge TTS path — used when no documentUri is available
     * (e.g. ad-hoc text input).
     */
    private suspend fun speakWithEdgeTtsNoCaching(
        fullText: String,
        curVoice: String,
        curRate: String,
        curPitch: String,
        startChunkIndex: Int = 0,
        startCharOffset: Int = 0,
    ) {
        _isOfflineMode.value = false
        val chunks = textChunker.chunk(fullText)

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            if (chunkIndex < startChunkIndex) continue

            _state.value = TtsState.Synthesizing(chunkIndex, chunks.size)
            audioPlayer.prepareForChunk()

            val chunkBoundaries = mutableListOf<WordBoundary>()
            var hadError = false

            val request = SynthesisRequest(
                text = chunk,
                voiceName = curVoice,
                rate = curRate,
                pitch = curPitch,
            )

            edgeTtsClient.synthesize(request).collect { event ->
                when (event) {
                    is EdgeTtsClient.SynthesisEvent.AudioChunkReceived -> {
                        audioPlayer.appendChunk(event.chunk)
                    }
                    is EdgeTtsClient.SynthesisEvent.WordBoundary -> {
                        chunkBoundaries.add(event.boundary)
                    }
                    is EdgeTtsClient.SynthesisEvent.TurnEnd -> {
                        Log.d(TAG, "TtsManager: chunk $chunkIndex synthesised " +
                                "(${chunkBoundaries.size} word boundaries)")
                    }
                    is EdgeTtsClient.SynthesisEvent.Error -> {
                        Log.e(TAG, "TtsManager: Edge TTS error: ${event.message}")
                        _state.value = TtsState.Error(event.message)
                        _isOfflineMode.value = true
                        hadError = true
                        speakWithAndroidTts(chunks.drop(chunkIndex).joinToString("\n\n"))
                    }
                }
            }

            if (hadError) return

            val started = audioPlayer.playBuffer()
            if (!started) continue
            _state.value = TtsState.Playing

            val seekIdx = if (chunkIndex == startChunkIndex && startCharOffset > 0) {
                findBoundaryByCharOffset(chunkBoundaries, startCharOffset)
            } else -1

            if (seekIdx >= 0) {
                audioPlayer.seekTo(chunkBoundaries[seekIdx].offsetMs)
                for (i in seekIdx until chunkBoundaries.size) {
                    while (audioPlayer.isPlaying.value && audioPlayer.currentPositionMs() < chunkBoundaries[i].offsetMs) {
                        delay(16)
                    }
                    if (!audioPlayer.isPlaying.value) break
                    _wordBoundaries.emit(chunkBoundaries[i])
                }
            } else {
                for (boundary in chunkBoundaries) {
                    while (audioPlayer.isPlaying.value && audioPlayer.currentPositionMs() < boundary.offsetMs) {
                        delay(16)
                    }
                    if (!audioPlayer.isPlaying.value) break
                    _wordBoundaries.emit(boundary)
                }
            }

            audioPlayer.awaitCompletion()
        }

        if (_state.value is TtsState.Playing) {
            _state.value = TtsState.Idle
        }
    }

    private fun speakWithAndroidTts(text: String) {
        _state.value = TtsState.Playing
        val tts = androidTts ?: return
        val speedRate = parseRateToFloat(rate)
        tts.setSpeechRate(speedRate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "murmur_utterance")
    }

    private fun parseRateToFloat(rate: String): Float {
        return try {
            val pct = rate.replace("%", "").replace("+", "").trim().toFloat()
            1.0f + (pct / 100f)
        } catch (e: NumberFormatException) {
            1.0f
        }
    }

    fun pause() {
        audioPlayer.pause()
        androidTts?.stop()
        _state.value = TtsState.Paused
    }

    fun resume() {
        audioPlayer.resume()
        _state.value = TtsState.Playing
    }

    fun stop() {
        stopInternal()
        _state.value = TtsState.Idle
    }

    private fun stopInternal() {
        currentJob?.cancel()
        audioPlayer.stop()
        androidTts?.stop()
    }

    fun release() {
        stopInternal()
        edgeTtsClient.close()
        androidTts?.shutdown()
        androidTts = null
    }
}
