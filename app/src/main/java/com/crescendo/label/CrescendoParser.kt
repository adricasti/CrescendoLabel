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
        
        // If current TLV is the target tag
        if (this.tag == currentTag) {
            if (remaining.isEmpty()) return this
            for (child in children) {
                val found = child.find(*remaining)
                if (found != null) return found
            }
        } else {
            // Search children
            for (child in children) {
                val found = child.find(*tagPath)
                if (found != null) return found
            }
        }
        return null
    }
}

object CrescendoParser {

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
        // Need at least 21 bytes (indices 0..20)
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
        // Strip 9000 status word if present at the end
        val data = if (isStatus9000(response)) {
            response.copyOfRange(0, response.size - 2)
        } else {
            response
        }

        val rootTlvs = parseTlvs(data)
        // Find tag 0x6F -> 0xA5 -> 0x01
        var tag01: Tlv? = null
        for (root in rootTlvs) {
            tag01 = root.find(0x6F, 0xA5, 0x01)
            if (tag01 != null) break
        }

        // If root was already 0x6F or direct search
        if (tag01 == null) {
            for (root in rootTlvs) {
                if (root.tag == 0xA5) {
                    tag01 = root.find(0xA5, 0x01)
                    if (tag01 != null) break
                }
            }
        }

        val value = tag01?.value ?: return null
        // Needs at least 6 bytes: family(1) + major(1) + minor(1) + rev(1) + build(2)
        if (value.size < 6) return null

        val major = value[1].toInt() and 0xFF
        val minor = value[2].toInt() and 0xFF
        val rev = value[3].toInt() and 0xFF
        val build = ((value[4].toInt() and 0xFF) shl 8) or (value[5].toInt() and 0xFF)

        return "$major.$minor.$rev.$build"
    }

    /**
     * Parses raw bytes into a list of BER-TLVs.
     */
    fun parseTlvs(data: ByteArray): List<Tlv> {
        val list = mutableListOf<Tlv>()
        var offset = 0
        while (offset < data.size) {
            if (offset >= data.size) break

            // Parse tag
            var tag = data[offset].toInt() and 0xFF
            offset++
            if ((tag and 0x1F) == 0x1F) {
                // Multi-byte tag
                while (offset < data.size) {
                    val nextByte = data[offset].toInt() and 0xFF
                    tag = (tag shl 8) or nextByte
                    offset++
                    if ((nextByte and 0x80) == 0) break
                }
            }

            if (offset >= data.size) break

            // Parse length
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
                // Larger length: read (len - 0x80) bytes
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

            // Check if constructed tag
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
