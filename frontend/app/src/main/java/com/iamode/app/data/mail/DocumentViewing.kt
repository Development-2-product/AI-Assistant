package com.iamode.app.data.mail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What the viewer can show for a file. PDFs stay open (lazily rendered pages) until the viewer closes. */
sealed interface Viewable {
    class Pdf(val doc: PdfHandle) : Viewable
    class Image(val bitmap: Bitmap) : Viewable
    class Text(val text: String) : Viewable
    data object Locked : Viewable
    data object Unsupported : Viewable
    data class Failed(val reason: String) : Viewable
}

/** PdfRenderer isn't thread-safe: one page at a time, on IO, behind a mutex. */
class PdfHandle(private val fd: ParcelFileDescriptor, private val temp: File?) {
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount
    val firstAspect: Float = renderer.openPage(0).use { it.height.toFloat() / it.width }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO.limitedParallelism(1)) {
        lock.withLock {
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(200, 2200)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        temp?.delete() // Drive downloads are deleted as soon as the viewer closes
    }
}

@Singleton
class DocumentViewing @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drive: DriveDocuments,
) {
    suspend fun open(uriString: String, name: String): Viewable = withContext(Dispatchers.IO) {
        val ext = name.substringAfterLast('.', "").lowercase()
        try {
            val (fd, temp) = descriptor(uriString) ?: return@withContext Viewable.Failed("IA Mode can't open this file")
            when {
                ext == "pdf" || uriString.contains("export=pdf") -> try {
                    Viewable.Pdf(PdfHandle(fd, temp))
                } catch (e: SecurityException) {
                    fd.close(); temp?.delete(); Viewable.Locked // password-protected
                }
                ext in setOf("jpg", "jpeg", "png", "webp") -> fd.use {
                    val bmp = BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    temp?.delete()
                    bmp?.let(Viewable::Image) ?: Viewable.Failed("This image couldn't be decoded")
                }
                ext in setOf("txt", "md") -> fd.use {
                    val t = ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().take(40_000)
                    temp?.delete(); Viewable.Text(t)
                }
                ext == "docx" -> fd.use {
                    val t = ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(it)).use { zip ->
                        generateSequence { zip.nextEntry }.firstOrNull { e -> e.name == "word/document.xml" }
                            ?.let { zip.readBytes().toString(Charsets.UTF_8) }
                    }?.replace("</w:p>", "\n\n")?.replace(Regex("<[^>]+>"), "")?.replace(Regex("[ \\t]+"), " ")?.trim()?.take(40_000)
                    temp?.delete()
                    t?.let(Viewable::Text) ?: Viewable.Unsupported
                }
                else -> { fd.close(); temp?.delete(); Viewable.Unsupported }
            }
        } catch (e: Exception) {
            Viewable.Failed("This file couldn't be opened")
        }
    }

    /** Only authorized sources: picker/folder grants, IA Mode's own folder, connected Drive accounts. */
    private suspend fun descriptor(uriString: String): Pair<ParcelFileDescriptor, File?>? {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            DriveDocuments.SCHEME -> {
                val bytes = drive.download(uriString, 18L * 1024 * 1024) ?: return null
                val tmp = File.createTempFile("view", ".bin", context.cacheDir).apply { writeBytes(bytes) }
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY) to tmp
            }
            "file" -> {
                val f = File(requireNotNull(uri.path)).canonicalFile
                if (!f.path.startsWith(File(context.filesDir, "documents").canonicalPath)) return null
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) to null
            }
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.let { it to null }
            else -> null
        }
    }
}
