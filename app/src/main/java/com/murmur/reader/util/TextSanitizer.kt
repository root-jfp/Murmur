package com.murmur.reader.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cleans text before sending to Edge TTS to avoid SSML injection or
 * sending content that causes synthesis failures.
 */
@Singleton
class TextSanitizer @Inject constructor() {

    /**
     * Removes control characters and trims excessive whitespace.
     * XML escaping is handled by EdgeTtsProtocol.buildSsml().
     */
    fun sanitize(text: String): String {
        return text
            // Remove null bytes and other control chars (except newlines/tabs)
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            // Collapse runs of whitespace (but preserve paragraph breaks as \n\n)
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
