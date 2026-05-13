package pt.cuco.scanner

object CucoOcrParser {

    data class CucoFields(
        val serial: String,
        val certifiedTime: String,
        val usageCounter: String,
    ) {
        fun usageCounterTrimmed(): String =
            usageCounter.trimStart('0').ifEmpty { "0" }
    }

    private val serialLabel = Regex("""machine\s*serial\s*number""", RegexOption.IGNORE_CASE)
    private val certifiedLabel = Regex("""certified\s*time""", RegexOption.IGNORE_CASE)
    private val usageLabel = Regex("""usage\s*(counter|time)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): CucoFields? {
        val serial = extractValue(text, serialLabel, minLen = 16, maxLen = 64) ?: return null
        val certified = extractValue(text, certifiedLabel, minLen = 4, maxLen = 16) ?: return null
        val usage = extractValue(text, usageLabel, minLen = 1, maxLen = 16) ?: return null
        return CucoFields(serial, certified, usage)
    }

    private fun extractValue(
        text: String,
        label: Regex,
        minLen: Int,
        maxLen: Int,
    ): String? {
        val match = label.find(text) ?: return null
        // Search forward from the end of the label match for the value.
        // The page renders "Label : VALUE" — value may follow on the same line or wrap.
        val tail = text.substring(match.range.last + 1)
            .lineSequence()
            .firstOrNull { it.contains(Regex("""[0-9A-Za-z]""")) }
            ?: return null
        // Strip leading "1.", "2.", "3." numbering artefacts and the colon separator.
        val afterColon = tail.substringAfter(':', tail).trim()
        return cleanHex(afterColon, minLen, maxLen)
    }

    private fun cleanHex(raw: String, minLen: Int, maxLen: Int): String? {
        // Conservative OCR corrections inside the captured value only.
        val normalised = raw
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1').replace('L', '1')
            .uppercase()
        // Take the longest contiguous hex run on this segment.
        val best = Regex("""[0-9A-F]+""").findAll(normalised)
            .map { it.value }
            .maxByOrNull { it.length }
            ?: return null
        if (best.length < minLen || best.length > maxLen) return null
        return best
    }
}
