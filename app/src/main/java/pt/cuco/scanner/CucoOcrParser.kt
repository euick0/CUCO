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

    fun parse(text: String): CucoFields? {
        val cleaned = preprocess(text)
        val values = mutableMapOf<String, String>()

        // 1) Preferred: line-aware extraction near each label.
        for (pattern in fieldPatterns) {
            extractByLabel(cleaned, pattern)?.let { values[pattern.canonicalName] = it }
        }

        // 2) Fallback: if OCR split text badly, infer from candidate pool by expected lengths/order.
        if (values.size < 3) {
            inferMissingFromGlobalCandidates(cleaned, values)
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

    private fun extractByLabel(text: String, pattern: FieldPattern): String? {
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (!pattern.labelRegex.containsMatchIn(line)) continue

            // Same line after ':' first, then next line fallback.
            val candidateSegments = buildList {
                add(line.substringAfter(':', ""))
                if (i + 1 < lines.size) add(lines[i + 1])
                if (i + 2 < lines.size) add(lines[i + 2])
            }

            candidateSegments.forEach { segment ->
                cleanHex(segment, pattern.minLen, pattern.maxLen)?.let { return it }
            }
        }
        return null
    }

    private fun inferMissingFromGlobalCandidates(text: String, values: MutableMap<String, String>) {
        val candidates = Regex("""[0-9A-Fa-fOolLI]{4,}""").findAll(text)
            .mapNotNull { cleanHex(it.value, 1, 64) }
            .distinct()
            .toList()

        if ("serial" !in values) {
            values["serial"] = candidates.firstOrNull { it.length in 16..64 } ?: return
        }
        if ("certified" !in values) {
            values["certified"] = candidates.firstOrNull { it.length in 4..16 && it != values["serial"] } ?: return
        }
        if ("usage" !in values) {
            values["usage"] = candidates.lastOrNull { it.length in 1..16 && it != values["serial"] && it != values["certified"] } ?: return
        }
    }

    private fun cleanHex(raw: String, minLen: Int, maxLen: Int): String? {
        val normalized = raw
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1').replace('L', '1')
            .uppercase()
        val best = Regex("""[0-9A-F]+""").findAll(normalized)
            .map { it.value }
            .maxByOrNull { it.length }
            ?: return null
        if (best.length !in minLen..maxLen) return null
        return best
    }
}
