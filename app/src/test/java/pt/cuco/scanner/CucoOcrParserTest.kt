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
    fun parsesUsageTimeVariant() {
        val text = """
            Machine Serial Number: ABCDEF0123456789
            Certified Time: DEADBEEF
            Usage Time: 0000000A
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("ABCDEF0123456789", fields!!.serial)
        assertEquals("DEADBEEF", fields.certifiedTime)
        assertEquals("A", fields.usageCounterTrimmed())
    }

    @Test
    fun correctsCommonOcrConfusions() {
        // OCR mis-reads inside the value: O→0, I→1, lowercase, all still valid hex.
        val text = """
            Machine Serial Number : abcdefOI23456789abcdefOI23456789
            Certified Time : 1E2A5A2E
            Usage Counter : 0000000I
        """.trimIndent()

        val fields = CucoOcrParser.parse(text)
        assertNotNull(fields)
        assertEquals("ABCDEF0123456789ABCDEF0123456789", fields!!.serial)
        assertEquals("1E2A5A2E", fields.certifiedTime)
        assertEquals("1", fields.usageCounterTrimmed())
    }

    @Test
    fun returnsNullWhenLabelMissing() {
        val text = "no relevant labels here, just random text"
        assertNull(CucoOcrParser.parse(text))
    }
}
