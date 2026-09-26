package com.iamode.app.domain.mail

enum class Formality { CASUAL, NEUTRAL, FORMAL }

/**
 * How the user writes email, learned from their own sent mail on the phone. Only this compact profile
 * (plus up to 3 short, masked excerpts) is ever sent to the AI, and only while drafting a reply.
 */
data class WritingStyle(
    val greeting: String?,
    val signOff: String?,
    val averageWords: Int,
    val formality: Formality,
    val usesEmoji: Boolean,
    val samples: List<String>,
    val sampleCount: Int,
)

object WritingStyleAnalyzer {
    // English plus common Indian greetings (native script and romanized)
    private val greetingRe = Regex(
        """^(hi|hello|hey|dear|greetings|good (morning|afternoon|evening)|namaste|namaskar(am)?|vanakkam|hii+|""" +
            """नमस्ते|नमस्कार|प्रिय|आदरणीय|నమస్కారం|నమస్తే|ప్రియమైన|வணக்கம்|ನಮಸ್ಕಾರ|നമസ്കാരം)(?=[\s,!.]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val signOffRe = Regex(
        """^(regards|best regards|kind regards|warm regards|thanks( (and|&) regards)?|thank you|many thanks|best|cheers|sincerely|""" +
            """yours (sincerely|truly|faithfully)|thanks!?|dhanyavad(amulu)?|dhanyavaad|""" +
            """धन्यवाद|सादर|भवदीय|ధన్యవాదాలు|ధన్యవాదములు|కృతజ్ఞతలు|இப்படிக்கு|நன்றி|ಧನ್ಯವಾದಗಳು|നന്ദി)(?=[\s,!.]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val quoteStart = Regex("""^(on .{5,120} wrote:|-----\s*original message|from: .+@.+|sent from my )""", RegexOption.IGNORE_CASE)
    private val emoji = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")
    private val casualMarkers = Regex("""\b(i'm|don't|can't|won't|it's|gonna|wanna|yeah|ya|lol|btw|thx|pls|ok|okay)\b|!{2,}""", RegexOption.IGNORE_CASE)
    private val formalMarkers = Regex("""\b(dear|kindly|please find|i am writing|sincerely|herewith|regards|respected|further to|as per)\b""", RegexOption.IGNORE_CASE)

    /** Null until there's enough of the user's own writing (at least 3 real emails). */
    fun analyze(sentBodies: List<String>): WritingStyle? {
        val emails = sentBodies.mapNotNull { clean(it) }.filter { it.lines.isNotEmpty() }
        if (emails.size < 3) return null

        val greetings = emails.mapNotNull { e -> greetingRe.find(e.lines.first())?.value?.lowercase()?.replaceFirstChar { it.uppercase() } }
        val greeting = mostCommon(greetings, emails.size)

        val signOffs = emails.mapNotNull { it.signOff }
        val signOff = mostCommon(signOffs, emails.size)

        val words = emails.map { e -> e.core.split(Regex("\\s+")).count { it.isNotBlank() } }
        val avg = words.sorted()[words.size / 2] // median resists one very long email

        val text = emails.joinToString("\n") { it.core }
        val casual = casualMarkers.findAll(text).count()
        val formal = formalMarkers.findAll(emails.joinToString("\n") { it.lines.joinToString("\n") }).count()
        val formality = when {
            formal >= casual * 2 && formal >= emails.size / 2 -> Formality.FORMAL
            casual >= formal * 2 && casual >= emails.size / 2 -> Formality.CASUAL
            else -> Formality.NEUTRAL
        }
        val usesEmoji = emails.count { emoji.containsMatchIn(it.core) } * 5 >= emails.size

        val samples = emails.map { it.core }
            .filter { it.split(Regex("\\s+")).size in 12..140 }
            .distinct()
            .take(3)
            .map { mask(it).take(350) }

        return WritingStyle(greeting, signOff, avg.coerceIn(10, 400), formality, usesEmoji, samples, emails.size)
    }

    private data class Cleaned(val lines: List<String>, val core: String, val signOff: String?)

    private fun clean(body: String): Cleaned? {
        val lines = mutableListOf<String>()
        for (raw in body.replace("\r", "").lines()) {
            val l = raw.trim()
            if (l.startsWith(">") || quoteStart.containsMatchIn(l)) break
            if (l == "--" || l == "-- ") break // signature block
            lines += l
        }
        val kept = lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (kept.isEmpty()) return null
        // sign-off = the closing line (+ the name on the next line, if short)
        val idx = kept.indexOfLast { signOffRe.containsMatchIn(it) && it.length <= 40 }
        val signOff = if (idx >= 0 && idx >= kept.size - 3) {
            kept.subList(idx, minOf(kept.size, idx + 2)).filter { it.length <= 40 }.joinToString("\n")
        } else null
        val end = if (idx >= 0 && idx >= kept.size - 3) idx else kept.size
        val start = if (greetingRe.containsMatchIn(kept.first()) && kept.first().length <= 60) 1 else 0
        val core = kept.subList(minOf(start, end), end).filter { it.isNotBlank() }.joinToString(" ").trim()
        return Cleaned(kept, core, signOff)
    }

    private fun mostCommon(values: List<String>, total: Int): String? {
        val (value, n) = values.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return if (n * 10 >= total * 3) value else null // used in at least 30% of emails
    }

    /** Excerpts never carry contact details, links or numbers. */
    fun mask(text: String): String = text
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[email]")
        .replace(Regex("""https?://\S+|www\.\S+"""), "[link]")
        .replace(Regex("""\+?\d[\d\s-]{3,}\d"""), "[number]")
}

/** What a sender usually sends, from mail IA Mode has already understood. */
data class SenderHistory(val total: Int, val promotions: Int, val notifications: Int) {
    val mostlyBulk: Boolean get() = total >= 3 && (promotions + notifications) * 4 >= total * 3

    companion object {
        fun from(categoryCounts: Map<String, Int>) = SenderHistory(
            total = categoryCounts.values.sum(),
            promotions = categoryCounts["promotion"] ?: 0,
            notifications = (categoryCounts["notification"] ?: 0) + (categoryCounts["no_reply"] ?: 0),
        )
    }
}
