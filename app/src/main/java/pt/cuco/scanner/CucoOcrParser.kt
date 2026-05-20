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

    private data class FieldPattern(
        val canonicalName: String,
        val labelRegex: Regex,
        val minLen: Int,
        val maxLen: Int,
    )

    private val fieldPatterns = listOf(
        FieldPattern(
            canonicalName = "serial",
            labelRegex = Regex("""mach[i1l]n[e3]\s*ser[i1l]a[l1]\s*n[uv]mb[e3]r""", RegexOption.IGNORE_CASE),
            minLen = 16,
            maxLen = 64,
        ),
        FieldPattern(
            canonicalName = "certified",
            labelRegex = Regex("""cert[i1l]f[i1l][e3]d\s*t[i1l]m[e3]""", RegexOption.IGNORE_CASE),
            minLen = 4,
            maxLen = 16,
        ),
        FieldPattern(
            canonicalName = "usage",
            labelRegex = Regex("""[uv]sag[e3]\s*(co[uv]nt[e3]r|t[i1l]m[e3])""", RegexOption.IGNORE_CASE),
            minLen = 1,
            maxLen = 16,
        ),
    )

    // Lines that should never be treated as value-bearing (the CUCO screen header
    // and the "Enter Unblocking Code" prompt contain words that survive OCR
    // normalization to look like hex — e.g. "Code" -> "C0DE").
    private val rejectLineRegex = Regex(
        """unblock|unlock|enter|c[o0]d[e3]|desbloque|aceda|suporte|inforland|seguranca|computador""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): CucoFields? {
        val lines = preprocess(text).lines()
        val values = mutableMapOf<String, String>()

        for (pattern in fieldPatterns) {
            extractByLabel(lines, pattern)?.let { values[pattern.canonicalName] = it }
        }

        val serial = values["serial"] ?: return null
        val certified = values["certified"] ?: return null
        val usage = values["usage"] ?: return null

        return CucoFields(serial, certified, usage)
    }

    private fun preprocess(text: String): String =
        text.replace(' ', ' ')
            .replace(Regex("""[\t\r]+"""), " ")
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")

    private fun extractByLabel(lines: List<String>, pattern: FieldPattern): String? {
        for (i in lines.indices) {
            val line = lines[i]
            if (!pattern.labelRegex.containsMatchIn(line)) continue

            // Prefer the value on the same line, after the first colon.
            // Anchoring to the colon avoids picking up letters from the label itself.
            val afterColon = if (line.contains(':')) line.substringAfter(':') else ""
            cleanHex(afterColon, pattern.minLen, pattern.maxLen, fromLabeledValue = true)
                ?.let { return it }

            // Fall back to the next 1-2 lines only when they look like a continuation
            // (e.g. the serial wrapped). Stop the moment we hit another labeled line
            // or a known noise line so we never pull the usage value into ctime, etc.
            for (j in (i + 1)..(i + 2).coerceAtMost(lines.size - 1)) {
                val next = lines[j]
                if (next.isBlank()) continue
                if (isOtherLabelLine(next, pattern)) break
                if (rejectLineRegex.containsMatchIn(next)) break
                cleanHex(next, pattern.minLen, pattern.maxLen, fromLabeledValue = false)
                    ?.let { return it }
            }
        }
        return null
    }

    private fun isOtherLabelLine(line: String, current: FieldPattern): Boolean =
        fieldPatterns.any { it !== current && it.labelRegex.containsMatchIn(line) }

    private fun cleanHex(raw: String, minLen: Int, maxLen: Int, fromLabeledValue: Boolean): String? {
        // 1:1 char normalization so indices in `normalized` map back to `raw`.
        val normalized = buildString(raw.length) {
            for (c in raw) {
                append(
                    when (c) {
                        'O', 'o' -> '0'
                        'I', 'l', 'L' -> '1'
                        in 'a'..'f' -> ('A' + (c - 'a'))
                        else -> c
                    }
                )
            }
        }
        val match = Regex("""[0-9A-F]+""").findAll(normalized)
            .maxByOrNull { it.value.length }
            ?: return null
        val best = match.value
        if (best.length !in minLen..maxLen) return null

        // Reject English-word-like matches that survive normalization (e.g. "Code" ->
        // "C0DE"). When the match came from a continuation line rather than directly
        // after a labeled colon, require at least one digit that was already present
        // in the source text — purely normalized letters don't count.
        if (!fromLabeledValue) {
            val original = raw.substring(match.range.first, match.range.last + 1)
            if (!original.any { it.isDigit() }) return null
        }

        return best
    }
}
