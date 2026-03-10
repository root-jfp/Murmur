package com.murmur.reader.ui.screens.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.murmur.reader.data.local.BookmarkDao
import com.murmur.reader.data.local.BookmarkEntity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import dagger.hilt.android.lifecycle.HiltViewModel
import com.murmur.reader.data.preferences.UserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
    val targetPage: Int = -1,
    val searchQuery: String = "",
    val searchMatchWordIndices: List<Int> = emptyList(),
    val currentMatchIndex: Int = -1,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val ttsManager: TtsManager,
    private val voiceRepository: VoiceRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
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

    /** Bookmarks for the currently-open document, reactively switching when document changes. */
    val bookmarks: StateFlow<List<BookmarkEntity>> = _uiState
        .map { it.documentUri?.toString() }
        .flatMapLatest { uri ->
            if (uri != null) bookmarkDao.observeForDocument(uri) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the current page has a bookmark. */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
        bookmarks,
        _uiState.map { it.currentPage },
    ) { bms, page -> bms.any { it.characterPosition == page } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Sequential counter — each boundary from TTS corresponds to the next word in order.
    // No text-matching needed; TTS delivers boundaries in the exact sequence they appear.
    private var wordBoundaryCount = 0

    // Debounce for playFromWordIndex — ignore calls within 300ms
    private var lastPlayFromWordMs = 0L

    // Cached global word index of the first word in each chunk (computed once when text changes)
    private var cachedChunkStarts: List<Int> = emptyList()

    // Cached page start word indices, reported by BookPager via onPagesComputed
    private var cachedPageStarts: List<Int> = emptyList()

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
        cachedChunkStarts = computeChunkStartWordIndices(clean)
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
                cachedChunkStarts = computeChunkStartWordIndices(clean)

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

    fun loadUrl(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDocument = true, errorMessage = null)
            try {
                val doc = withContext(Dispatchers.IO) {
                    Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        .timeout(15_000)
                        .get()
                }
                doc.select("script, style, nav, footer, header, aside").remove()
                val text = doc.body().select("p, h1, h2, h3, h4, h5, h6, li, blockquote")
                    .joinToString("\n\n") { it.text() }
                    .ifBlank { doc.body().text() }
                val clean = textSanitizer.sanitize(text)
                val title = doc.title().ifBlank { url }

                _uiState.value = _uiState.value.copy(
                    text = clean,
                    documentTitle = title,
                    documentUri = Uri.parse(url),
                    isLoadingDocument = false,
                    currentWordIndex = -1,
                )
                wordBoundaryCount = 0
                cachedChunkStarts = computeChunkStartWordIndices(clean)
                cachedPageStarts = emptyList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDocument = false,
                    errorMessage = "Failed to load URL: ${e.message}"
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
        cachedPageStarts = emptyList()
    }

    fun onPageChange(page: Int, total: Int) {
        _uiState.value = _uiState.value.copy(currentPage = page, totalPages = total)
    }

    fun selectVoice(voice: EdgeVoice) {
        viewModelScope.launch {
            prefsRepository.setVoiceName(voice.shortName)
        }
    }

    fun previewVoice(voice: EdgeVoice) {
        ttsManager.previewVoice(voice.shortName)
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

    /** Re-fetches voice list and clears offline mode flag. */
    fun retryConnection() {
        viewModelScope.launch {
            voiceRepository.fetchVoices()
        }
    }

    /**
     * Jumps TTS playback to the word at [globalWordIndex].
     * Debounced (300ms) — intended for tap-on-word interaction.
     */
    fun playFromWordIndex(globalWordIndex: Int) {
        val now = System.currentTimeMillis()
        if (now - lastPlayFromWordMs < 300) return // debounce
        lastPlayFromWordMs = now
        playFromWordIndexInternal(globalWordIndex)
    }

    /**
     * Internal (non-debounced) jump to [globalWordIndex].
     * Used by both tap-to-play and skip buttons.
     */
    private fun playFromWordIndexInternal(globalWordIndex: Int) {
        val text = _uiState.value.text
        if (text.isBlank()) return

        val wordRegex = Regex("\\S+")
        val allWords = wordRegex.findAll(text).toList()
        if (globalWordIndex < 0 || globalWordIndex >= allWords.size) return

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

        wordBoundaryCount = globalWordIndex
        _uiState.value = _uiState.value.copy(currentWordIndex = globalWordIndex)

        ttsManager.speak(text, _uiState.value.documentUri?.toString(), startChunkIndex, charOffsetInChunk)
    }

    // ── Bookmarks ──────────────────────────────────────────────

    fun toggleBookmark() {
        val state = _uiState.value
        val uri = state.documentUri ?: return
        viewModelScope.launch {
            val existing = bookmarks.value.find { it.characterPosition == state.currentPage }
            if (existing != null) {
                bookmarkDao.delete(existing)
            } else {
                bookmarkDao.insert(
                    BookmarkEntity(
                        documentUri = uri.toString(),
                        documentTitle = state.documentTitle,
                        characterPosition = state.currentPage,
                    )
                )
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { bookmarkDao.delete(bookmark) }
    }

    fun navigateToPage(pageIndex: Int) {
        _uiState.value = _uiState.value.copy(targetPage = pageIndex)
    }

    fun onNavigationConsumed() {
        _uiState.value = _uiState.value.copy(targetPage = -1)
    }

    // ── Text search ──────────────────────────────────────────

    fun onPagesComputed(pageStartWordIndices: List<Int>) {
        cachedPageStarts = pageStartWordIndices
    }

    fun setSearchQuery(query: String) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searchQuery = query,
                searchMatchWordIndices = emptyList(),
                currentMatchIndex = -1,
            )
            return
        }

        val text = _uiState.value.text
        val wordRegex = Regex("\\S+")
        val allWords = wordRegex.findAll(text).toList()

        // Find all case-insensitive occurrences (character-level)
        val matchWordIndices = mutableListOf<Int>()
        var searchFrom = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        while (true) {
            val charIdx = lowerText.indexOf(lowerQuery, searchFrom)
            if (charIdx < 0) break
            // Convert char offset to word index
            val wordIdx = allWords.indexOfFirst { charIdx in it.range }
            if (wordIdx >= 0 && (matchWordIndices.isEmpty() || matchWordIndices.last() != wordIdx)) {
                matchWordIndices.add(wordIdx)
            }
            searchFrom = charIdx + 1
        }

        val matchIndex = if (matchWordIndices.isNotEmpty()) 0 else -1
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            searchMatchWordIndices = matchWordIndices,
            currentMatchIndex = matchIndex,
        )

        if (matchIndex >= 0) {
            navigateToWordIndex(matchWordIndices[matchIndex])
        }
    }

    fun nextSearchMatch() {
        val state = _uiState.value
        if (state.searchMatchWordIndices.isEmpty()) return
        val next = (state.currentMatchIndex + 1) % state.searchMatchWordIndices.size
        _uiState.value = state.copy(currentMatchIndex = next)
        navigateToWordIndex(state.searchMatchWordIndices[next])
    }

    fun prevSearchMatch() {
        val state = _uiState.value
        if (state.searchMatchWordIndices.isEmpty()) return
        val prev = (state.currentMatchIndex - 1 + state.searchMatchWordIndices.size) % state.searchMatchWordIndices.size
        _uiState.value = state.copy(currentMatchIndex = prev)
        navigateToWordIndex(state.searchMatchWordIndices[prev])
    }

    fun clearSearch() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            searchMatchWordIndices = emptyList(),
            currentMatchIndex = -1,
        )
    }

    private fun navigateToWordIndex(wordIdx: Int) {
        if (cachedPageStarts.isEmpty()) return
        val page = cachedPageStarts.indexOfLast { it <= wordIdx }.coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(targetPage = page)
    }

    /** Skips to the next chunk (paragraph). No-op if already on the last chunk. */
    fun skipNext() {
        val text = _uiState.value.text
        if (text.isBlank() || cachedChunkStarts.isEmpty()) return

        val currentChunk = cachedChunkStarts.indexOfLast { it <= wordBoundaryCount }
            .coerceAtLeast(0)
        val nextChunk = currentChunk + 1
        if (nextChunk > cachedChunkStarts.lastIndex) return // already on last chunk

        playFromWordIndexInternal(cachedChunkStarts[nextChunk])
    }

    /** Skips to the previous chunk, or restarts the current chunk from the beginning. */
    fun skipPrev() {
        val text = _uiState.value.text
        if (text.isBlank() || cachedChunkStarts.isEmpty()) return

        val currentChunk = cachedChunkStarts.indexOfLast { it <= wordBoundaryCount }
            .coerceAtLeast(0)
        val wordsIntoChunk = wordBoundaryCount - cachedChunkStarts[currentChunk]

        val targetChunk = if (wordsIntoChunk < 3 && currentChunk > 0) {
            currentChunk - 1  // near start of chunk → go to previous
        } else {
            currentChunk      // deep into chunk → restart current
        }

        playFromWordIndexInternal(cachedChunkStarts[targetChunk])
    }

    /**
     * Computes the global word index of the first word in each chunk.
     * Uses the same `\S+` regex as [playFromWordIndexInternal] for consistency.
     */
    private fun computeChunkStartWordIndices(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val wordRegex = Regex("\\S+")
        val chunks = textChunker.chunk(text)
        var cumulative = 0
        return chunks.map { chunk ->
            val start = cumulative
            cumulative += wordRegex.findAll(chunk).count()
            start
        }
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
