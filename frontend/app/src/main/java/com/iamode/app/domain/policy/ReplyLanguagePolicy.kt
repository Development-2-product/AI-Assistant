package com.iamode.app.domain.policy

import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.util.LanguageDetector

/**
 * A small deterministic guard around the model: the latest inbound message chooses
 * the reply language and script. A doubtful draft is shown for approval, never sent
 * automatically just because it sounds plausible in a different language.
 */
object ReplyLanguagePolicy {
    fun targetForLatestInbound(text: String): Language = LanguageDetector.detect(text)

    fun matches(reply: String, target: Language): Boolean {
        if (reply.isBlank()) return false
        val hasIndianScript = reply.any { it.code in 0x0900..0x0D7F }
        if (target.script == Script.NATIVE) return hasScriptFor(reply, target.code)
        if (hasIndianScript) return false
        // English must stay English. Roman Indian-language replies need at least one
        // recognizable language cue; otherwise we fail safe to approval.
        val detected = LanguageDetector.detect(reply)
        return target.code == LanguageCode.EN || detected.code == target.code
    }

    private fun hasScriptFor(text: String, language: LanguageCode): Boolean = text.any { ch ->
        when (language) {
            LanguageCode.TE -> ch.code in 0x0C00..0x0C7F
            LanguageCode.HI -> ch.code in 0x0900..0x097F
            LanguageCode.TA -> ch.code in 0x0B80..0x0BFF
            LanguageCode.KN -> ch.code in 0x0C80..0x0CFF
            LanguageCode.EN -> false
        }
    }
}
