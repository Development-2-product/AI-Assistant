package com.iamode.app.domain.jobs

import kotlin.math.max
import kotlin.math.min

sealed interface MatchResult {
    data class Existing(val applicationId: String, val reason: String) : MatchResult
    /** Several applications fit (e.g. two roles at one company); the user picks. */
    data class Ambiguous(val candidateIds: List<String>) : MatchResult
    data object New : MatchResult
    /** Not enough information to track (no company and no role). */
    data object Untrackable : MatchResult
}

/**
 * Links a career email to the application it belongs to, in order of certainty:
 * same thread → same reference ID → company (from the email or the sender's domain) + role.
 * Emails from job platforms (Workday, Greenhouse, Naukri…) don't reveal the company by domain,
 * so they rely on the company/role the AI extracted.
 */
object ApplicationMatcher {
    private const val COMPANY_MATCH = 0.92
    private const val ROLE_MATCH = 0.86

    /** Senders that are platforms, not employers. */
    val platformDomains = setOf(
        "greenhouse.io", "greenhouse-mail.io", "lever.co", "hire.lever.co", "myworkday.com", "myworkdayjobs.com", "workday.com",
        "icims.com", "smartrecruiters.com", "ashbyhq.com", "jobvite.com", "taleo.net", "successfactors.com", "oraclecloud.com",
        "naukri.com", "naukrigulf.com", "linkedin.com", "indeed.com", "instahyre.com", "foundit.in", "monsterindia.com", "shine.com",
        "internshala.com", "wellfound.com", "angel.co", "hirist.tech", "iimjobs.com", "cutshort.io", "unstop.com", "hackerrank.com",
        "hackerearth.com", "codility.com", "mettl.com", "gmail.com", "outlook.com", "yahoo.com", "hotmail.com",
    )

    private val companySuffixes = setOf(
        "pvt", "private", "ltd", "limited", "inc", "llc", "llp", "corp", "corporation", "technologies", "technology", "tech",
        "solutions", "services", "software", "systems", "labs", "india", "global", "co", "company", "the", "group", "careers",
        "jobs", "hr", "recruitment", "talent", "consulting", "consultancy", "infotech", "digital",
    )

    fun normalizeCompany(name: String?): String {
        val words = name.orEmpty().lowercase().replace("&", " and ").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val core = words.filter { it !in companySuffixes }
        return (core.ifEmpty { words }).joinToString(" ")
    }

    fun normalizeRole(role: String?): String = role.orEmpty().lowercase()
        .replace(Regex("\\b(sr|snr)\\b\\.?"), "senior").replace(Regex("\\b(jr)\\b\\.?"), "junior")
        .replace(Regex("\\b(engg|engr)\\b\\.?"), "engineer").replace(Regex("\\bdev\\b"), "developer")
        .split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.joinToString(" ")

    fun domainOf(address: String): String? = address.substringAfterLast('@', "").lowercase().trim(' ', '>').ifBlank { null }

    private fun registrable(domain: String): String {
        val p = domain.split('.')
        return if (p.size >= 3 && p[p.size - 2] in setOf("co", "com", "org", "net", "ac") && p.last().length == 2) p.takeLast(3).joinToString(".")
        else p.takeLast(2).joinToString(".")
    }

    fun isPlatform(domain: String?): Boolean = domain != null && registrable(domain).let { r -> platformDomains.any { r == it || domain.endsWith(".$it") } }

    /** "careers.abc-tech.com" → "abc" (comparable with normalizeCompany("ABC Technologies")). */
    fun companyKeyFromDomain(address: String): String? {
        val d = domainOf(address) ?: return null
        if (isPlatform(d)) return null
        return normalizeCompany(registrable(d).substringBefore('.').replace('-', ' ')).ifBlank { null }
    }

    fun match(e: ApplicationEvidence, apps: List<TrackedApplication>): MatchResult {
        apps.firstOrNull { e.threadId in it.threadIds }?.let { return MatchResult.Existing(it.id, "same email thread") }

        val companyKey = normalizeCompany(e.company).ifBlank { null } ?: companyKeyFromDomain(e.fromAddress)
        e.referenceId?.trim()?.lowercase()?.takeIf { it.length >= 3 }?.let { ref ->
            apps.firstOrNull { a ->
                a.referenceId?.trim()?.lowercase() == ref && (companyKey == null || similarity(companyKey, normalizeCompany(a.company)) >= COMPANY_MATCH)
            }?.let { return MatchResult.Existing(it.id, "same reference ID") }
        }

        if (companyKey == null) return if (e.role.isNullOrBlank()) MatchResult.Untrackable else MatchResult.New
        val sameCompany = apps.filter { a ->
            similarity(companyKey, normalizeCompany(a.company)) >= COMPANY_MATCH ||
                (domainOf(e.fromAddress)?.let { d -> !isPlatform(d) && d in a.senderDomains } == true)
        }
        if (sameCompany.isEmpty()) return MatchResult.New

        val role = normalizeRole(e.role)
        if (role.isNotBlank()) {
            val sameRole = sameCompany.filter { a -> a.role.isNullOrBlank() || similarity(role, normalizeRole(a.role)) >= ROLE_MATCH }
            return when {
                sameRole.size == 1 -> MatchResult.Existing(sameRole.single().id, "same company and role")
                sameRole.size > 1 -> sameRole.filter { !it.role.isNullOrBlank() }.let { exact ->
                    if (exact.size == 1) MatchResult.Existing(exact.single().id, "same company and role") else MatchResult.Ambiguous(sameRole.map { it.id })
                }
                else -> MatchResult.New // a different role at the same company is a separate application
            }
        }
        val active = sameCompany.filter { it.stage.active || it.stage == ApplicationStage.GHOSTED }
        return when {
            sameCompany.size == 1 -> MatchResult.Existing(sameCompany.single().id, "same company")
            active.size == 1 -> MatchResult.Existing(active.single().id, "only active application at this company")
            else -> MatchResult.Ambiguous(sameCompany.map { it.id })
        }
    }

    /** Jaro-Winkler similarity in [0, 1]. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val aM = BooleanArray(a.length); val bM = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - window)..min(b.length - 1, i + window)) {
                if (!bM[j] && a[i] == b[j]) { aM[i] = true; bM[j] = true; matches++; break }
            }
        }
        if (matches == 0) return 0.0
        var t = 0; var k = 0
        for (i in a.indices) if (aM[i]) { while (!bM[k]) k++; if (a[i] != b[k]) t++; k++ }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}
