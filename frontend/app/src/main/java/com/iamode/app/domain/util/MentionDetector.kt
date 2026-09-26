package com.iamode.app.domain.util

object MentionDetector {
    /** True if [text] mentions any of [names] as a whole word ("@Kasi", "Kasi,", "kasi?"), ignoring case. */
    fun isMentioned(text: String, names: List<String>): Boolean = names.any { name ->
        val n = name.trim()
        n.length >= 2 && Regex("(^|[^\\p{L}\\p{N}])@?${Regex.escape(n)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }
}
