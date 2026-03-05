package com.murmur.reader.tts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Murmur"
private const val VOICE_LIST_URL =
    "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
    "?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

@Singleton
class VoiceRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _voices = MutableStateFlow<List<EdgeVoice>>(emptyList())
    val voices: StateFlow<List<EdgeVoice>> = _voices

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Cached in-memory — fetched once per session
    private var cachedVoices: List<EdgeVoice>? = null

    /** Default voices shown before the full list loads */
    val defaultVoices = listOf(
        EdgeVoice(
            name = "Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)",
            shortName = "en-US-AriaNeural",
            gender = "Female",
            locale = "en-US",
            friendlyName = "Microsoft Aria Online (Natural) - English (United States)"
        ),
        EdgeVoice(
            name = "Microsoft Server Speech Text to Speech Voice (en-US, GuyNeural)",
            shortName = "en-US-GuyNeural",
            gender = "Male",
            locale = "en-US",
            friendlyName = "Microsoft Guy Online (Natural) - English (United States)"
        ),
        EdgeVoice(
            name = "Microsoft Server Speech Text to Speech Voice (pt-PT, RaquelNeural)",
            shortName = "pt-PT-RaquelNeural",
            gender = "Female",
            locale = "pt-PT",
            friendlyName = "Microsoft Raquel Online (Natural) - Portuguese (Portugal)"
        ),
        EdgeVoice(
            name = "Microsoft Server Speech Text to Speech Voice (pt-PT, DuarteNeural)",
            shortName = "pt-PT-DuarteNeural",
            gender = "Male",
            locale = "pt-PT",
            friendlyName = "Microsoft Duarte Online (Natural) - Portuguese (Portugal)"
        ),
    )

    /**
     * Fetches the full voice list from the Edge TTS endpoint.
     * Results are cached in memory for the session.
     */
    suspend fun fetchVoices(): Result<List<EdgeVoice>> = withContext(Dispatchers.IO) {
        cachedVoices?.let { return@withContext Result.success(it) }

        _isLoading.value = true
        try {
            val request = Request.Builder()
                .url(VOICE_LIST_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val voices = json.decodeFromString<List<EdgeVoice>>(body)
                .sortedWith(compareBy({ it.locale }, { it.friendlyName }))

            cachedVoices = voices
            _voices.value = voices
            Log.d(TAG, "VoiceRepository: fetched ${voices.size} voices")
            Result.success(voices)
        } catch (e: Exception) {
            Log.e(TAG, "VoiceRepository: failed to fetch voices", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    /** Returns voices for a given locale prefix (e.g. "en", "pt") */
    fun voicesByLanguage(langPrefix: String): List<EdgeVoice> {
        val all = cachedVoices ?: defaultVoices
        return all.filter { it.locale.startsWith(langPrefix, ignoreCase = true) }
    }

    /** Returns all unique locale codes from the cached voice list */
    fun availableLocales(): List<String> {
        return (cachedVoices ?: defaultVoices).map { it.locale }.distinct().sorted()
    }
}
