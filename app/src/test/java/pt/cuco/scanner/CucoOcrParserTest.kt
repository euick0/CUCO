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
    fun returnsNullWhenLabelMissing() {
        val text = "no relevant labels here, just random text"
        assertNull(CucoOcrParser.parse(text))
    }
}
