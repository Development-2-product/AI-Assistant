package com.iamode.app.data.mail

import com.iamode.app.core.di.GmailClient
import com.iamode.app.data.gmail.GmailAuthManager
import com.iamode.app.domain.mail.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive as an opt-in document source (drive.readonly, requested only when the user taps
 * "Connect Google Drive"). Search uses Drive's own name and full-text search, so file contents are
 * matched without downloading them. A file is downloaded only after the user confirms it as an attachment
 * (or opens its preview). Google Docs/Sheets/Slides are exported as PDF.
 */
@Singleton
class DriveDocuments @Inject constructor(
    private val auth: GmailAuthManager,
    @GmailClient private val http: OkHttpClient,
    private val json: Json,
) {
    data class SearchResult(val files: List<DocumentFile>, val contentHits: Set<String>)

    @Serializable private data class DriveFile(
        val id: String, val name: String, val mimeType: String, val size: String? = null, val modifiedTime: String? = null,
    )
    @Serializable private data class FileList(val files: List<DriveFile> = emptyList())

    private val searchable = listOf(
        "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "image/jpeg", "image/png", "text/plain", GOOGLE_DOC, GOOGLE_SHEET, GOOGLE_SLIDES,
    )

    suspend fun search(account: String, keywords: List<String>): SearchResult = withContext(Dispatchers.IO) {
        val token = auth.driveToken(account) ?: return@withContext SearchResult(emptyList(), emptySet())
        val words = keywords.map { it.replace("\\", "").replace("'", "") }.filter { it.length >= 3 }.distinct().take(8)
        if (words.isEmpty()) return@withContext SearchResult(emptyList(), emptySet())
        val types = searchable.joinToString(" or ") { "mimeType = '$it'" }
        val byName = query(token, "trashed = false and ($types) and (" + words.joinToString(" or ") { "name contains '$it'" } + ")")
        val byText = query(token, "trashed = false and ($types) and (" + words.joinToString(" or ") { "fullText contains '$it'" } + ")")
        val all = (byName + byText).distinctBy { it.id }.map { it.toDocument(account) }
        SearchResult(all, byText.map { uri(account, it.id, it.mimeType) }.toSet())
    }

    /** Searches My Drive, "Shared with me" and shared drives; falls back to My Drive if shared drives aren't allowed. */
    private fun query(token: String, q: String): List<DriveFile> =
        query(token, q, allDrives = true) ?: query(token, q, allDrives = false).orEmpty()

    private fun query(token: String, q: String, allDrives: Boolean): List<DriveFile>? {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("corpora", if (allDrives) "allDrives" else "user")
            .addQueryParameter("includeItemsFromAllDrives", allDrives.toString())
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("pageSize", "25")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("fields", "files(id,name,mimeType,size,modifiedTime)")
            .build()
        return runCatching {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                json.decodeFromString(FileList.serializer(), r.body?.string().orEmpty()).files
            }
        }.getOrNull()
    }

    /** Downloads a confirmed file (or exports a Google Doc as PDF). Null if too big or not allowed. */
    suspend fun download(uri: String, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        val (account, id, native) = parse(uri) ?: return@withContext null
        val token = auth.driveToken(account) ?: return@withContext null
        val url = if (native) "https://www.googleapis.com/drive/v3/files/$id/export?mimeType=application/pdf&supportsAllDrives=true"
        else "https://www.googleapis.com/drive/v3/files/$id?alt=media&supportsAllDrives=true"
        runCatching {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val len = r.body?.contentLength() ?: -1
                if (len > maxBytes) return@use null
                r.body?.bytes()?.takeIf { it.size <= maxBytes }
            }
        }.getOrNull()
    }

    private fun DriveFile.toDocument(account: String): DocumentFile {
        val native = mimeType.startsWith("application/vnd.google-apps.")
        return DocumentFile(
            uri = uri(account, id, mimeType),
            name = if (native && !name.endsWith(".pdf", true)) "$name.pdf" else name,
            mimeType = if (native) "application/pdf" else mimeType,
            sizeBytes = size?.toLongOrNull(),
            modifiedAt = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            folder = "Google Drive",
        )
    }

    companion object {
        const val GOOGLE_DOC = "application/vnd.google-apps.document"
        const val GOOGLE_SHEET = "application/vnd.google-apps.spreadsheet"
        const val GOOGLE_SLIDES = "application/vnd.google-apps.presentation"
        const val SCHEME = "gdrive"

        fun sourceUri(account: String) = "$SCHEME://$account"
        fun uri(account: String, id: String, mime: String) =
            "$SCHEME://$account/$id" + if (mime.startsWith("application/vnd.google-apps.")) "?export=pdf" else ""

        /** (account, fileId, isGoogleNative) */
        fun parse(uri: String): Triple<String, String, Boolean>? {
            if (!uri.startsWith("$SCHEME://")) return null
            val rest = uri.removePrefix("$SCHEME://")
            val account = rest.substringBefore('/')
            val id = rest.substringAfter('/', "").substringBefore('?')
            if (account.isBlank() || id.isBlank()) return null
            return Triple(account, id, rest.contains("?export=pdf"))
        }
    }
}
