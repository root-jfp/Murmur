package com.murmur.reader.document

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PlainTextParser @Inject constructor(
    @ApplicationContext private val context: Context
) : DocumentParser {

    override val supportedMimeTypes = listOf("text/plain", "text/markdown", "text/x-markdown")

    override suspend fun extractText(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")
        stream.use { it.bufferedReader().readText() }
    }
}
