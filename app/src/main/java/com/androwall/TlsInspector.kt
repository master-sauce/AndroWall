package com.androwall

object TlsInspector {
    /** Extracts SNI hostname from TLS ClientHello. Null = incomplete / no SNI / not TLS. */
    fun extractSni(data: ByteArray): String? = runCatching {
        if (data.size < 5) return null
        if (data[0].toInt() and 0xFF != 0x16) return null   // must be Handshake record
        val recordLen = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (data.size < 5 + recordLen) return null           // incomplete record

        var pos = 5
        if (pos >= data.size || data[pos].toInt() and 0xFF != 0x01) return null  // ClientHello
        pos += 4       // skip handshake type + 3-byte length
        pos += 2       // skip client version
        pos += 32      // skip random

        if (pos >= data.size) return null
        val sessionIdLen = data[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen

        if (pos + 2 > data.size) return null
        val cipherLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2 + cipherLen

        if (pos >= data.size) return null
        val compLen = data[pos].toInt() and 0xFF
        pos += 1 + compLen

        if (pos + 2 > data.size) return null
        val extTotal = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2

        val extEnd = pos + extTotal
        while (pos + 4 <= extEnd && pos + 4 <= data.size) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extLen  = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (extType == 0x0000 && pos + 2 <= data.size) {  // server_name
                pos += 2  // skip list length
                if (pos >= data.size || data[pos].toInt() and 0xFF != 0x00) break
                pos++     // skip name type (0x00 = host_name)
                if (pos + 2 > data.size) break
                val nameLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2
                if (pos + nameLen > data.size) break
                return String(data, pos, nameLen, Charsets.US_ASCII)
            }
            pos += extLen
        }
        null
    }.getOrNull()
}