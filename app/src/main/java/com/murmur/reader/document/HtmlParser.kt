package com.murmur.reader.document

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import javax.inject.Inject

class HtmlParser @Inject constructor(
    @ApplicationContext private val context: Context
) : DocumentParser {

    override val supportedMimeTypes = listOf("text/html", "application/xhtml+xml")

    override suspend fun extractText(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        stream.use { input ->
            val doc = Jsoup.parse(input, "UTF-8", uri.toString())
            // Remove script/style noise
            doc.select("script, style, nav, footer, header, aside").remove()
            // Extract text preserving paragraph breaks
            doc.body().select("p, h1, h2, h3, h4, h5, h6, li, blockquote").joinToString("\n\n") {
                it.text()
            }.ifBlank { doc.body().text() }
        }
    }

    /** Also handles parsing raw HTML strings (for web content) */
    suspend fun parseHtmlString(html: String, baseUri: String = ""): String =
        withContext(Dispatchers.IO) {
            val doc = Jsoup.parse(html, baseUri)
            doc.select("script, style, nav, footer, header, aside").remove()
            doc.body().select("p, h1, h2, h3, h4, h5, h6, li, blockquote").joinToString("\n\n") {
                it.text()
            }.ifBlank { doc.body().text() }
        }
}
