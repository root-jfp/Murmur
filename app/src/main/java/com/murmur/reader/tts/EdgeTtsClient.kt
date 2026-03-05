package com.murmur.reader.tts

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Murmur"

private const val CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
private const val CHROMIUM_VERSION = "143.0.3650.75"
private const val CHROMIUM_MAJOR = "143"

// The Edge TTS WebSocket base URL (ConnectionId + Sec-MS-GEC appended per-connection)
private const val EDGE_TTS_URL_BASE =
    "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
    "?TrustedClientToken=$CLIENT_TOKEN"

private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_VERSION"

/**
 * Generates the Sec-MS-GEC DRM token required by the Edge TTS API.
 *
 * Algorithm (from github.com/rany2/edge-tts drm.py):
 *  1. Get Unix timestamp in seconds, add Windows epoch offset (11644473600)
 *  2. Round DOWN to nearest 5 minutes (300 seconds)
 *  3. Convert to 100-nanosecond intervals (* 10_000_000)
 *  4. SHA256("{ticks}{CLIENT_TOKEN}") → uppercase hex
 */
private fun generateSecMsGec(): String {
    val unixSeconds = System.currentTimeMillis() / 1000.0
    // Add Windows epoch offset (seconds from 1601-01-01 to 1970-01-01)
    var ticks = unixSeconds + 11_644_473_600.0
    // Round down to nearest 300 seconds (5 minutes)
    ticks -= ticks % 300
    // Convert seconds → 100-nanosecond intervals
    ticks *= 10_000_000.0
    val input = "${ticks.toLong()}${CLIENT_TOKEN}"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
    return digest.joinToString("") { "%02X".format(it) }
}

/** Generates a random MUID cookie value (16 random bytes as uppercase hex). */
private fun generateMuid(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02X".format(it) }
}

/**
 * Low-level Edge TTS WebSocket client.
 *
 * Usage:
 *  val flow = client.synthesize(request)
 *  flow.collect { event -> ... }
 *
 * Call [close] when done to release the WebSocket connection.
 *
 * IMPORTANT: The WebSocket connection is reused across multiple synthesize()
 * calls. Do NOT create a new client per sentence — Microsoft throttles
 * frequent reconnections.
 */
@Singleton
class EdgeTtsClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Events emitted during synthesis */
    sealed interface SynthesisEvent {
        data class AudioChunkReceived(val chunk: AudioChunk) : SynthesisEvent
        data class WordBoundary(val boundary: com.murmur.reader.tts.WordBoundary) : SynthesisEvent
        object TurnEnd : SynthesisEvent
        data class Error(val message: String, val cause: Throwable? = null) : SynthesisEvent
    }

    private var webSocket: WebSocket? = null

    // Channel used to pass events from the WebSocket listener thread to the flow collector
    private var eventChannel: Channel<SynthesisEvent>? = null

    /**
     * Synthesizes text and returns a Flow of [SynthesisEvent].
     * Collects audio chunks and word boundaries in real time.
     *
     * The connection is established lazily on the first call and reused thereafter.
     */
    fun synthesize(request: SynthesisRequest): Flow<SynthesisEvent> = flow {
        val channel = Channel<SynthesisEvent>(Channel.UNLIMITED)
        eventChannel = channel

        // Connect if not already connected
        if (webSocket == null) {
            Log.d(TAG, "EdgeTtsClient: opening new WebSocket connection")
            // ConnectionId and Sec-MS-GEC go in the URL query string (not headers)
            // per the edge-tts Python reference implementation (communicate.py)
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val secMsGec = generateSecMsGec()
            val muid = generateMuid()
            val wsUrl = "$EDGE_TTS_URL_BASE" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$secMsGec" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
            val wsRequest = Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", ORIGIN)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-WebSocket-Version", "13")
                .header("Cookie", "muid=$muid;")
                .build()
            Log.d(TAG, "EdgeTtsClient: connectionId=$connectionId Sec-MS-GEC=$secMsGec")

            webSocket = okHttpClient.newWebSocket(wsRequest, createListener())
        }

        // Send speech.config first (required before each session of synthesis)
        val configMsg = EdgeTtsProtocol.buildSpeechConfig()
        webSocket?.send(configMsg)
        Log.d(TAG, "EdgeTtsClient: sent speech.config")

        // Send SSML request
        val ssmlMsg = EdgeTtsProtocol.buildSsmlRequest(request)
        webSocket?.send(ssmlMsg)
        Log.d(TAG, "EdgeTtsClient: sent SSML for voice=${request.voiceName}, textLen=${request.text.length}")

        // Collect events until turn.end or error
        for (event in channel) {
            emit(event)
            if (event is SynthesisEvent.TurnEnd || event is SynthesisEvent.Error) break
        }
    }

    private fun createListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "EdgeTtsClient: WebSocket opened (${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val path = EdgeTtsProtocol.parsePath(text) ?: return

            when (path) {
                "turn.start" -> Log.d(TAG, "EdgeTtsClient: turn.start")
                "turn.end" -> {
                    Log.d(TAG, "EdgeTtsClient: turn.end")
                    eventChannel?.trySend(SynthesisEvent.TurnEnd)
                }
                "audio.metadata" -> {
                    val body = EdgeTtsProtocol.parseBody(text) ?: return
                    try {
                        val meta = json.decodeFromString<MetadataMessage>(body)
                        meta.metadata.forEach { item ->
                            if (item.type == "WordBoundary") {
                                val data = item.data
                                val wordText = data.text?.text ?: return@forEach
                                // Offset is in 100-nanosecond ticks — convert to ms
                                val offsetMs = data.offset / 10_000L
                                val durationMs = data.duration / 10_000L
                                eventChannel?.trySend(
                                    SynthesisEvent.WordBoundary(
                                        WordBoundary(
                                            offsetMs = offsetMs,
                                            durationMs = durationMs,
                                            text = wordText,
                                            wordLength = data.text.length,
                                        )
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "EdgeTtsClient: failed to parse audio.metadata: $body", e)
                    }
                }
                else -> Log.v(TAG, "EdgeTtsClient: unknown path=$path")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val rawBytes = bytes.toByteArray()
            if (!EdgeTtsProtocol.isAudioFrame(rawBytes)) return

            val audioBytes = EdgeTtsProtocol.extractAudioBytes(rawBytes) ?: return
            eventChannel?.trySend(SynthesisEvent.AudioChunkReceived(AudioChunk(audioBytes)))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "EdgeTtsClient: WebSocket failure: ${t.message}", t)
            this@EdgeTtsClient.webSocket = null
            eventChannel?.trySend(SynthesisEvent.Error("Connection failed: ${t.message}", t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "EdgeTtsClient: WebSocket closed (code=$code, reason=$reason)")
            this@EdgeTtsClient.webSocket = null
        }
    }

    /** Closes the WebSocket connection. Call when the app no longer needs TTS. */
    fun close() {
        webSocket?.close(1000, "Done")
        webSocket = null
        Log.d(TAG, "EdgeTtsClient: connection closed")
    }
}
