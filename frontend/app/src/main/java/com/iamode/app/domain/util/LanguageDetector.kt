package com.iamode.app.domain.util

import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Script

/**
 * Fast offline language guess. The backend's AI does the real detection for replies;
 * this is used for missed-call language selection and as an offline fallback.
 */
object LanguageDetector {
    private val romanWords = mapOf(
        LanguageCode.TE to setOf("em", "emi", "enti", "chesthunav", "chestunnav", "chesthunnav", "ra", "nenu", "nuvvu",
            "ekkada", "unnav", "unnava", "bagunnava", "cheppu", "tinnava", "ayyindi", "kadha", "ledu", "chala", "sare",
            "matladu", "bangaram", "chesta", "vastha", "enduku", "ippudu"),
        LanguageCode.HI to setOf("kya", "kar", "raha", "rahi", "hai", "hain", "kaise", "ho", "bhai", "nahi", "acha", "accha",
            "kab", "tak", "hoga", "mujhe", "tum", "aap", "yaar", "kaha", "kyun", "jaldi", "theek"),
        LanguageCode.TA to setOf("enna", "panra", "panre", "da", "di", "pannala", "saptiya", "epdi", "iruka", "irukka",
            "illa", "seri", "ungala", "unakku", "enaku", "kooda", "machan", "vaa", "panren"),
        LanguageCode.KN to setOf("yenu", "enu", "maadtha", "madta", "idiya", "hegidiya", "oota", "aytha", "aytu", "guru",
            "maga", "swalpa", "tumba", "beda", "banni", "bejaar", "aagilla", "aagide", "maadtini"),
    )

    fun detect(text: String): Language {
        for (ch in text) {
            when (ch.code) {
                in 0x0C00..0x0C7F -> return Language(LanguageCode.TE, Script.NATIVE)
                in 0x0B80..0x0BFF -> return Language(LanguageCode.TA, Script.NATIVE)
                in 0x0C80..0x0CFF -> return Language(LanguageCode.KN, Script.NATIVE)
                in 0x0900..0x097F -> return Language(LanguageCode.HI, Script.NATIVE)
            }
        }
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        val best = romanWords.maxByOrNull { (_, vocab) -> words.count { it in vocab } }
        val score = best?.let { (_, vocab) -> words.count { it in vocab } } ?: 0
        return if (score >= 1) Language(best!!.key, Script.ROMAN) else Language(LanguageCode.EN, Script.ROMAN)
    }
}
