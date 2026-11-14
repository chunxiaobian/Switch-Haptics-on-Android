package com.switchhaptics.extractor.switchrom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decompresses Yaz0 (.szs) streams and unpacks Nintendo SARC archives to locate embedded `.bnvib` files.
 */
object SarcExtractor {

    data class SarcEntry(
        val name: String,
        val data: ByteArray
    )

    fun extractBnvibEntries(rawBytes: ByteArray): List<SarcEntry> {
        val sarcBytes = if (isYaz0(rawBytes)) decompressYaz0(rawBytes) else rawBytes
        if (!isSarc(sarcBytes)) return emptyList()
        return parseSarc(sarcBytes, onlyBnvib = true)
    }

    fun isYaz0(data: ByteArray): Boolean =
        data.size >= 16 &&
            data[0] == 'Y'.code.toByte() &&
            data[1] == 'a'.code.toByte() &&
            data[2] == 'z'.code.toByte() &&
            data[3] == '0'.code.toByte()

    fun isSarc(data: ByteArray): Boolean =
        data.size >= 20 &&
            data[0] == 'S'.code.toByte() &&
            data[1] == 'A'.code.toByte() &&
            data[2] == 'R'.code.toByte() &&
            data[3] == 'C'.code.toByte()

    fun decompressYaz0(src: ByteArray): ByteArray {
        val uncompressedSize = ByteBuffer.wrap(src, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        if (uncompressedSize <= 0 || uncompressedSize > 256 * 1024 * 1024) {
            return ByteArray(0)
        }
        val dst = ByteArray(uncompressedSize)

        var srcPos = 16
        var dstPos = 0
        var validBitCount = 0
        var currCodeByte = 0

        while (dstPos < uncompressedSize && srcPos < src.size) {
            if (validBitCount == 0) {
                currCodeByte = src[srcPos++].toInt() and 0xFF
                validBitCount = 8
            }

            if ((currCodeByte and 0x80) != 0) {
                dst[dstPos++] = src[srcPos++]
            } else {
                if (srcPos + 1 >= src.size) break
                val byte1 = src[srcPos++].toInt() and 0xFF
                val byte2 = src[srcPos++].toInt() and 0xFF
                val dist = ((byte1 and 0x0F) shl 8) or byte2
                var copySource = dstPos - (dist + 1)

                var numBytes = byte1 ushr 4
                numBytes = if (numBytes == 0) {
                    (src[srcPos++].toInt() and 0xFF) + 0x12
                } else {
                    numBytes + 2
                }

                for (i in 0 until numBytes) {
                    if (dstPos >= uncompressedSize) break
                    dst[dstPos++] = if (copySource in 0 until dstPos) dst[copySource++] else 0
                }
            }
            currCodeByte = currCodeByte shl 1
            validBitCount--
        }
        return dst
    }

    private fun parseSarc(data: ByteArray, onlyBnvib: Boolean = true): List<SarcEntry> {
        val bom = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val order = if (bom == 0xFEFF) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buf = ByteBuffer.wrap(data).order(order)

        val headerSize = buf.getShort(4).toInt() and 0xFFFF
        val dataOffset = buf.getInt(12)

        // SFAT starts right after SARC header
        val sfatMagic = String(data, headerSize, 4, Charsets.US_ASCII)
        if (sfatMagic != "SFAT") return emptyList()

        val sfatHeaderSize = buf.getShort(headerSize + 4).toInt() and 0xFFFF
        val nodeCount = buf.getShort(headerSize + 6).toInt() and 0xFFFF
        val nodesStart = headerSize + sfatHeaderSize

        val sfntStart = nodesStart + nodeCount * 16
        if (sfntStart + 8 > data.size) return emptyList()
        val sfntMagic = String(data, sfntStart, 4, Charsets.US_ASCII)
        if (sfntMagic != "SFNT") return emptyList()

        val sfntHeaderSize = buf.getShort(sfntStart + 4).toInt() and 0xFFFF
        val stringTableStart = sfntStart + sfntHeaderSize

        val entries = ArrayList<SarcEntry>(nodeCount)
        for (i in 0 until nodeCount) {
            val nodeOff = nodesStart + i * 16
            val fileAttributes = buf.getInt(nodeOff + 4)
            val nodeStart = buf.getInt(nodeOff + 8)
            val nodeEnd = buf.getInt(nodeOff + 12)

            val hasName = (fileAttributes and 0x01000000) != 0
            val name = if (hasName) {
                val nameOffset = (fileAttributes and 0x00FFFFFF) * 4
                val strPos = stringTableStart + nameOffset
                var strEnd = strPos
                while (strEnd < data.size && data[strEnd].toInt() != 0) strEnd++
                String(data, strPos, strEnd - strPos, Charsets.UTF_8)
            } else {
                "unnamed_$i.bin"
            }

            if (onlyBnvib && !name.endsWith(".bnvib", ignoreCase = true)) {
                continue
            }

            val absStart = dataOffset + nodeStart
            val absEnd = dataOffset + nodeEnd
            if (absStart >= 0 && absEnd <= data.size && absEnd >= absStart) {
                entries.add(SarcEntry(name, data.copyOfRange(absStart, absEnd)))
            }
        }
        return entries
    }
}
