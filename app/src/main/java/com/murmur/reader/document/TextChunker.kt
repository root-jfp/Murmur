package com.murmur.reader.document

import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_CHUNK_CHARS = 2000

/**
 * Splits long text into chunks suitable for Edge TTS.
 *
 * Strategy:
 * 1. Split by paragraph (double newline)
 * 2. If a paragraph exceeds MAX_CHUNK_CHARS, split by sentence
 * 3. If a sentence exceeds MAX_CHUNK_CHARS, hard-split by character count
 */
@Singleton
class TextChunker @Inject constructor() {

    fun chunk(text: String): List<String> {
        val paragraphs = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (currentChunk.length + paragraph.length + 2 <= MAX_CHUNK_CHARS) {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(paragraph)
            } else {
                // Flush current chunk
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk.clear()
                }

                if (paragraph.length <= MAX_CHUNK_CHARS) {
                    currentChunk.append(paragraph)
                } else {
                    // Split long paragraph by sentence
                    chunks.addAll(splitBySentence(paragraph))
                }
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun splitBySentence(text: String): List<String> {
        // Simple sentence splitter: split on ". ", "! ", "? ", ".\n"
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (sentence in sentences) {
            if (currentChunk.length + sentence.length + 1 <= MAX_CHUNK_CHARS) {
                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                currentChunk.append(sentence)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk.clear()
                }
                if (sentence.length <= MAX_CHUNK_CHARS) {
                    currentChunk.append(sentence)
                } else {
                    // Hard split — sentence is too long
                    chunks.addAll(hardSplit(sentence))
                }
            }
        }

        if (currentChunk.isNotEmpty()) chunks.add(currentChunk.toString())
        return chunks
    }

    private fun hardSplit(text: String): List<String> {
        return text.chunked(MAX_CHUNK_CHARS)
    }
}
