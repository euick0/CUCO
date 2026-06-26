package pt.cuco.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CucoOcrParserTest {

    @Test
    fun parsesSampleScreen() {
        val text = """
            O seu computador esta bloqueado pela seguranca CUCo
            Aceda a iland.pt/suportecuco ou contacte o suporte Inforlandia: 234 340 880 para ajuda.

            1. Machine Serial Number : 495DC99EDEFD2E3B1CD06C0C71875F25
            2. Certified Time        : 1E2A5A2E
            3. Usage Counter         : 00000001

            Enter Unblocking Code: _
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("495DC99EDEFD2E3B1CD06C0C71875F25", fields!!.serial)
        assertEquals("1E2A5A2E", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
        assertEquals("1", fields.usageCounterTrimmed())
    }

    @Test
    fun parsesUserSubmittedScreen() {
        val text = """
            O seu computador esta bloqueado pela seguranca CUCo
            Aceda a iland.pt/suportecuco ou contacte o suporte Inforlandia: 234 340 880 para
            desbloquear ou obter ajuda.

            1. Machine Serial Number : 9F0D5D448916710C6053779DCBC24EA9
            2. Certified Time        : 1D293962
            3. Usage Counter         : 00000001

            Enter Unblocking Code: _
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun parsesWrappedAndNoisyOcr() {
        val text = """
            1. Mach1ne Serial Number :
            9F0D5D448916710C6053779DC8C24EA9
            2. Certif1ed T1me : 1D293962
            3. Usage Counter : 00000001
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DC8C24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("1", fields.usageCounterTrimmed())
    }

    @Test
    fun reassemblesSerialWrappedAcrossTwoLines() {
        // The 32-char serial wraps to two 16-char rows on the LCD. The parser
        // must rebuild the full serial, not keep only the first half.
        val text = """
            1. Machine Serial Number : 9F0D5D448916710C
            6053779DCBC24EA9
            2. Certified Time : 1D293962
            3. Usage Counter : 00000001
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun reassemblesSerialWrappedBelowLabel() {
        val text = """
            1. Machine Serial Number :
            9F0D5D448916710C
            6053779DCBC24EA9
            2. Certified Time : 1D293962
            3. Usage Counter : 00000001
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun reassemblesSerialWrappedInSpatialOcr() {
        // Simulates a real photo: the serial value is split into two spatial
        // lines (first half to the right of the label, second half on the row
        // below). Without repair only the first 16 chars survive.
        val lines = listOf(
            CucoOcrParser.OcrLine("1. Machine Serial Number :", 20, 100, 360, 130),
            CucoOcrParser.OcrLine("9F0D5D448916710C", 380, 100, 760, 130),
            CucoOcrParser.OcrLine("6053779DCBC24EA9", 380, 150, 760, 180),
            CucoOcrParser.OcrLine("2. Certified Time : 1D293962", 20, 210, 700, 240),
            CucoOcrParser.OcrLine("3. Usage Counter : 00000001", 20, 260, 700, 290),
        )

        val fields = CucoOcrParser.parse(lines)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun reassemblesSerialWrappedInValueRows() {
        val lines = listOf(
            CucoOcrParser.OcrLine("9F0D5D448916710C", 20, 100, 400, 130),
            CucoOcrParser.OcrLine("6053779DCBC24EA9", 20, 150, 400, 180),
            CucoOcrParser.OcrLine("1D293962", 20, 200, 210, 230),
            CucoOcrParser.OcrLine("00000001", 20, 250, 210, 280),
        )

        val fields = CucoOcrParser.parseValueRows(lines)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun parsesUsageTimeVariant() {
        val text = """
            Machine Serial Number: ABCDEF0123456789
            Certified Time: 1DEADBEE
            Usage Time: 0000000A
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("ABCDEF0123456789", fields!!.serial)
        assertEquals("1DEADBEE", fields.certifiedTime)
        assertEquals("A", fields.usageCounterTrimmed())
    }

    @Test
    fun parsesViaNumberedPrefixWhenLabelsMangled() {
        // OCR severely mis-reads label words but the "1.", "2.", "3." prefix
        // and the values themselves survive — the parser falls back to the
        // numbered prefix to assign each value to its correct field.
        val text = """
            1. M@chnle SeriaI Numb3r : 9F0D5D448916710C6053779DCBC24EA9
            2. Certifled T!me        : 1D293962
            3. Usage C0unt3r         : 00000001
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun handlesUsageCounterWithZeroOhTypo() {
        // OCR commonly mis-reads the 'o' in "Counter" as '0'. The label regex
        // and the numbered-prefix fallback both have to tolerate this.
        val text = """
            1. Machine Serial Number : 9F0D5D448916710C6053779DCBC24EA9
            2. Certified Time        : 1D293962
            3. Usage C0unter         : 00000001
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("00000001", fields!!.usageCounter)
    }

    @Test
    fun trimsAtNextLabelOnJoinedLine() {
        // If OCR joins multiple fields onto a single line, each label's value
        // must stop at the next label, not greedily eat the rest of the line.
        val text =
            "1. Machine Serial Number : 9F0D5D448916710C6053779DCBC24EA9 " +
                "2. Certified Time : 1D293962 " +
                "3. Usage Counter : 00000001"

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun parsesColumnSplitTextFromRealPhotoOcrOrder() {
        val text = """
            O seu computador esta bloqueado pela seguranca CUCo
            Aceda a iland.pt/suportecuco ou contacte o suporte Inforlandia para ajuda.

            1. Machine Serial Number :
            2. Certified Time
            3. Usage Counter
            : 9F0D5D448916710C6053779DCBC24EA9
            : 1D293962
            : 00000001

            Enter Unblocking Code: _
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun parsesSpatiallySeparatedRowsFromStructuredOcr() {
        val lines = listOf(
            CucoOcrParser.OcrLine("1. Machine Serial Number :", 20, 100, 360, 130),
            CucoOcrParser.OcrLine("2. Certified Time", 20, 150, 280, 180),
            CucoOcrParser.OcrLine("3. Usage Counter", 20, 200, 280, 230),
            CucoOcrParser.OcrLine(": 1D293962", 520, 150, 700, 180),
            CucoOcrParser.OcrLine(": 00000001", 520, 200, 700, 230),
            CucoOcrParser.OcrLine(": 9F0D5D448916710C6053779DCBC24EA9", 520, 100, 1160, 130),
        )

        val fields = CucoOcrParser.parse(lines)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun parsesValueOnlyRowsFromCroppedOcr() {
        val lines = listOf(
            CucoOcrParser.OcrLine(": 9F0D5D448916710C6053779DCBC24EA9", 20, 100, 760, 130),
            CucoOcrParser.OcrLine(": 1D293962", 20, 150, 210, 180),
            CucoOcrParser.OcrLine(": 00000001", 20, 200, 210, 230),
        )

        val fields = CucoOcrParser.parseValueRows(lines)
        assertNotNull(fields)
        assertEquals("9F0D5D448916710C6053779DCBC24EA9", fields!!.serial)
        assertEquals("1D293962", fields.certifiedTime)
        assertEquals("00000001", fields.usageCounter)
    }

    @Test
    fun rejectsDuplicateFieldValues() {
        val text = """
            1. Machine Serial Number : 9F0D5D448916710C6053779DCBC24EA9
            2. Certified Time        : 1D293962
            3. Usage Counter         : 1D293962
        """.trimIndent()

        assertNull(CucoOcrParser.parse(text))
    }

    @Test
    fun ignoresUnblockingCodeNoise() {
        // If the Certified Time value gets dropped by OCR, the parser must NOT
        // fall through to the "Enter Unblocking Code" line (where "Code" would
        // normalize to "C0DE") or to the Usage Counter line.
        val text = """
            1. Machine Serial Number : 9F0D5D448916710C6053779DCBC24EA9
            2. Certified Time        :
            3. Usage Counter         : 00000001
            Enter Unblocking Code: _
        """.trimIndent()

        assertNull(CucoOcrParser.parse(text))
    }

    @Test
    fun doesNotInferGlobalCandidatesWithoutCucoAnchors() {
        val text = """
            1. unrelated heading
            2. another note
            3. final note
            9F0D5D448916710C6053779DCBC24EA9
            1D293962
            00000001
        """.trimIndent()

        assertNull(CucoOcrParser.parse(text))
    }

    @Test
    fun returnsNullWhenLabelMissing() {
        val text = "no relevant labels here, just random text"
        assertNull(CucoOcrParser.parse(text))
    }
}
