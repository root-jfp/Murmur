package com.murmur.reader.tts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Voice List ─────────────────────────────────────────────────────────────

@Serializable
data class EdgeVoice(
    @SerialName("Name") val name: String,
    @SerialName("ShortName") val shortName: String,
    @SerialName("Gender") val gender: String,
    @SerialName("Locale") val locale: String,
    @SerialName("FriendlyName") val friendlyName: String,
    @SerialName("VoicePersonalities") val voicePersonalities: List<String> = emptyList(),
    @SerialName("Status") val status: String = "",
    @SerialName("SuggestedCodec") val suggestedCodec: String = "",
    @SerialName("WordsPerMinute") val wordsPerMinute: String = "",
)

// ─── Word Boundary (from audio.metadata) ────────────────────────────────────

data class WordBoundary(
    /** Time offset from audio start, in milliseconds */
    val offsetMs: Long,
    /** Duration of the word in milliseconds */
    val durationMs: Long,
    /** The word text */
    val text: String,
    /** Character offset in the original text string */
    val textOffset: Int = 0,
    /** Length of the word in characters */
    val wordLength: Int = 0,
)

// ─── Audio Chunk ─────────────────────────────────────────────────────────────

data class AudioChunk(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = bytes.contentHashCode()
}

// ─── TTS Playback State ──────────────────────────────────────────────────────

sealed interface TtsState {
    object Idle : TtsState
    object Connecting : TtsState
    data class Synthesizing(val chunkIndex: Int, val totalChunks: Int) : TtsState
    object Playing : TtsState
    object Paused : TtsState
    data class Error(val message: String) : TtsState
}

// ─── TTS Synthesis Request ───────────────────────────────────────────────────

data class SynthesisRequest(
    val text: String,
    val voiceName: String,
    val rate: String = "+0%",
    val pitch: String = "+0Hz",
    val volume: String = "+0%",
)

// ─── Metadata JSON structures ────────────────────────────────────────────────

@Serializable
data class MetadataMessage(
    @SerialName("Metadata") val metadata: List<MetadataItem>
)

@Serializable
data class MetadataItem(
    @SerialName("Type") val type: String,
    @SerialName("Data") val data: MetadataData
)

@Serializable
data class MetadataData(
    @SerialName("Offset") val offset: Long = 0L,
    @SerialName("Duration") val duration: Long = 0L,
    @SerialName("text") val text: MetadataText? = null,
)

@Serializable
data class MetadataText(
    @SerialName("Text") val text: String = "",
    @SerialName("Length") val length: Int = 0,
    @SerialName("BoundaryType") val boundaryType: String = "",
)
