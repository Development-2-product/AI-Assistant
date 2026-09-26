package com.iamode.app.data.mail

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.entity.DocumentSourceEntity
import com.iamode.app.domain.mail.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only places IA Mode looks for documents:
 *  1. folders the user explicitly granted (Storage Access Framework, revocable at any time),
 *  2. IA Mode's own private "documents" folder (copies the user chose to keep),
 *  3. any single file the user picks in the system file picker.
 * There is no broad storage permission, so private files elsewhere are never visible to the app.
 */
@Singleton
class DocumentSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MailWorkflowDao,
    private val drive: DriveDocuments,
) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val ownFolder: File get() = File(context.filesDir, "documents").apply { mkdirs() }

    val sources = dao.observeSources()

    /** Called with the tree URI from ACTION_OPEN_DOCUMENT_TREE. Keeps access across reboots. */
    suspend fun addFolder(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').ifBlank { "Folder" }
        dao.upsertSource(DocumentSourceEntity(treeUri.toString(), name, System.currentTimeMillis()))
    }

    suspend fun removeFolder(uri: String) {
        if (uri.startsWith("${DriveDocuments.SCHEME}://")) { dao.deleteSource(uri); return }
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        dao.deleteSource(uri)
    }

    /** Lists candidate files (metadata only) from every authorized source. Bounded so it stays fast. */
    suspend fun listFiles(maxFiles: Int = 1500, maxDepth: Int = 4): List<DocumentFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DocumentFile>()
        ownFolder.listFiles()?.forEach { f ->
            out += DocumentFile(Uri.fromFile(f).toString(), f.name, mimeFor(f.name), f.length(), f.lastModified(), "IA Mode")
        }
        val granted = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        for (source in dao.sources()) {
            if (source.uri.startsWith("${DriveDocuments.SCHEME}://")) continue // searched per request, see searchDrive
            if (source.uri !in granted) continue // access was revoked in system settings
            val tree = Uri.parse(source.uri)
            walk(tree, DocumentsContract.getTreeDocumentId(tree), source.displayName, 0, maxDepth, out, maxFiles)
            if (out.size >= maxFiles) break
        }
        out
    }

    /** Connected Drive accounts, searched with the request's keywords (names + Drive full-text). */
    suspend fun searchDrive(keywords: List<String>): DriveDocuments.SearchResult {
        val accounts = dao.sources().filter { it.uri.startsWith("${DriveDocuments.SCHEME}://") }.map { it.uri.removePrefix("${DriveDocuments.SCHEME}://") }
        val results = accounts.map { drive.search(it, keywords) }
        return DriveDocuments.SearchResult(results.flatMap { it.files }, results.flatMap { it.contentHits }.toSet())
    }

    suspend fun addDrive(account: String) =
        dao.upsertSource(DocumentSourceEntity(DriveDocuments.sourceUri(account), "Google Drive · $account", System.currentTimeMillis()))

    private fun walk(tree: Uri, docId: String, folder: String, depth: Int, maxDepth: Int, out: MutableList<DocumentFile>, max: Int) {
        if (depth > maxDepth || out.size >= max) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        runCatching {
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(tree, id, name, depth + 1, maxDepth, out, max)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out += DocumentFile(uri.toString(), name, mime, if (c.isNull(3)) null else c.getLong(3),
                            if (c.isNull(4)) null else c.getLong(4), folder)
                    }
                }
            }
        }
    }

    /** Metadata for a file picked in the system picker (ACTION_OPEN_DOCUMENT grants access to that file only). */
    suspend fun describe(uri: Uri): DocumentFile? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val f = File(requireNotNull(uri.path))
            if (!f.canonicalPath.startsWith(ownFolder.canonicalPath)) return@withContext null // only our own folder
            return@withContext DocumentFile(uri.toString(), f.name, mimeFor(f.name), f.length(), f.lastModified(), "IA Mode")
        }
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0) ?: "document"
            DocumentFile(uri.toString(), name, resolver.getType(uri) ?: mimeFor(name), if (c.isNull(1)) null else c.getLong(1), null, null)
        }
    }

    /** Keeps a copy in IA Mode's private documents folder so it can be suggested next time. */
    suspend fun keepCopy(uri: Uri, name: String): DocumentFile? = withContext(Dispatchers.IO) {
        val target = File(ownFolder, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        runCatching {
            if (uri.scheme == DriveDocuments.SCHEME) {
                target.writeBytes(requireNotNull(drive.download(uri.toString(), 18L * 1024 * 1024)))
            } else resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            DocumentFile(Uri.fromFile(target).toString(), target.name, mimeFor(target.name), target.length(), target.lastModified(), "IA Mode")
        }.getOrNull()
    }

    /**
     * Reads the bytes of a file the user confirmed. Only URIs from an authorized source, a picker grant,
     * or IA Mode's own folder can be opened; anything else is refused.
     */
    suspend fun readConfirmed(uriString: String, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            DriveDocuments.SCHEME -> drive.download(uriString, maxBytes) // only Drive accounts the user connected
            "file" -> {
                val f = File(requireNotNull(uri.path)).canonicalFile
                if (!f.path.startsWith(ownFolder.canonicalPath) || f.length() > maxBytes) return@withContext null
                f.readBytes()
            }
            "content" -> runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > maxBytes) null else bytes
                }
            }.getOrNull()
            else -> null
        }
    }

    /** First page of a PDF, or the image itself, for the preview. Null for other types. */
    suspend fun preview(uriString: String, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.startsWith("${DriveDocuments.SCHEME}://")) return@withContext drivePreview(uriString, widthPx)
        val uri = Uri.parse(uriString)
        val mime = if (uri.scheme == "file") mimeFor(uri.path.orEmpty()) else resolver.getType(uri)
        runCatching {
            when {
                mime == "application/pdf" -> resolver.openFileDescriptor(uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        pdf.openPage(0).use { page ->
                            val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
                mime?.startsWith("image/") == true -> resolver.openInputStream(uri)?.use {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
                else -> null
            }
        }.getOrNull()
    }

    /** Drive files are downloaded to a private cache file just for the preview, then deleted. */
    private suspend fun drivePreview(uri: String, widthPx: Int): Bitmap? {
        val bytes = drive.download(uri, 10L * 1024 * 1024) ?: return null
        val tmp = File.createTempFile("preview", ".bin", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            if (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") {
                android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        pdf.openPage(0).use { page ->
                            val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            } else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "txt" -> "text/plain"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }
}
