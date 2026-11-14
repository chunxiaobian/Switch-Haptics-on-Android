package com.switchhaptics.extractor.switchrom

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads Nintendo Switch PFS0 / NSP (Partition File System) containers via a FileChannel.
 */
class Pfs0Reader(private val channel: FileChannel) {

    data class Pfs0Entry(
        val name: String,
        val offset: Long,
        val size: Long
    )

    fun listEntries(): List<Pfs0Entry> {
        val header = readBytes(0L, 0x10)
        val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        require(magic == "PFS0") { "无效的 NSP/PFS0 文件：未找到 PFS0 魔数（实际为 $magic）" }

        val fileCount = headerBuf.getInt(4)
        val stringTableSize = headerBuf.getInt(8)

        val entriesSize = fileCount * 0x18
        val tableBytes = readBytes(0x10L, entriesSize + stringTableSize)
        val tableBuf = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)

        val dataStart = 0x10L + entriesSize + stringTableSize
        val result = ArrayList<Pfs0Entry>(fileCount)

        for (i in 0 until fileCount) {
            val base = i * 0x18
            tableBuf.position(base)
            val fileOffset = tableBuf.long
            val fileSize = tableBuf.long
            val nameOffset = tableBuf.int

            val nameStart = entriesSize + nameOffset
            var nameEnd = nameStart
            while (nameEnd < tableBytes.size && tableBytes[nameEnd].toInt() != 0) {
                nameEnd++
            }
            val name = String(tableBytes, nameStart, nameEnd - nameStart, Charsets.UTF_8)

            result.add(
                Pfs0Entry(
                    name = name,
                    offset = dataStart + fileOffset,
                    size = fileSize
                )
            )
        }
        return result
    }

    /**
     * Reads the 16-byte encrypted TitleKey at offset 0x180 inside a `.tik` file entry.
     */
    fun readEncryptedTitleKey(tikEntry: Pfs0Entry): ByteArray {
        require(tikEntry.size >= 0x190) { "无效的 .tik 文件大小：${tikEntry.size}" }
        return readBytes(tikEntry.offset + 0x180L, 16)
    }

    private fun readBytes(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val read = channel.read(buf, pos)
            if (read <= 0) break
            pos += read
        }
        if (buf.hasRemaining()) {
            throw IllegalStateException("读取 NSP 失败：偏移 0x${offset.toString(16)} 处数据不足")
        }
        return buf.array()
    }
}
