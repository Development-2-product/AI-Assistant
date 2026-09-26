package com.iamode.app.data.mail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.iamode.app.domain.mail.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what documents say, on the phone, so badly named files are still found:
 *  - PDFs with a text layer: first 2 pages (pdfbox-android)
 *  - scanned PDFs and photos (JPG/PNG): on-device OCR (ML Kit, model from Play services)
 *  - Word .docx and .txt/.md: read directly
 * Password-protected PDFs are reported as locked (IA Mode never asks for the password).
 * Text stays in memory, is bounded in size and count, and is never uploaded.
 */
@Singleton
class DocumentTextExtractor @Inject constructor(@ApplicationContext private val context: Context) {

    data class Extraction(val text: Map<String, String>, val locked: Set<String>)

    private val cache = LruCache<String, String>(150)
    private val lockedCache = LruCache<String, Boolean>(150)
    @Volatile private var pdfReady = false
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    /** Reads Devanagari (Hindi, Marathi) and Latin. Telugu/Tamil aren't supported by on-device ML Kit. */
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    suspend fun extract(files: List<DocumentFile>, maxFiles: Int = 40, maxOcr: Int = 10): Extraction = withContext(Dispatchers.IO) {
        val targets = files.filter { ext(it.name) in READABLE && (it.sizeBytes ?: 0L) < 25L * 1024 * 1024 && !it.uri.startsWith("gdrive:") }
            .sortedByDescending { it.modifiedAt ?: 0L }
            .take(maxFiles)
        val text = mutableMapOf<String, String>()
        val locked = mutableSetOf<String>()
        var ocrBudget = maxOcr
        for (f in targets) {
            val key = "${f.uri}|${f.modifiedAt}|${f.sizeBytes}"
            if (lockedCache.get(key) == true) { locked += f.uri; continue }
            val cached = cache.get(key)
            if (cached != null) {
                text[f.uri] = cached
                continue
            }
            val direct = try {
                runInterruptible { readDirect(f) }
            } catch (e: InvalidPasswordException) {
                lockedCache.put(key, true); locked += f.uri; continue
            } catch (e: Exception) {
                null
            }
            var result = direct
            // No text layer (scan) or a photo: OCR, within a budget so ranking stays fast.
            if ((result == null || result.length < MIN_TEXT) && ext(f.name) in OCR_TYPES && ocrBudget > 0) {
                ocrBudget--
                result = withTimeoutOrNull(4_000) { runCatching { ocr(f) }.getOrNull() } ?: result
            }
            if (!result.isNullOrBlank()) { cache.put(key, result); text[f.uri] = result }
        }
        Extraction(text, locked)
    }

    private fun readDirect(f: DocumentFile): String? = when (ext(f.name)) {
        "pdf" -> open(f.uri)?.use { pdf(it) }
        "docx" -> open(f.uri)?.use { docx(it) }
        "txt", "md" -> open(f.uri)?.use { it.bufferedReader().readText().take(MAX_CHARS) }
        else -> null
    }

    private suspend fun ocr(f: DocumentFile): String? {
        val pages: List<Bitmap> = if (ext(f.name) == "pdf") renderPdf(f.uri, pages = 2) else listOfNotNull(decodeImage(f.uri))
        val out = StringBuilder()
        try {
            for (bmp in pages) {
                val image = InputImage.fromBitmap(bmp, 0)
                var text = recognizer.process(image).await().text
                // Little Latin text often means a Hindi/Marathi document: try the Devanagari model.
                if (text.count { it.isLetter() } < MIN_TEXT) {
                    val hi = runCatching { devanagari.process(image).await().text }.getOrNull().orEmpty()
                    if (hi.count { it.isLetter() } > text.count { it.isLetter() }) text = hi
                }
                out.append(text).append('\n')
                if (out.length > MAX_CHARS) break
            }
        } finally {
            pages.forEach { it.recycle() }
        }
        return out.toString().take(MAX_CHARS).ifBlank { null }
    }

    private fun renderPdf(uri: String, pages: Int): List<Bitmap> {
        val fd = descriptor(uri) ?: return emptyList()
        return fd.use {
            runCatching {
                PdfRenderer(it).use { pdf ->
                    (0 until minOf(pages, pdf.pageCount)).map { i ->
                        pdf.openPage(i).use { page ->
                            val w = 1400
                            val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 2400)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                                b.eraseColor(Color.WHITE)
                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun decodeImage(uri: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        return open(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun descriptor(uri: String): ParcelFileDescriptor? {
        val u = Uri.parse(uri)
        return when (u.scheme) {
            "file" -> ownFile(u)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
            "content" -> runCatching { context.contentResolver.openFileDescriptor(u, "r") }.getOrNull()
            else -> null
        }
    }

    private fun open(uri: String): InputStream? {
        val u = Uri.parse(uri)
        return when (u.scheme) {
            "file" -> ownFile(u)?.inputStream()
            "content" -> runCatching { context.contentResolver.openInputStream(u) }.getOrNull()
            else -> null // Drive files are matched by Drive's own full-text search instead
        }
    }

    private fun ownFile(u: Uri): File? =
        File(requireNotNull(u.path)).takeIf { it.canonicalPath.startsWith(File(context.filesDir, "documents").canonicalPath) }

    /** Throws InvalidPasswordException for PDFs that need a password to open. Owner-password-only PDFs are read. */
    private fun pdf(input: InputStream): String? {
        if (!pdfReady) { PDFBoxResourceLoader.init(context); pdfReady = true }
        return PDDocument.load(input).use { doc ->
            PDFTextStripper().apply { startPage = 1; endPage = minOf(2, doc.numberOfPages) }.getText(doc).trim().take(MAX_CHARS)
        }
    }

    /** word/document.xml inside the .docx zip, tags stripped. */
    private fun docx(input: InputStream): String? = ZipInputStream(input).use { zip ->
        generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" } ?: return null
        val xml = zip.readBytes().toString(Charsets.UTF_8).take(200_000)
        xml.replace(Regex("</w:p>"), "\n").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").take(MAX_CHARS)
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private companion object {
        const val MAX_CHARS = 6_000
        const val MIN_TEXT = 30
        val READABLE = setOf("pdf", "docx", "txt", "md", "jpg", "jpeg", "png")
        val OCR_TYPES = setOf("pdf", "jpg", "jpeg", "png")
    }
}
