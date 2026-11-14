package com.switchhaptics.extractor.switchrom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Traverses Nintendo Switch RomFS directory and file metadata tables.
 */
class RomFsReader(private val nca: NcaReader) {

    data class RomFsFile(
        val path: String,
        val dataOffsetInFileRegion: Long,
        val size: Long
    )

    private val dirMeta: ByteArray
    private val fileMeta: ByteArray
    private val fileDataBaseOffset: Long

    init {
        val header = ByteBuffer.wrap(nca.readRomFs(0L, 0x50)).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = header.getLong(0x00)
        require(headerSize == 0x50L) { "无效的 RomFS 头部大小：$headerSize" }

        val dirMetaOffset = header.getLong(0x18)
        val dirMetaSize = header.getLong(0x20).toInt()
        val fileMetaOffset = header.getLong(0x38)
        val fileMetaSize = header.getLong(0x40).toInt()
        fileDataBaseOffset = header.getLong(0x48)

        dirMeta = nca.readRomFs(dirMetaOffset, dirMetaSize)
        fileMeta = nca.readRomFs(fileMetaOffset, fileMetaSize)
    }

    fun listAllFiles(): List<RomFsFile> {
        val result = mutableListOf<RomFsFile>()
        val dirBuf = ByteBuffer.wrap(dirMeta).order(ByteOrder.LITTLE_ENDIAN)
        val fileBuf = ByteBuffer.wrap(fileMeta).order(ByteOrder.LITTLE_ENDIAN)
        traverseDirectory(dirOffset = 0, currentPath = "", dirBuf = dirBuf, fileBuf = fileBuf, out = result)
        return result
    }

    fun readFile(file: RomFsFile): ByteArray {
        require(file.size <= Int.MAX_VALUE) { "文件过大无法一次性读入内存：${file.path}" }
        return nca.readRomFs(fileDataBaseOffset + file.dataOffsetInFileRegion, file.size.toInt())
    }

    fun readFileSlice(file: RomFsFile, offsetInFile: Long, length: Int): ByteArray {
        if (offsetInFile < 0 || offsetInFile >= file.size || length <= 0) return ByteArray(0)
        val actualLen = minOf(length.toLong(), file.size - offsetInFile).toInt()
        return nca.readRomFs(fileDataBaseOffset + file.dataOffsetInFileRegion + offsetInFile, actualLen)
    }

    private fun traverseDirectory(
        dirOffset: Int,
        currentPath: String,
        dirBuf: ByteBuffer,
        fileBuf: ByteBuffer,
        out: MutableList<RomFsFile>
    ) {
        if (dirOffset < 0 || dirOffset + 0x18 > dirMeta.size) return

        // 0x00: parent, 0x04: sibling, 0x08: childDir, 0x0C: firstFile, 0x10: hashNext, 0x14: nameLen
        val siblingOffset = dirBuf.getInt(dirOffset + 0x04)
        val firstChildDirOffset = dirBuf.getInt(dirOffset + 0x08)
        val firstFileOffset = dirBuf.getInt(dirOffset + 0x0C)
        val nameLen = dirBuf.getInt(dirOffset + 0x14)

        val dirName = if (nameLen > 0) {
            String(dirMeta, dirOffset + 0x18, nameLen, Charsets.UTF_8)
        } else {
            ""
        }
        val fullDirPath = when {
            currentPath.isEmpty() && dirName.isEmpty() -> ""
            currentPath.isEmpty() -> "/$dirName"
            dirName.isEmpty() -> currentPath
            else -> "$currentPath/$dirName"
        }

        // Collect all files in this directory
        var fileOff = firstFileOffset
        while (fileOff >= 0 && fileOff + 0x20 <= fileMeta.size) {
            val nextSiblingFile = fileBuf.getInt(fileOff + 0x04)
            val dataOffset = fileBuf.getLong(fileOff + 0x08)
            val dataSize = fileBuf.getLong(fileOff + 0x10)
            val fNameLen = fileBuf.getInt(fileOff + 0x1C)
            val fName = String(fileMeta, fileOff + 0x20, fNameLen, Charsets.UTF_8)
            out.add(
                RomFsFile(
                    path = "$fullDirPath/$fName",
                    dataOffsetInFileRegion = dataOffset,
                    size = dataSize
                )
            )
            fileOff = nextSiblingFile
        }

        // Recurse into child directory
        if (firstChildDirOffset >= 0) {
            traverseDirectory(firstChildDirOffset, fullDirPath, dirBuf, fileBuf, out)
        }

        // Continue to sibling directory (skip root's sibling)
        if (dirOffset != 0 && siblingOffset >= 0) {
            traverseDirectory(siblingOffset, currentPath, dirBuf, fileBuf, out)
        }
    }
}
