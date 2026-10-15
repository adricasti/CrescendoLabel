package com.crescendo.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrescendoParserTest {

    @Test
    fun testStatus9000() {
        val resp1 = CrescendoParser.hexToBytes("6F108408A000000151000000A5049F6501FF9000")
        val respErr = CrescendoParser.hexToBytes("6A82")
        assertTrue(CrescendoParser.isStatus9000(resp1))
        assertTrue(!CrescendoParser.isStatus9000(respErr))
    }

    @Test
    fun testExtractSerialNumber() {
        val resp2 = CrescendoParser.hexToBytes("9F7F2A4790D60047000000000052133318782136170000000072386006000000000102356600000000000000009000")
        val serial = CrescendoParser.extractSerialNumber(resp2)
        assertEquals("4790D600-3617-33187821", serial)
    }

    @Test
    fun testExtractAppletVersion() {
        val resp3 = CrescendoParser.hexToBytes("6F3B8407A0000000791000A5300106100400000127200601010103005A31060606080408FF32040606085A24015A25035A5A5A260103270181330200009000")
        val version = CrescendoParser.extractAppletVersion(resp3)
        assertEquals("4.0.0.295", version)
    }
}
