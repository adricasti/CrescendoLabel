package com.crescendo.label

/**
 * Represents a parsed ASN.1 BER-TLV structure.
 */
data class Tlv(
    val tag: Int,
    val value: ByteArray,
    val children: List<Tlv> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Tlv
        if (tag != other.tag) return false
        if (!value.contentEquals(other.value)) return false
        if (children != other.children) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tag
        result = 31 * result + value.contentHashCode()
        result = 31 * result + children.hashCode()
        return result
    }

    /**
     * Find a nested TLV following a sequence of tags.
     */
    fun find(vararg tagPath: Int): Tlv? {
        if (tagPath.isEmpty()) return this
        val currentTag = tagPath[0]
        val remaining = tagPath.slice(1 until tagPath.size).toIntArray()

        if (this.tag == currentTag) {
            if (remaining.isEmpty()) return this
            for (child in children) {
                val found = child.find(*remaining)
                if (found != null) return found
            }
        } else {
            for (child in children) {
                val found = child.find(*tagPath)
                if (found != null) return found
            }
        }
        return null
    }
}

/**
 * Represents a parsed CBOR value.
 */
sealed class CborValue {
    data class Integer(val value: Long) : CborValue()
    data class ByteString(val value: ByteArray) : CborValue() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ByteString
            return value.contentEquals(other.value)
        }
        override fun hashCode(): Int = value.contentHashCode()
    }
    data class TextString(val value: String) : CborValue()
    data class ArrayValue(val items: List<CborValue>) : CborValue()
    data class MapValue(val entries: Map<CborValue, CborValue>) : CborValue()
    data class Tagged(val tag: Long, val value: CborValue) : CborValue()
    data class Simple(val value: Int) : CborValue()
    data class BooleanValue(val value: Boolean) : CborValue()
    data object Null : CborValue()
}

/**
 * Lightweight RFC 8949 CBOR Decoder.
 */
class CborDecoder(private val data: ByteArray) {
    private var offset = 0

    fun hasMore(): Boolean = offset < data.size

    fun decode(): CborValue {
        if (!hasMore()) throw IllegalArgumentException("Unexpected end of CBOR data")
        val initialByte = data[offset++].toInt() and 0xFF
        val majorType = initialByte shr 5
        val additionalInfo = initialByte and 0x1F

        val lengthOrValue = when (additionalInfo) {
            in 0..23 -> additionalInfo.toLong()
            24 -> readUint8()
            25 -> readUint16()
            26 -> readUint32()
            27 -> readUint64()
            31 -> -1L // Indefinite length
            else -> throw IllegalArgumentException("Invalid additional info: $additionalInfo")
        }

        return when (majorType) {
            0 -> CborValue.Integer(lengthOrValue)
            1 -> CborValue.Integer(-1 - lengthOrValue)
            2 -> {
                if (lengthOrValue < 0) {
                    val chunks = mutableListOf<ByteArray>()
                    while (hasMore() && (data[offset].toInt() and 0xFF) != 0xFF) {
                        val chunk = decode()
                        if (chunk is CborValue.ByteString) {
                            chunks.add(chunk.value)
                        }
                    }
                    if (hasMore() && (data[offset].toInt() and 0xFF) == 0xFF) offset++
                    val totalSize = chunks.sumOf { it.size }
                    val result = ByteArray(totalSize)
                    var pos = 0
                    for (c in chunks) {
                        System.arraycopy(c, 0, result, pos, c.size)
                        pos += c.size
                    }
                    CborValue.ByteString(result)
                } else {
                    val len = lengthOrValue.toInt()
                    if (offset + len > data.size) throw IllegalArgumentException("Byte string out of bounds")
                    val bytes = data.copyOfRange(offset, offset + len)
                    offset += len
                    CborValue.ByteString(bytes)
                }
            }
            3 -> {
                if (lengthOrValue < 0) {
                    val sb = StringBuilder()
                    while (hasMore() && (data[offset].toInt() and 0xFF) != 0xFF) {
                        val chunk = decode()
                        if (chunk is CborValue.TextString) {
                            sb.append(chunk.value)
                        }
                    }
                    if (hasMore() && (data[offset].toInt() and 0xFF) == 0xFF) offset++
                    CborValue.TextString(sb.toString())
                } else {
                    val len = lengthOrValue.toInt()
                    if (offset + len > data.size) throw IllegalArgumentException("Text string out of bounds")
                    val str = String(data, offset, len, Charsets.UTF_8)
                    offset += len
                    CborValue.TextString(str)
                }
            }
            4 -> {
                val list = mutableListOf<CborValue>()
                if (lengthOrValue < 0) {
                    while (hasMore() && (data[offset].toInt() and 0xFF) != 0xFF) {
                        list.add(decode())
                    }
                    if (hasMore() && (data[offset].toInt() and 0xFF) == 0xFF) offset++
                } else {
                    for (i in 0 until lengthOrValue) {
                        list.add(decode())
                    }
                }
                CborValue.ArrayValue(list)
            }
            5 -> {
                val map = mutableMapOf<CborValue, CborValue>()
                if (lengthOrValue < 0) {
                    while (hasMore() && (data[offset].toInt() and 0xFF) != 0xFF) {
                        val key = decode()
                        val value = decode()
                        map[key] = value
                    }
                    if (hasMore() && (data[offset].toInt() and 0xFF) == 0xFF) offset++
                } else {
                    for (i in 0 until lengthOrValue) {
                        val key = decode()
                        val value = decode()
                        map[key] = value
                    }
                }
                CborValue.MapValue(map)
            }
            6 -> {
                val child = decode()
                CborValue.Tagged(lengthOrValue, child)
            }
            7 -> {
                when (additionalInfo) {
                    20 -> CborValue.BooleanValue(false)
                    21 -> CborValue.BooleanValue(true)
                    22 -> CborValue.Null
                    else -> CborValue.Simple(additionalInfo)
                }
            }
            else -> throw IllegalArgumentException("Unsupported major type: $majorType")
        }
    }

