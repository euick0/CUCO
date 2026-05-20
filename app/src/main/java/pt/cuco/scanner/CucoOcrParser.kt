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
        val numericPrefix: Int,
        val labelRegex: Regex,
        val minLen: Int,
        val maxLen: Int,
    )

    private val fieldPatterns = listOf(
        FieldPattern(
            canonicalName = "serial",
            numericPrefix = 1,
            labelRegex = Regex(
                """mach[i1l]n[e3]\s*ser[i1l]a[l1]\s*n[uv]mb[e3]r""",
                RegexOption.IGNORE_CASE,
            ),
            minLen = 16,
            maxLen = 64,
        ),
        FieldPattern(
            canonicalName = "certified",
            numericPrefix = 2,
            labelRegex = Regex(
                """cert[i1l]f[i1l][e3]d\s*t[i1l]m[e3]""",
                RegexOption.IGNORE_CASE,
            ),
            minLen = 4,
            maxLen = 16,
        ),
        FieldPattern(
            canonicalName = "usage",
            numericPrefix = 3,
            labelRegex = Regex(
                """[uv]sag[e3]\s*(c[o0][uv]nt[e3]r|t[i1l]m[e3])""",
                RegexOption.IGNORE_CASE,
            ),
            minLen = 1,
            maxLen = 16,
        ),
    )

    // Lines that should never be treated as value-bearing — the CUCO screen
    // header and the "Enter Unblocking Code" prompt contain words that survive
    // OCR normalization to look like hex (e.g. "Code" -> "C0DE").
    private val rejectLineRegex = Regex(
        """unblock|unlock|desbloque|aceda|suporte|inforland|seguranca|computador""",
        RegexOption.IGNORE_CASE,
    )

    // Matches "1.", "2.", "3." (or ")" / ":") at the start of a line —
    // a reliable positional anchor even when the label text is mangled by OCR.
    private val numberedPrefixRegex = Regex("""^([1-3])\s*[.):]\s*""")

    fun parse(text: String): CucoFields? {
        val lines = preprocess(text).lines()
        val values = mutableMapOf<String, String>()

        // Strategy 1: extract by label text (handles OCR substitutions in labels).
        for (pattern in fieldPatterns) {
            extractByLabel(lines, pattern)?.let { values[pattern.canonicalName] = it }
        }

        // Strategy 2: fall back to numbered-prefix anchors ("1.", "2.", "3.").
        // The numeric prefix on the CUCO screen survives OCR even when the
        // label words get garbled, so it's a reliable positional signal.
        if (values.size < fieldPatterns.size) {
            extractByNumberedPrefix(lines, values)
        }

        val serial = values["serial"] ?: return null
        val certified = values["certified"] ?: return null
        val usage = values["usage"] ?: return null

        return CucoFields(serial, certified, usage)
    }

    private fun preprocess(text: String): String =
        text.replace('\u00A0', ' ')
            .replace(Regex("""[\t\r]+"""), " ")
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")

    private fun extractByLabel(lines: List<String>, pattern: FieldPattern): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val labelMatch = pattern.labelRegex.find(line) ?: continue

            // Take the text right after the label, chopped at the next field
            // label if multiple are present on the same line.
            val afterLabel = line.substring(labelMatch.range.last + 1)
            val segment = trimAtNextLabel(afterLabel, pattern)
            val candidate = if (segment.contains(':')) segment.substringAfter(':') else segment
            cleanHex(candidate, pattern.minLen, pattern.maxLen, fromLabeledValue = true)
                ?.let { return it }

            // Fall back to the next 1-2 lines for wrapped values, but stop at
            // another labeled line or a known noise line so we never pull the
            // next field's value or "Code" -> "C0DE" into this field.
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

    private fun extractByNumberedPrefix(lines: List<String>, values: MutableMap<String, String>) {
        val patternByPrefix = fieldPatterns.associateBy { it.numericPrefix }
        for (line in lines) {
            val match = numberedPrefixRegex.find(line) ?: continue
            val pattern = patternByPrefix[match.groupValues[1].toInt()] ?: continue
            if (pattern.canonicalName in values) continue

            val rest = line.substring(match.range.last + 1)
            val segment = trimAtNextLabel(rest, pattern)
            val candidate = if (segment.contains(':')) segment.substringAfter(':') else segment
            cleanHex(candidate, pattern.minLen, pattern.maxLen, fromLabeledValue = true)
                ?.let { values[pattern.canonicalName] = it }
        }
    }

    private fun trimAtNextLabel(text: String, current: FieldPattern): String {
        val nextStart = fieldPatterns
            .filter { it !== current }
            .mapNotNull { it.labelRegex.find(text)?.range?.first }
            .minOrNull()
        return if (nextStart != null) text.substring(0, nextStart) else text
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
        // Pick the longest hex run that fits within [minLen, maxLen]. Filtering
        // by length BEFORE selecting the max ensures we don't lose a valid 8-char
        // ctime value just because a 32-char serial also appears on the same line.
        val match = Regex("""[0-9A-F]+""").findAll(normalized)
            .filter { it.value.length in minLen..maxLen }
            .maxByOrNull { it.value.length }
            ?: return null
        val best = match.value

        // For continuation-line matches (not directly after a labeled colon),
        // require at least one real digit in the source text so an OCR'd word
        // like "Code" -> "C0DE" doesn't pass as a hex value.
        if (!fromLabeledValue) {
            val original = raw.substring(match.range.first, match.range.last + 1)
            if (!original.any { it.isDigit() }) return null
        }
        return best
    }
}
