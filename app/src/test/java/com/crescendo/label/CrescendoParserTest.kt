package com.crescendo.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrescendoParserTest {

    @Test
    fun testStatus9000() {
        val resp1 = CrescendoParser.hexToBytes("6F108408A000000151000000A5049F6501FF9000")
        val respErr = CrescendoParser.hexToBytes("6A82")
        val fidoSelectResp = CrescendoParser.hexToBytes("5532465F56329000")
        assertTrue(CrescendoParser.isStatus9000(resp1))
        assertTrue(CrescendoParser.isStatus9000(fidoSelectResp))
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

    @Test
    fun testExtractAaguidAndDeviceNameFromGetInfo() {
        val getInfoHex = "00AD0183665532465F5632684649444F5F325F30684649444F5F325F3102826B6372656450726F746563746B686D61632D73656372657403507991798AA7F3487F98C03FAF7A458A0404A862726BF568616C776179735576F468637265644D676D74F569617574686E72436667F569636C69656E7450696EF56E70696E557641757468546F6B656EF56F7365744D696E50494E4C656E677468F5706D616B654372656455764E6F74527164F5051901FE06820201070108185E0982636E6663637573620CF40D040E1A0004000014181D9000"
        val getInfoResp = CrescendoParser.hexToBytes(getInfoHex)

        val aaguid = CrescendoParser.extractAaguid(getInfoResp)
        assertNotNull(aaguid)
        assertEquals("7991798a-a7f3-487f-98c0-3faf7a458a04", aaguid)

        val deviceName = CrescendoParser.resolveDeviceName(aaguid)
        assertEquals("Crescendo Key V3", deviceName)
    }

    @Test
    fun testUnknownAaguidReturnsFallback() {
        val unknown = "11111111-2222-3333-4444-555555555555"
        val deviceName = CrescendoParser.resolveDeviceName(unknown)
        assertEquals("Unknown Crescendo Device", deviceName)
    }
}