    private fun readUint8(): Long {
        if (offset >= data.size) throw IllegalArgumentException("Unexpected EOF")
        return (data[offset++].toInt() and 0xFF).toLong()
    }

    private fun readUint16(): Long {
        if (offset + 2 > data.size) throw IllegalArgumentException("Unexpected EOF")
        val b0 = data[offset++].toInt() and 0xFF
        val b1 = data[offset++].toInt() and 0xFF
        return ((b0 shl 8) or b1).toLong()
    }

    private fun readUint32(): Long {
        if (offset + 4 > data.size) throw IllegalArgumentException("Unexpected EOF")
        val b0 = data[offset++].toLong() and 0xFF
        val b1 = data[offset++].toLong() and 0xFF
        val b2 = data[offset++].toLong() and 0xFF
        val b3 = data[offset++].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readUint64(): Long {
        if (offset + 8 > data.size) throw IllegalArgumentException("Unexpected EOF")
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (data[offset++].toLong() and 0xFF)
        }
        return result
    }
}

object CrescendoParser {

    /**
     * Map of FIDO2 AAGUID to Crescendo product names, as documented in:
     * https://docs.hidglobal.com/crescendo/htog/aaguid.htm
     */
    val CRESCENDO_AAGUID_MAP = mapOf(
        "aeb6569c-f8fb-4950-ac60-24ca2bbe2e52" to "Crescendo 2300",
        "c80dbd9a-533f-4a17-b941-1a2f1c7cedff" to "Crescendo 3000 Contactless",
        "2a55aee6-27cb-42c0-bc6e-04efe999e88a" to "Crescendo 4000",
        "0b8b05a4-ebd4-4b0b-8f5f-33d7b6e606ab" to "Crescendo 4000",
        "8eec9bf9-486c-46da-9a67-1fbb4f66b9ed" to "Crescendo 4000 FIPS",
        "aa79f476-ea00-417e-9628-1e8365123922" to "Crescendo 4000 FIDO",
        "692db549-7ae5-44d5-a1e5-dd20a493b723" to "Crescendo Key",
        "7991798a-a7f3-487f-98c0-3faf7a458a04" to "Crescendo Key V3",
        "87c13177-85d6-40ac-8c61-fe7ab3de9dfb" to "Crescendo Key V3",
        "13ac47cf-1d78-4fd5-9060-aedaabacf826" to "Crescendo Key V3 – Enterprise Edition",
        "54d9fee8-e621-4291-8b18-7157b99c5bec" to "HID Crescendo Enabled",
        "c4ddaf11-3032-4e77-b3b9-3a340369b9ad" to "HID Crescendo Fusion"
    )

    fun isStatus9000(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return sw1 == 0x90 && sw2 == 0x00
    }

    /**
     * Extracts serial number from GET DATA 9F7F response.
     * Takes bytes [3-6]-[19-20]-[15-18] (0-based inclusive indices).
     * Example: "4790D600-3617-33187821"
     */
    fun extractSerialNumber(response: ByteArray): String? {
        if (response.size < 21) return null
        val part1 = response.sliceArray(3..6).toHex()
        val part2 = response.sliceArray(19..20).toHex()
        val part3 = response.sliceArray(15..18).toHex()
        return "$part1-$part2-$part3"
    }

