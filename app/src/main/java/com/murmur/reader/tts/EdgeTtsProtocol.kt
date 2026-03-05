package com.murmur.reader.tts

import android.util.Log
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private const val TAG = "Murmur"

/**
 * Builds and parses Edge TTS WebSocket messages.
 *
 * Protocol reference: https://github.com/rany2/edge-tts (communicate.py)
 */
object EdgeTtsProtocol {

    // JavaScript-style date format matching the edge-tts Python reference:
    // "Mon Feb 23 2026 14:00:00 GMT+0000 (Coordinated Universal Time)"
    private val timestampFormatter = DateTimeFormatter
        .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'")
        .withLocale(Locale.ENGLISH)
        .withZone(ZoneOffset.UTC)

    private fun timestamp(): String = timestampFormatter.format(Instant.now())

    // ─── Outgoing messages ───────────────────────────────────────────────────

    /**
     * speech.config — sent once after WebSocket opens.
     * Requests MP3 output with word + sentence boundary metadata.
     */
    fun buildSpeechConfig(): String {
        val ts = timestamp()
        return "X-Timestamp:$ts\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"true"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
    }

    /**
     * SSML synthesis request — sent for each text chunk to synthesize.
     */
    fun buildSsmlRequest(request: SynthesisRequest): String {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val ts = timestamp()
        val ssml = buildSsml(request)
        return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:$ts\r\n" +
                "Path:ssml\r\n\r\n" +
                ssml
    }

    private fun buildSsml(request: SynthesisRequest): String {
        // Escape XML special characters in the text
        val safeText = request.text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val lang = request.voiceName.substringBefore("-", "en").let { l ->
            "${l}-${request.voiceName.split("-").getOrElse(1) { "US" }}"
        }

        return """<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'><voice name='${request.voiceName}'><prosody pitch='${request.pitch}' rate='${request.rate}' volume='${request.volume}'>${safeText}</prosody></voice></speak>"""
    }

    // ─── Incoming message parsing ────────────────────────────────────────────

    /** Returns the Path header value from a WebSocket text message. */
    fun parsePath(message: String): String? {
        return message.lines().firstOrNull { it.startsWith("Path:") }
            ?.removePrefix("Path:")
            ?.trim()
    }

    /**
     * Parses the JSON body from a text message.
     * The body is everything after the double CRLF header separator.
     */
    fun parseBody(message: String): String? {
        val separator = "\r\n\r\n"
        val idx = message.indexOf(separator)
        return if (idx >= 0) message.substring(idx + separator.length) else null
    }

    /**
     * Extracts raw MP3 bytes from a binary WebSocket frame.
     *
     * Binary frame format:
     *  [0..1]  big-endian unsigned short = header length (N)
     *  [2..N+1] text header (contains Path:audio and other headers)
     *  [N+2..] raw MP3 audio bytes
     */
    fun extractAudioBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 2) return null
        // First two bytes = header length as big-endian unsigned short
        val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val audioStart = 2 + headerLength
        if (audioStart >= bytes.size) {
            Log.w(TAG, "Binary frame has no audio data (headerLength=$headerLength, total=${bytes.size})")
            return null
        }
        return bytes.copyOfRange(audioStart, bytes.size)
    }

    /**
     * Checks if a binary frame's header contains the "audio" path.
     * This filters out non-audio binary frames.
     */
    fun isAudioFrame(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (2 + headerLength > bytes.size) return false
        val header = String(bytes, 2, headerLength, Charsets.UTF_8)
        return header.contains("Path:audio")
    }
}
