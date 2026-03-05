package com.murmur.reader.document

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.zip.ZipInputStream
import javax.inject.Inject

private const val TAG = "Murmur"

/**
 * Minimal EPUB parser that extracts text from HTML content files inside the zip.
 * Does not require an external EPUB library — reads the zip directly.
 */
class EpubParser @Inject constructor(
    @ApplicationContext private val context: Context
) : DocumentParser {

    override val supportedMimeTypes = listOf("application/epub+zip")

    override suspend fun extractText(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        val textParts = mutableListOf<String>()

        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm"))) {
                    try {
                        val bytes = zip.readBytes()
                        val html = bytes.toString(Charsets.UTF_8)
                        val doc = Jsoup.parse(html)
                        doc.select("script, style").remove()
                        val text = doc.body().text().trim()
                        if (text.isNotBlank()) textParts.add(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "EpubParser: failed to parse entry ${entry.name}", e)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        Log.d(TAG, "EpubParser: extracted ${textParts.size} content sections")
        textParts.joinToString("\n\n")
    }
}
