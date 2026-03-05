package com.murmur.reader.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Murmur"

// If PDFBox extracts fewer than this many characters per page on average,
// we treat the PDF as scanned and fall back to OCR.
private const val MIN_CHARS_PER_PAGE = 50

class PdfParser @Inject constructor(
    @ApplicationContext private val context: Context
) : DocumentParser {

    override val supportedMimeTypes = listOf("application/pdf")

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        PDFBoxResourceLoader.init(context)
    }

    override suspend fun extractText(uri: Uri): String = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        // Step 1: Try PDFBox text extraction
        val pdfboxText = inputStream.use { stream ->
            try {
                val document = PDDocument.load(stream)
                document.use { doc ->
                    Log.d(TAG, "PdfParser: ${doc.numberOfPages} pages")
                    PDFTextStripper().getText(doc)
                }
            } catch (e: Exception) {
                Log.w(TAG, "PdfParser: PDFBox extraction failed", e)
                ""
            }
        }

        // Step 2: Check if extraction was meaningful
        val pageCount = estimatePageCount(uri)
        val avgCharsPerPage = if (pageCount > 0) pdfboxText.length / pageCount else pdfboxText.length

        if (avgCharsPerPage >= MIN_CHARS_PER_PAGE) {
            Log.d(TAG, "PdfParser: using PDFBox text (${pdfboxText.length} chars)")
            return@withContext pdfboxText.trim()
        }

        // Step 3: Scanned PDF — fall back to ML Kit OCR
        Log.d(TAG, "PdfParser: sparse text ($avgCharsPerPage chars/page avg), switching to OCR")
        extractTextWithOcr(uri, pageCount)
    }

    /**
     * Renders each PDF page to a Bitmap and runs ML Kit OCR on it.
     */
    private suspend fun extractTextWithOcr(uri: Uri, pageCount: Int): String =
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor for URI: $uri")

            val pages = mutableListOf<String>()

            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val total = renderer.pageCount
                    Log.d(TAG, "PdfParser OCR: processing $total pages")

                    for (i in 0 until total) {
                        renderer.openPage(i).use { page ->
                            // Render at 2x scale for better OCR accuracy
                            val scale = 2
                            val bitmapWidth = page.width * scale
                            val bitmapHeight = page.height * scale
                            val bitmap = Bitmap.createBitmap(
                                bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val pageText = runOcrOnBitmap(bitmap)
                            bitmap.recycle()

                            if (pageText.isNotBlank()) {
                                pages.add(pageText)
                                Log.d(TAG, "PdfParser OCR: page ${i+1} — ${pageText.length} chars")
                            }
                        }
                    }
                }
            }

            pages.joinToString("\n\n")
        }

    /**
     * Runs ML Kit text recognition on a single bitmap.
     * Wraps the callback-based ML Kit API in a coroutine.
     */
    private suspend fun runOcrOnBitmap(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "PdfParser OCR: ML Kit failed on page", e)
                    cont.resume("") // don't crash, just skip page
                }
        }

    private fun estimatePageCount(uri: Uri): Int {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 1
            pfd.use { PdfRenderer(it).pageCount }
        } catch (e: Exception) {
            1
        }
    }
}
