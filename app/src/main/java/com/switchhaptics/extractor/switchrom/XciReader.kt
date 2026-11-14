package com.switchhaptics.extractor.switchrom

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads Nintendo Switch XCI (NX Card Image) files via a FileChannel without loading the image into memory.
 */
class XciReader(private val channel: FileChannel) {

    data class Hfs0Entry(
        val name: String,
        val offset: Long,
        val size: Long
    )

    fun listSecureNcaEntries(): List<Hfs0Entry> {
        val header = readBytes(0L, 0x200)
        val magic = String(header, 0x100, 4, Charsets.US_ASCII)
        require(magic == "HEAD") { "无效的 XCI 文件：未找到 HEAD 魔数（实际为 $magic）" }

        val hfs0Offset = ByteBuffer.wrap(header, 0x130, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long

        val rootPartitions = readHfs0(hfs0Offset)
        val securePartition = rootPartitions.firstOrNull { it.name == "secure" }
            ?: throw IllegalStateException("XCI 中未找到 secure 分区")

        return readHfs0(securePartition.offset).filter { it.name.endsWith(".nca", ignoreCase = true) }
    }

    private fun readHfs0(partitionOffset: Long): List<Hfs0Entry> {
        val headerBuf = ByteBuffer.wrap(readBytes(partitionOffset, 0x10)).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(4)
        headerBuf.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        require(magic == "HFS0") { "无效的 HFS0 分区魔数：$magic" }

        val fileCount = headerBuf.int
        val stringTableSize = headerBuf.int
        headerBuf.int // reserved

        val entriesSize = fileCount * 0x40
        val tableBytes = readBytes(partitionOffset + 0x10, entriesSize + stringTableSize)
        val tableBuf = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)

        val dataStart = partitionOffset + 0x10 + entriesSize + stringTableSize
        val result = ArrayList<Hfs0Entry>(fileCount)

        for (i in 0 until fileCount) {
            val base = i * 0x40
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
                Hfs0Entry(
                    name = name,
                    offset = dataStart + fileOffset,
                    size = fileSize
                )
            )
        }
        return result
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
            throw IllegalStateException("读取 XCI 失败：偏移 0x${offset.toString(16)} 处数据不足")
        }
        return buf.array()
    }
}
