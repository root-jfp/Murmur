package com.murmur.reader.document

import android.net.Uri

/**
 * Common interface for all document parsers.
 * Each parser extracts plain text from a different file format.
 */
interface DocumentParser {
    /** Supported MIME types this parser handles */
    val supportedMimeTypes: List<String>

    /**
     * Extracts text from the document at [uri].
     * @return Extracted plain text, or throws on failure.
     */
    suspend fun extractText(uri: Uri): String
}
