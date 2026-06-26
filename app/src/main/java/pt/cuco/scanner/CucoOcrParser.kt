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

    data class OcrLine(
        val text: String,
        val left: Int? = null,
        val top: Int? = null,
        val right: Int? = null,
        val bottom: Int? = null,
    )

    private data class FieldPattern(
        val canonicalName: String,
        val numericPrefix: Int,
        val labelRegex: Regex,
        val minLen: Int,
        val maxLen: Int,
        val preferredLen: Int,
    )

    private data class IndexedOcrLine(
        val index: Int,
        val line: OcrLine,
    )

    private data class HexCandidate(
        val value: String,
        val lineIndex: Int,
        val order: Int,
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
            preferredLen = 32,
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
            preferredLen = 8,
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
            preferredLen = 8,
        ),
    )

    // Lines that should never be treated as value-bearing: the CUCO screen
    // header and the "Enter Unblocking Code" prompt contain words that survive
    // OCR normalization to look like hex (e.g. "Code" -> "C0DE").
    private val rejectLineRegex = Regex(
        """cuco|unblock|unlock|desbloque|aceda|suporte|inforland|seguranca|computador""",
        RegexOption.IGNORE_CASE,
    )

    // Matches "1.", "2.", "3." (or ")" / ":") at the start of a line:
    // a reliable positional anchor even when the label text is mangled by OCR.
    private val numberedPrefixRegex = Regex("""^([1-3])\s*[.):]\s*""")
    private val globalHexCandidateRegex = Regex("""[0-9A-Fa-fOolLI]{4,64}""")
    private val hexOnlyRegex = Regex("""^[0-9A-F]+$""")

    // A serial shorter than this is treated as possibly truncated (the real
    // serial is 32 chars); joining stops once we reach it. A wrapped half is
    // ~16 chars, so continuation chunks must be at least MIN_WRAP_PART long to
    // avoid swallowing a short Certified Time / Usage Counter value.
    private const val SERIAL_WRAP_THRESHOLD = 28
    private const val MIN_WRAP_PART = 12

    fun parse(text: String): CucoFields? {
        val lines = preprocess(text).lines()
        val values = extractTextualValues(lines, allowContinuation = true)

        if (values.size < fieldPatterns.size) {
            inferMissingFromGlobalCandidates(lines, values)
        }

        repairSerial(values, lines.flatMap { collectHexValues(it, minLen = 1, maxLen = 64) })
        return toFieldsOrNull(values)
    }

    fun parse(ocrLines: List<OcrLine>, fallbackText: String = ""): CucoFields? {
        val preparedOcrLines = ocrLines.mapIndexedNotNull { index, line ->
            val cleanedText = preprocessLine(line.text)
            if (cleanedText.isBlank()) null else IndexedOcrLine(index, line.copy(text = cleanedText))
        }
        val fallbackLines = preprocess(
            fallbackText.ifBlank {
                preparedOcrLines.joinToString("\n") { it.line.text }
            }
        ).lines()
        val hasSpatialData = preparedOcrLines.any { it.line.hasBounds }
        val values = extractTextualValues(
            fallbackLines,
            allowContinuation = !hasSpatialData,
        )

        if (values.size < fieldPatterns.size && hasSpatialData) {
            extractBySpatialRows(preparedOcrLines, values)
        }

        if (values.size < fieldPatterns.size) {
            inferMissingFromGlobalCandidates(fallbackLines, values)
        }

        repairSerial(values, orderedHexCandidates(preparedOcrLines, fallbackLines))
        return toFieldsOrNull(values)
    }

    fun parseValueRows(ocrLines: List<OcrLine>, fallbackText: String = ""): CucoFields? {
        val preparedOcrLines = ocrLines.mapIndexedNotNull { index, line ->
            val cleanedText = preprocessLine(line.text)
            if (cleanedText.isBlank()) null else IndexedOcrLine(index, line.copy(text = cleanedText))
        }
        val fallbackLines = preprocess(
            fallbackText.ifBlank {
                preparedOcrLines.joinToString("\n") { it.line.text }
            }
        ).lines()

        val orderedCandidates = orderedHexCandidates(preparedOcrLines, fallbackLines)
        val serialIndex = orderedCandidates.indexOfFirst { it.length in 16..64 }
        if (serialIndex < 0) return null

        // The 32-char serial wraps to two ~16-char rows on the CUCo LCD; rebuild
        // it from consecutive value chunks instead of keeping just the first half.
        var serialValue = orderedCandidates[serialIndex]
        var afterSerialIndex = serialIndex
        if (serialValue.length < SERIAL_WRAP_THRESHOLD) {
            val (joined, lastIndex) = joinWrappedSerial(orderedCandidates, serialIndex, emptySet())
            if (joined.length >= SERIAL_WRAP_THRESHOLD && joined.length in 16..64) {
                serialValue = joined
                afterSerialIndex = lastIndex
            }
        }

        val certifiedIndex = orderedCandidates.withIndex()
            .firstOrNull { (index, value) ->
                index > afterSerialIndex && value.length in 4..16
            }
            ?.index
            ?: return null
        val usage = orderedCandidates.withIndex()
            .firstOrNull { (index, value) ->
                index > certifiedIndex && value.length in 1..16
            }
            ?.value
            ?: return null

        return toFieldsOrNull(
            mapOf(
                "serial" to serialValue,
                "certified" to orderedCandidates[certifiedIndex],
                "usage" to usage,
            )
        )
    }

    /** Hex value chunks across all lines, in reading order (top→bottom, left→right). */
    private fun orderedHexCandidates(
        preparedOcrLines: List<IndexedOcrLine>,
        fallbackLines: List<String>,
    ): List<String> {
        val orderedLines = if (preparedOcrLines.any { it.line.hasBounds }) {
            preparedOcrLines.sortedWith(
                compareBy<IndexedOcrLine> { it.line.top ?: Int.MAX_VALUE }
                    .thenBy { it.line.left ?: Int.MAX_VALUE }
                    .thenBy { it.index }
            ).map { it.line.text }
        } else {
            fallbackLines
        }
        return orderedLines.flatMap { collectHexValues(it, minLen = 1, maxLen = 64) }
    }

    /**
     * Fixes a machine serial number that OCR cut mid-code: the 32-char serial
     * wraps to two ~16-char lines on the LCD, and a single ≥16-char half passes
     * validation, so only one half is kept. Starting from the assigned serial, we
     * append following hex chunks that look like serial continuations until we get
     * back to ~32 characters.
     */
    private fun repairSerial(values: MutableMap<String, String>, orderedCandidates: List<String>) {
        val serial = values["serial"] ?: return
        if (serial.length >= SERIAL_WRAP_THRESHOLD) return
        val startIndex = orderedCandidates.indexOf(serial)
        if (startIndex < 0) return

        val excluded = setOfNotNull(values["certified"], values["usage"])
        val (joined, _) = joinWrappedSerial(orderedCandidates, startIndex, excluded)
        if (joined.length >= SERIAL_WRAP_THRESHOLD &&
            joined.length in 16..64 &&
            joined.length > serial.length
        ) {
            values["serial"] = joined
        }
    }

    /**
     * Concatenates the chunk at [startIndex] with the following chunks while they
     * look like serial continuations (long enough, not an already-identified
     * field) until reaching [SERIAL_WRAP_THRESHOLD]. Returns the joined value and
     * the index of the last chunk consumed.
     */
    private fun joinWrappedSerial(
        candidates: List<String>,
        startIndex: Int,
        excluded: Set<String>,
    ): Pair<String, Int> {
        val builder = StringBuilder(candidates[startIndex])
        var lastIndex = startIndex
        var k = startIndex + 1
        while (k < candidates.size && builder.length < SERIAL_WRAP_THRESHOLD) {
            val next = candidates[k]
            if (next.length < MIN_WRAP_PART) break
            if (next in excluded) break
            if (builder.length + next.length > 64) break
            builder.append(next)
            lastIndex = k
            k++
        }
        return builder.toString() to lastIndex
    }

    fun confidenceScore(fields: CucoFields): Int {
        if (!isValidCandidate(fields)) return 0
        var score = 20
        score += lengthScore(fields.serial.length, preferred = 32, min = 16, max = 64, exactPoints = 40)
        score += lengthScore(fields.certifiedTime.length, preferred = 8, min = 4, max = 16, exactPoints = 20)
        score += lengthScore(fields.usageCounter.length, preferred = 8, min = 1, max = 16, exactPoints = 20)
        if (fields.serial.any { it.isDigit() } && fields.serial.any { it in 'A'..'F' }) score += 6
        if (fields.certifiedTime.any { it.isDigit() }) score += 3
        if (fields.usageCounter.any { it.isDigit() }) score += 3
        return score
    }

    private fun extractTextualValues(
        lines: List<String>,
        allowContinuation: Boolean,
    ): MutableMap<String, String> {
        val values = mutableMapOf<String, String>()

        for (pattern in fieldPatterns) {
            extractByLabel(lines, pattern, allowContinuation)
                ?.let { values[pattern.canonicalName] = it }
        }

        if (values.size < fieldPatterns.size) {
            extractByNumberedPrefix(lines, values)
        }

        return values
    }

    private fun preprocess(text: String): String =
        text.replace('\u00A0', ' ')
            .replace(Regex("""[\t\r]+"""), " ")
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")

    private fun preprocessLine(text: String): String =
        text.replace('\u00A0', ' ')
            .replace(Regex("""[\t\r]+"""), " ")
            .trim()

    private fun extractByLabel(
        lines: List<String>,
        pattern: FieldPattern,
        allowContinuation: Boolean,
    ): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val labelMatch = pattern.labelRegex.find(line) ?: continue

            val afterLabel = line.substring(labelMatch.range.last + 1)
            val segment = trimAtNextLabel(afterLabel, pattern)
            val candidate = if (segment.contains(':')) segment.substringAfter(':') else segment
            cleanHex(candidate, pattern.minLen, pattern.maxLen, fromLabeledValue = true, pattern.preferredLen)
                ?.let { return it }

            if (!allowContinuation) continue

            for (j in (i + 1)..(i + 2).coerceAtMost(lines.size - 1)) {
                val next = lines[j]
                if (next.isBlank()) continue
                if (isOtherLabelLine(next, pattern)) break
                if (rejectLineRegex.containsMatchIn(next)) break
                cleanHex(next, pattern.minLen, pattern.maxLen, fromLabeledValue = false, pattern.preferredLen)
                    ?.let { return it }
                break
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
            val candidate = if (segment.contains(':')) {
                segment.substringAfter(':')
            } else {
                val labelMatch = pattern.labelRegex.find(segment)
                if (labelMatch != null) {
                    segment.substring(labelMatch.range.last + 1)
                } else {
                    segment
                }
            }
            cleanHex(candidate, pattern.minLen, pattern.maxLen, fromLabeledValue = true, pattern.preferredLen)
                ?.let { values[pattern.canonicalName] = it }
        }
    }

    private fun extractBySpatialRows(
        ocrLines: List<IndexedOcrLine>,
        values: MutableMap<String, String>,
    ) {
        val boundedLines = ocrLines.filter { it.line.hasBounds }
        if (boundedLines.isEmpty()) return

        val lineByIndex = ocrLines.associateBy { it.index }
        val usedLineIndexes = mutableSetOf<Int>()
        for (pattern in fieldPatterns) {
            if (pattern.canonicalName in values) continue

            val anchors = boundedLines.filter { it.line.isAnchorFor(pattern) }
            if (anchors.isEmpty()) continue

            val candidates = boundedLines
                .filterNot { it.line.isAnyFieldAnchor() }
                .mapNotNull { indexedLine ->
                    cleanHex(
                        indexedLine.line.text,
                        pattern.minLen,
                        pattern.maxLen,
                        fromLabeledValue = true,
                        preferredLen = pattern.preferredLen,
                    )?.let { value ->
                        HexCandidate(value, indexedLine.index, indexedLine.index)
                    }
                }
                .filterNot { it.lineIndex in usedLineIndexes }

            val best = anchors
                .flatMap { anchor ->
                    candidates.mapNotNull { candidate ->
                        val candidateLine = lineByIndex[candidate.lineIndex]?.line
                            ?: return@mapNotNull null
                        val score = spatialScore(anchor.line, candidateLine) ?: return@mapNotNull null
                        score to candidate
                    }
                }
                .minByOrNull { it.first }
                ?.second

            if (best != null) {
                values[pattern.canonicalName] = best.value
                usedLineIndexes += best.lineIndex
            }
        }
    }

    private fun inferMissingFromGlobalCandidates(
        lines: List<String>,
        values: MutableMap<String, String>,
    ) {
        if (!hasGuardedFallbackContext(lines)) return

        val candidates = collectGlobalCandidates(lines)
        if (candidates.isEmpty()) return

        val usedValues = values.values.toMutableSet()

        var serialOrder = candidates.firstOrNull { it.value == values["serial"] }?.order ?: -1
        if ("serial" !in values) {
            val serial = candidates.firstPreferredOrAny(32) {
                it.value !in usedValues && it.value.length in 16..64
            } ?: return
            values["serial"] = serial.value
            usedValues += serial.value
            serialOrder = serial.order
        }

        var certifiedOrder = candidates.firstOrNull { it.value == values["certified"] }?.order ?: -1
        if ("certified" !in values) {
            val certified = candidates.firstPreferredOrAny(8) {
                it.order > serialOrder &&
                    it.value !in usedValues &&
                    it.value.length in 4..16
            } ?: return
            values["certified"] = certified.value
            usedValues += certified.value
            certifiedOrder = certified.order
        }

        if ("usage" !in values) {
            val usage = candidates.firstPreferredOrAny(8) {
                it.order > certifiedOrder &&
                    it.value !in usedValues &&
                    it.value.length in 1..16
            } ?: return
            values["usage"] = usage.value
        }
    }

    private fun hasGuardedFallbackContext(lines: List<String>): Boolean {
        val joined = lines.joinToString("\n")
        val labelMatches = fieldPatterns.count { pattern ->
            lines.any { pattern.labelRegex.containsMatchIn(it) }
        }
        val numberedMatches = lines
            .mapNotNull { numberedPrefixRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .count()
        val hasCucoContext = rejectLineRegex.containsMatchIn(joined)
        return (labelMatches >= 1 && labelMatches + numberedMatches >= 2) ||
            (hasCucoContext && labelMatches + numberedMatches >= 2)
    }

    private fun collectGlobalCandidates(lines: List<String>): List<HexCandidate> {
        val candidates = mutableListOf<HexCandidate>()
        var order = 0
        for ((lineIndex, line) in lines.withIndex()) {
            for (match in globalHexCandidateRegex.findAll(line)) {
                cleanHex(match.value, 1, 64, fromLabeledValue = false)?.let {
                    candidates += HexCandidate(it, lineIndex, order)
                    order += 1
                }
            }
        }
        return candidates
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

    private fun OcrLine.isAnchorFor(pattern: FieldPattern): Boolean =
        pattern.labelRegex.containsMatchIn(text) ||
            numberedPrefixRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() == pattern.numericPrefix

    private fun OcrLine.isAnyFieldAnchor(): Boolean =
        fieldPatterns.any { isAnchorFor(it) }

    private val OcrLine.hasBounds: Boolean
        get() {
            val l = left ?: return false
            val t = top ?: return false
            val r = right ?: return false
            val b = bottom ?: return false
            return r > l && b > t
        }

    private fun spatialScore(anchor: OcrLine, candidate: OcrLine): Double? {
        if (!anchor.hasBounds || !candidate.hasBounds) return null
        val anchorCenterY = anchor.centerY()
        val candidateCenterY = candidate.centerY()
        val verticalDelta = kotlin.math.abs(anchorCenterY - candidateCenterY)
        val maxHeight = maxOf(anchor.height(), candidate.height())
        val overlap = minOf(anchor.bottom!!, candidate.bottom!!) - maxOf(anchor.top!!, candidate.top!!)
        val sameRow = overlap > 0 || verticalDelta <= maxHeight * 1.25
        val toRight = candidate.centerX() > anchor.centerX()
        if (!sameRow || !toRight) return null

        val horizontalGap = maxOf(0, candidate.left!! - anchor.right!!)
        return verticalDelta * 10.0 + horizontalGap.toDouble() / maxHeight
    }

    private fun OcrLine.centerX(): Double = (left!! + right!!) / 2.0

    private fun OcrLine.centerY(): Double = (top!! + bottom!!) / 2.0

    private fun OcrLine.height(): Int = bottom!! - top!!

    private fun cleanHex(
        raw: String,
        minLen: Int,
        maxLen: Int,
        fromLabeledValue: Boolean,
        preferredLen: Int? = null,
    ): String? {
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
        val matches = Regex("""[0-9A-F]+""").findAll(normalized)
            .filter { it.value.length in minLen..maxLen }
            .toList()
        val match = matches.firstOrNull { it.value.length == preferredLen }
            ?: matches.maxByOrNull { it.value.length }
            ?: return null
        val best = match.value

        if (!fromLabeledValue) {
            val original = raw.substring(match.range.first, match.range.last + 1)
            if (!original.any { it.isDigit() }) return null
        }
        return best
    }

    private fun collectHexValues(raw: String, minLen: Int, maxLen: Int): List<String> {
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
        return Regex("""[0-9A-F]+""").findAll(normalized)
            .map { it.value }
            .filter { it.length in minLen..maxLen }
            .toList()
    }

    private fun lengthScore(
        length: Int,
        preferred: Int,
        min: Int,
        max: Int,
        exactPoints: Int,
    ): Int =
        when {
            length == preferred -> exactPoints
            length in min..max -> exactPoints / 2
            else -> 0
        }

    private inline fun List<HexCandidate>.firstPreferredOrAny(
        preferredLen: Int,
        predicate: (HexCandidate) -> Boolean,
    ): HexCandidate? =
        firstOrNull { predicate(it) && it.value.length == preferredLen }
            ?: firstOrNull(predicate)

    private fun toFieldsOrNull(values: Map<String, String>): CucoFields? {
        val serial = values["serial"] ?: return null
        val certified = values["certified"] ?: return null
        val usage = values["usage"] ?: return null
        val fields = CucoFields(serial, certified, usage)
        return fields.takeIf(::isValidCandidate)
    }

    private fun isValidCandidate(fields: CucoFields): Boolean {
        if (!hexOnlyRegex.matches(fields.serial)) return false
        if (!hexOnlyRegex.matches(fields.certifiedTime)) return false
        if (!hexOnlyRegex.matches(fields.usageCounter)) return false
        if (fields.serial.length !in 16..64) return false
        if (fields.certifiedTime.length !in 4..16) return false
        if (fields.usageCounter.length !in 1..16) return false
        if (fields.serial.length <= fields.certifiedTime.length) return false
        if (fields.serial.length <= fields.usageCounter.length) return false
        return setOf(fields.serial, fields.certifiedTime, fields.usageCounter).size == 3
    }
}
