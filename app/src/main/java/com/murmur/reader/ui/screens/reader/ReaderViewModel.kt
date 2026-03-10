package com.murmur.reader.ui.screens.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.murmur.reader.data.local.ReadingProgressDao
import com.murmur.reader.data.local.ReadingProgressEntity
import com.murmur.reader.data.preferences.UserPreferencesRepository
import android.util.Log
import com.murmur.reader.document.DocumentParser
import com.murmur.reader.document.EpubParser
import com.murmur.reader.document.HtmlParser
import com.murmur.reader.document.PdfParser
import com.murmur.reader.document.PlainTextParser
import com.murmur.reader.document.TextChunker
import com.murmur.reader.tts.TtsManager
import com.murmur.reader.tts.TtsState
import com.murmur.reader.tts.VoiceRepository
import com.murmur.reader.tts.EdgeVoice
import com.murmur.reader.util.TextSanitizer
import dagger.hilt.android.lifecycle.HiltViewModel
import com.murmur.reader.data.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val text: String = "",
    val isLoadingDocument: Boolean = false,
    val documentTitle: String = "",
    val documentUri: Uri? = null,
    val currentWordIndex: Int = -1,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val errorMessage: String? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val ttsManager: TtsManager,
    private val voiceRepository: VoiceRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val progressDao: ReadingProgressDao,
    private val textSanitizer: TextSanitizer,
    private val textChunker: TextChunker,
    private val pdfParser: PdfParser,
    private val htmlParser: HtmlParser,
    private val plainTextParser: PlainTextParser,
    private val epubParser: EpubParser,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    val ttsState: StateFlow<TtsState> = ttsManager.state
    val isOfflineMode: StateFlow<Boolean> = ttsManager.isOfflineMode

    val voices: StateFlow<List<EdgeVoice>> = voiceRepository.voices
    val isLoadingVoices: StateFlow<Boolean> = voiceRepository.isLoading

    /** Exposes user display preferences so the ReaderScreen can pass them to BookPager. */
    val displayPrefs = prefsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    // Sequential counter — each boundary from TTS corresponds to the next word in order.
    // No text-matching needed; TTS delivers boundaries in the exact sequence they appear.
    private var wordBoundaryCount = 0

    // Debounce for playFromWordIndex — ignore calls within 300ms
    private var lastPlayFromWordMs = 0L

    init {
        // Advance the highlight index for every word boundary received
        ttsManager.wordBoundaries
            .onEach {
                _uiState.value = _uiState.value.copy(currentWordIndex = wordBoundaryCount++)
            }
            .launchIn(viewModelScope)

        // Fetch voice list in background
        viewModelScope.launch {
            voiceRepository.fetchVoices()
        }
    }

    fun setText(text: String) {
        val clean = textSanitizer.sanitize(text)
        _uiState.value = _uiState.value.copy(
            text = clean,
            documentTitle = "Entered text",
            documentUri = null,
            currentWordIndex = -1,
            errorMessage = null,
        )
        wordBoundaryCount = 0
    }

    fun loadDocument(uri: Uri, mimeType: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDocument = true, errorMessage = null)
            try {
                val parser: DocumentParser = when {
                    mimeType == "application/pdf" || uri.path?.endsWith(".pdf") == true -> pdfParser
                    mimeType == "application/epub+zip" || uri.path?.endsWith(".epub") == true -> epubParser
                    mimeType?.startsWith("text/html") == true || uri.path?.endsWith(".html") == true -> htmlParser
                    else -> plainTextParser
                }
                val rawText = parser.extractText(uri)
                val clean = textSanitizer.sanitize(rawText)
                val title = uri.lastPathSegment ?: "Document"

                _uiState.value = _uiState.value.copy(
                    text = clean,
                    documentTitle = title,
                    documentUri = uri,
                    isLoadingDocument = false,
                    currentWordIndex = -1,
                )
                wordBoundaryCount = 0

                // Restore reading progress if available
                val progress = progressDao.getProgress(uri.toString())
                if (progress != null) {
                    _uiState.value = _uiState.value.copy(currentPage = progress.currentPageIndex)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDocument = false,
                    errorMessage = "Failed to open document: ${e.message}"
                )
            }
        }
    }

    fun play() {
        val text = _uiState.value.text
        if (text.isBlank()) return
        wordBoundaryCount = 0
        _uiState.value = _uiState.value.copy(currentWordIndex = -1)
        ttsManager.speak(text, _uiState.value.documentUri?.toString())
    }

    fun pause() = ttsManager.pause()

    fun resume() = ttsManager.resume()

    fun stop() {
        ttsManager.stop()
        _uiState.value = _uiState.value.copy(currentWordIndex = -1)
        saveProgress()
    }

    fun clearDocument() {
        ttsManager.stop()
        saveProgress()
        _uiState.value = ReaderUiState()
        wordBoundaryCount = 0
    }

    fun onPageChange(page: Int, total: Int) {
        _uiState.value = _uiState.value.copy(currentPage = page, totalPages = total)
    }

    fun selectVoice(voice: EdgeVoice) {
        viewModelScope.launch {
            prefsRepository.setVoiceName(voice.shortName)
        }
    }

    fun setRate(percent: Float) {
        viewModelScope.launch {
            prefsRepository.setRate(percent)
        }
    }

    fun setPitch(hz: Float) {
        viewModelScope.launch {
            prefsRepository.setPitch(hz)
        }
    }

    fun setFontSize(size: Float) {
        viewModelScope.launch {
            prefsRepository.setFontSize(size)
        }
    }

    /**
     * Jumps TTS playback to the word at [globalWordIndex].
     * Maps the global word index to a chunk index + word offset within that chunk,
     * using the same whitespace split as BookPager for consistency.
     */
    fun playFromWordIndex(globalWordIndex: Int) {
        val now = System.currentTimeMillis()
        if (now - lastPlayFromWordMs < 300) return // debounce
        lastPlayFromWordMs = now

        val text = _uiState.value.text
        if (text.isBlank()) return

        // Find the character offset of the target word in the full text
        val wordRegex = Regex("\\S+")
        val allWords = wordRegex.findAll(text).toList()
        if (globalWordIndex < 0 || globalWordIndex >= allWords.size) return
        val targetCharOffset = allWords[globalWordIndex].range.first

        // Count words per chunk to find which chunk contains the target word,
        // and compute the character offset of the word within that chunk.
        val chunks = textChunker.chunk(text)
        var wordsBeforeChunk = 0
        var startChunkIndex = 0
        var charOffsetInChunk = 0

        for ((idx, chunk) in chunks.withIndex()) {
            val chunkWords = wordRegex.findAll(chunk).toList()
            val wordsInChunk = chunkWords.size
            if (wordsBeforeChunk + wordsInChunk > globalWordIndex || idx == chunks.lastIndex) {
                startChunkIndex = idx
                val wordIdxInChunk = (globalWordIndex - wordsBeforeChunk).coerceIn(0, chunkWords.lastIndex)
                charOffsetInChunk = chunkWords[wordIdxInChunk].range.first
                break
            }
            wordsBeforeChunk += wordsInChunk
        }

        Log.d("Murmur", "playFromWordIndex: global=$globalWordIndex → chunk=$startChunkIndex, charOffset=$charOffsetInChunk")

        // Update counters so highlighting starts from the tapped word
        wordBoundaryCount = globalWordIndex
        _uiState.value = _uiState.value.copy(currentWordIndex = globalWordIndex)

        ttsManager.speak(text, _uiState.value.documentUri?.toString(), startChunkIndex, charOffsetInChunk)
    }

    private fun saveProgress() {
        val state = _uiState.value
        val uri = state.documentUri ?: return
        viewModelScope.launch {
            progressDao.upsert(
                ReadingProgressEntity(
                    documentUri = uri.toString(),
                    documentTitle = state.documentTitle,
                    currentPageIndex = state.currentPage,
                    totalPageCount = state.totalPages,
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.stop()
    }
}
