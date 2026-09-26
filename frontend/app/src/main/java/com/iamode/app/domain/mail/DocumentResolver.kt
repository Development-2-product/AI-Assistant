package com.iamode.app.domain.mail

/** A file from a source the user authorized. The resolver never sees file contents. */
data class DocumentFile(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAt: Long?,
    val folder: String?,
)

enum class MatchLabel(val label: String) { RECOMMENDED("Recommended"), POSSIBLE("Possible match") }

data class RankedDocument(
    val file: DocumentFile,
    val score: Double,
    val label: MatchLabel,
    val formatMatches: Boolean,
    /** True when the file's text (not just its name) matches the request. */
    val contentMatch: Boolean = false,
)

/**
 * Ranks authorized files for a document request, on the phone. It uses the filename, folder, type, format,
 * the file's text (extracted on the phone, or Drive's own full-text search), how recent the file is, and which
 * file the user chose for the same kind of request before.
 * Only one clear winner is marked Recommended; everything else is a Possible match for the user to choose.
 */
object DocumentResolver {
    private const val RECOMMEND_SCORE = 6.0
    private const val RECOMMEND_MARGIN = 1.5
    private val stopwords = setOf(
        "the", "and", "your", "you", "our", "a", "an", "of", "in", "on", "for", "to", "as", "with", "please", "send",
        "share", "copy", "current", "latest", "file", "document", "documents", "format", "attached", "attach", "me", "us", "my",
    )
    private val documentExt = setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "jpg", "jpeg", "png", "heic", "ppt", "pptx", "xls", "xlsx")

    fun tokens(text: String): Set<String> =
        text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in stopwords }
            .map { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }
            .toSet()

    fun rank(
        request: DocumentRequest,
        files: List<DocumentFile>,
        now: Long,
        previousSelections: Map<String, Int> = emptyMap(),
        limit: Int = 5,
        /** uri -> first pages of text, extracted on the phone. */
        contentText: Map<String, String> = emptyMap(),
        /** uris a provider's own full-text search matched (e.g. Google Drive). */
        contentHits: Set<String> = emptySet(),
    ): List<RankedDocument> {
        val typeWords = request.type.keywords.flatMap { tokens(it) }.toSet()
        val askWords = tokens(request.description.orEmpty()) - typeWords
        val scored = files.mapNotNull { f ->
            val ext = AttachmentSafety.extension(f.name)
            if (ext !in documentExt) return@mapNotNull null
            if (AttachmentSafety.check(listOf(AttachmentInfo(f.name, f.mimeType, f.sizeBytes, true))) != null) return@mapNotNull null
            val nameWords = tokens(f.name.substringBeforeLast('.')) + tokens(f.folder.orEmpty()).map { "dir:$it" }
            val plain = nameWords.map { it.removePrefix("dir:") }.toSet()
            val typeHits = plain.count { it in typeWords }
            val askHits = plain.count { it in askWords }
            val chosenBefore = previousSelections[f.uri] ?: 0
            val text = contentText[f.uri]?.let { tokens(it.take(20_000)) }.orEmpty()
            val contentType = text.count { it in typeWords }
            val contentAsk = text.count { it in askWords }
            val serverHit = f.uri in contentHits
            val contentScore = minOf(contentType * 1.5, 6.0) + minOf(contentAsk * 1.0, 4.0) + (if (serverHit) 3.0 else 0.0)
            if (typeHits + askHits == 0 && chosenBefore == 0 && contentScore == 0.0) return@mapNotNull null // unrelated file

            var score = typeHits * 3.0 + askHits * 2.0 + minOf(chosenBefore, 2) * 4.0 + contentScore
            val formatOk = AttachmentSafety.matchesFormat(request.requestedFormat, f.name, f.mimeType)
            score += if (request.requestedFormat != null) (if (formatOk) 3.0 else -2.0) else 0.0
            val ageDays = f.modifiedAt?.let { (now - it) / 86_400_000.0 }
            score += when {
                ageDays == null -> 0.0
                ageDays <= 30 -> 1.5
                ageDays <= 180 -> 0.5
                ageDays > 730 -> -1.0
                else -> 0.0
            }
            if (nameWords.any { it.startsWith("dir:") && it.removePrefix("dir:") in typeWords }) score += 0.5
            RankedDocument(f, score, MatchLabel.POSSIBLE, formatOk, contentMatch = contentScore > 0.0)
        }.sortedWith(compareByDescending<RankedDocument> { it.score }.thenByDescending { it.file.modifiedAt ?: 0L })
            .take(limit)

        if (scored.isEmpty()) return scored
        val top = scored.first()
        val clearWinner = top.score >= RECOMMEND_SCORE && top.formatMatches &&
            (scored.size == 1 || top.score - scored[1].score >= RECOMMEND_MARGIN)
        return if (clearWinner) listOf(top.copy(label = MatchLabel.RECOMMENDED)) + scored.drop(1) else scored
    }
}
