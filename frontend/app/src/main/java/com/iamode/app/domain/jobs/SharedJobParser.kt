package com.iamode.app.domain.jobs

/** What a shared job link tells us. The user always confirms before anything is saved. */
data class SharedJob(val company: String?, val role: String?, val url: String?, val source: String?)

/**
 * Parses text shared from job apps (LinkedIn, Naukri, Indeed, Instahyre…) via Android's share sheet.
 * Formats vary, so this is best-effort; everything stays editable.
 */
object SharedJobParser {
    private val urlRe = Regex("""https?://[^\s<>"']+""")
    private val patterns = listOf(
        // "Check out this job at ABC Technologies: Android Developer"
        Regex("""job at (.+?):\s*(.+)""", RegexOption.IGNORE_CASE) to (1 to 2),
        // "Android Developer at ABC Technologies" / "Android Developer - ABC Technologies"
        Regex("""^(.+?)\s+(?:at|@|-|–|\|)\s+(.+?)$""", RegexOption.IGNORE_CASE) to (2 to 1),
        // "ABC Technologies is hiring a Android Developer"
        Regex("""^(.+?) is hiring (?:an? )?(.+)$""", RegexOption.IGNORE_CASE) to (1 to 2),
    )

    fun parse(text: String): SharedJob {
        val url = urlRe.find(text)?.value?.trimEnd('.', ',', ')')
        val source = url?.let { sourceOf(it) }
        val firstLine = text.replace(urlRe, " ").lines().map { it.trim() }.firstOrNull { it.length in 3..160 }
        var company: String? = null
        var role: String? = null
        if (firstLine != null) {
            for ((re, groups) in patterns) {
                val m = re.find(firstLine) ?: continue
                company = clean(m.groupValues[groups.first])
                role = clean(m.groupValues[groups.second])
                break
            }
        }
        return SharedJob(company, role, url, source)
    }

    fun sourceOf(url: String): String? {
        val host = url.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.")
        return listOf("linkedin", "naukri", "indeed", "instahyre", "foundit", "internshala", "wellfound", "glassdoor", "hirist",
            "cutshort", "iimjobs", "unstop").firstOrNull { host.contains(it) }
    }

    private fun clean(s: String) = s.trim().trim('"', '\'', ':', '-', '|').replace(Regex("\\s+"), " ")
        .removeSuffix(" job").ifBlank { null }?.take(120)
}
