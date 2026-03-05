package com.murmur.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.murmur.reader.audio.AudioPlayer
import com.murmur.reader.data.preferences.UserPreferencesRepository
import com.murmur.reader.document.TextChunker
import com.murmur.reader.util.NetworkUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Murmur"

/**
 * High-level TTS orchestrator.
 *
 * Coordinates:
 * - Text chunking
 * - Edge TTS synthesis (online)
 * - Android TTS fallback (offline)
 * - AudioPlayer streaming
 * - Word boundary event forwarding
 *
 * Preferences are observed on the singleton's own [scope] so they stay in sync
 * regardless of which ViewModel or screen is currently active.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val edgeTtsClient: EdgeTtsClient,
    private val audioPlayer: AudioPlayer,
    private val textChunker: TextChunker,
    private val networkUtil: NetworkUtil,
    private val prefsRepository: UserPreferencesRepository,
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

    // Current synthesis settings — kept in sync with DataStore preferences below
    var voiceName: String = "en-US-AriaNeural"
        private set
    var rate: String = "+0%"
        private set
    var pitch: String = "+0Hz"
        private set

    init {
        initAndroidTts()

        // Observe preferences on the singleton's own scope — not tied to any ViewModel.
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
     * Automatically falls back to Android TTS if offline.
     */
    fun speak(text: String) {
        stopInternal()
        currentJob = scope.launch {
            if (networkUtil.isNetworkAvailable()) {
                speakWithEdgeTts(text)
            } else {
                _isOfflineMode.value = true
                speakWithAndroidTts(text)
            }
        }
    }

    private suspend fun speakWithEdgeTts(fullText: String) {
        _isOfflineMode.value = false
        val chunks = textChunker.chunk(fullText)
        _state.value = TtsState.Synthesizing(0, chunks.size)

        audioPlayer.prepareForStreaming()

        var chunkIndex = 0
        for (chunk in chunks) {
            if (currentJob?.isCancelled == true) break

            _state.value = TtsState.Synthesizing(chunkIndex, chunks.size)
            val request = SynthesisRequest(
                text = chunk,
                voiceName = voiceName,
                rate = rate,
                pitch = pitch,
            )

            edgeTtsClient.synthesize(request).collect { event ->
                when (event) {
                    is EdgeTtsClient.SynthesisEvent.AudioChunkReceived -> {
                        audioPlayer.appendChunk(event.chunk)
                        if (_state.value !is TtsState.Playing) {
                            _state.value = TtsState.Playing
                        }
                    }
                    is EdgeTtsClient.SynthesisEvent.WordBoundary -> {
                        _wordBoundaries.emit(event.boundary)
                    }
                    is EdgeTtsClient.SynthesisEvent.TurnEnd -> {
                        Log.d(TAG, "TtsManager: chunk $chunkIndex done")
                    }
                    is EdgeTtsClient.SynthesisEvent.Error -> {
                        Log.e(TAG, "TtsManager: Edge TTS error: ${event.message}")
                        _state.value = TtsState.Error(event.message)
                        _isOfflineMode.value = true
                        // Preserve paragraph structure when joining remaining chunks
                        speakWithAndroidTts(chunks.drop(chunkIndex).joinToString("\n\n"))
                        return@collect
                    }
                }
            }
            chunkIndex++
        }

        if (_state.value == TtsState.Playing) {
            audioPlayer.finishStreaming()
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