    /**
     * Parses the response to the SELECT command as ASN.1 and takes tag 6F/A5/01.
     * Value format: 1 byte family, 1 byte major, 1 byte minor, 1 byte rev, 2 bytes build.
     * Example: "100400000127" -> "4.0.0.295"
     */
    fun extractAppletVersion(response: ByteArray): String? {
        val data = if (isStatus9000(response)) {
            response.copyOfRange(0, response.size - 2)
        } else {
            response
        }

        val rootTlvs = parseTlvs(data)
        var tag01: Tlv? = null
        for (root in rootTlvs) {
            tag01 = root.find(0x6F, 0xA5, 0x01)
            if (tag01 != null) break
        }

        if (tag01 == null) {
            for (root in rootTlvs) {
                if (root.tag == 0xA5) {
                    tag01 = root.find(0xA5, 0x01)
                    if (tag01 != null) break
                }
            }
        }

        val value = tag01?.value ?: return null
        if (value.size < 6) return null

        val major = value[1].toInt() and 0xFF
        val minor = value[2].toInt() and 0xFF
        val rev = value[3].toInt() and 0xFF
        val build = ((value[4].toInt() and 0xFF) shl 8) or (value[5].toInt() and 0xFF)

        return "$major.$minor.$rev.$build"
    }

    /**
     * Extracts the AAGUID from an authenticatorGetInfo response.
     * The response starts with 0x00 (CTAP status success) and ends with 0x9000.
     * The inner payload is a CBOR map with element '03' containing a 16-byte byte string.
     * Returns formatted UUID lowercase string, e.g. "7991798a-a7f3-487f-98c0-3faf7a458a04".
     */
    fun extractAaguid(getInfoResponse: ByteArray): String? {
        if (!isStatus9000(getInfoResponse)) return null
        if (getInfoResponse.size < 4) return null
        if (getInfoResponse[0] != 0x00.toByte()) return null

        // Strip initial 0x00 status byte and last 2 status bytes (0x9000)
        val cborData = getInfoResponse.copyOfRange(1, getInfoResponse.size - 2)

        val root = try {
            CborDecoder(cborData).decode()
        } catch (_: Exception) {
            return null
        }

        if (root !is CborValue.MapValue) return null

        // Locate element 0x03 (aaguid)
        for ((key, value) in root.entries) {
            val isKey3 = (key is CborValue.Integer && key.value == 3L)
            if (isKey3 && value is CborValue.ByteString) {
                if (value.value.size == 16) {
                    return formatAaguid(value.value)
                }
            }
        }
        return null
    }

    /**
     * Resolves product name from AAGUID, or returns "Unknown Crescendo Device".
     */
    fun resolveDeviceName(aaguid: String?): String {
        if (aaguid == null) return "Unknown Crescendo Device"
        val normalized = aaguid.lowercase().trim()
        return CRESCENDO_AAGUID_MAP[normalized] ?: "Unknown Crescendo Device"
    }

    fun formatAaguid(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return if (hex.length == 32) {
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        } else {
            hex
        }
    }

    /**
     * Parses raw bytes into a list of BER-TLVs.
     */
    fun parseTlvs(data: ByteArray): List<Tlv> {
        val list = mutableListOf<Tlv>()
        var offset = 0
        while (offset < data.size) {
            if (offset >= data.size) break

            var tag = data[offset].toInt() and 0xFF
            offset++
            if ((tag and 0x1F) == 0x1F) {
                while (offset < data.size) {
                    val nextByte = data[offset].toInt() and 0xFF
                    tag = (tag shl 8) or nextByte
                    offset++
                    if ((nextByte and 0x80) == 0) break
                }
            }

            if (offset >= data.size) break

            var len = data[offset].toInt() and 0xFF
            offset++
            if (len == 0x81) {
                if (offset >= data.size) break
                len = data[offset].toInt() and 0xFF
                offset++
            } else if (len == 0x82) {
                if (offset + 1 >= data.size) break
                len = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
            } else if (len > 0x82) {
                val numBytes = len and 0x7F
                len = 0
                for (i in 0 until numBytes) {
                    if (offset >= data.size) break
                    len = (len shl 8) or (data[offset].toInt() and 0xFF)
                    offset++
                }
            }

            val valueEnd = minOf(offset + len, data.size)
            val value = data.copyOfRange(offset, valueEnd)
            offset = valueEnd

            val firstTagByte = if (tag > 0xFF) (tag shr 8) else tag
            val isConstructed = (firstTagByte and 0x20) != 0

            val children = if (isConstructed) {
                parseTlvs(value)
            } else {
                emptyList()
            }

            list.add(Tlv(tag, value, children))
        }
        return list
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
