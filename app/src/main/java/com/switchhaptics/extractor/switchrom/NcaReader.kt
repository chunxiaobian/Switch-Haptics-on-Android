package com.switchhaptics.extractor.switchrom

import com.switchhaptics.extractor.crypto.NintendoAes
import com.switchhaptics.extractor.crypto.ProdKeys
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Parses encrypted NCA3/NCA2 headers and provides random-access decrypted reads
 * over the Program NCA's RomFS data section.
 */
class NcaReader(
    private val channel: FileChannel,
    private val ncaOffsetInContainer: Long,
    private val ncaSize: Long,
    private val keys: ProdKeys,
    private val encryptedTitleKeyProvider: ((rightsIdHex: String) -> ByteArray?)? = null
) {
    val contentType: Int
    val titleId: String
    val romFsSize: Long

    private val romFsOffsetInNca: Long
    private val ctrKey: ByteArray?
    private val sectionCtrUpper8: ByteArray
    private val encryptionType: Int

    init {
        val encryptedHeader = readRaw(0L, 0xC00)
        val header = NintendoAes.decryptXts(keys.headerKey, encryptedHeader, sectorStart = 0L, sectorSize = 0x200)

        val magic = String(header, 0x200, 4, Charsets.US_ASCII)
        require(magic == "NCA3" || magic == "NCA2") {
            "NCA 头部解密失败（魔数=$magic），请确认 prod.keys 与镜像匹配"
        }

        contentType = header[0x205].toInt() and 0xFF
        val titleIdLong = ByteBuffer.wrap(header, 0x210, 8).order(ByteOrder.LITTLE_ENDIAN).long
        titleId = titleIdLong.toULong().toString(16).padStart(16, '0')

        val keyGenOld = header[0x206].toInt() and 0xFF
        val keyGenNew = header[0x220].toInt() and 0xFF
        val keyGen = maxOf(keyGenOld, keyGenNew)
        val cryptoType = if (keyGen > 0) keyGen - 1 else 0

        val keyAreaIdx = header[0x207].toInt() and 0xFF
        require(keyAreaIdx == 0) { "暂不支持非 Application 类型的 KeyArea ($keyAreaIdx)" }

        val rightsId = header.copyOfRange(0x230, 0x240)
        val hasRightsId = rightsId.any { it.toInt() != 0 }

        val decryptedCtrKey: ByteArray = if (hasRightsId) {
            val rightsIdHex = rightsId.joinToString("") { "%02x".format(it) }
            val encryptedTitleKey = encryptedTitleKeyProvider?.invoke(rightsIdHex)
                ?: throw IllegalStateException("该 NCA 需要 TitleKey (RightsId=$rightsIdHex)，但在容器中未找到对应的 .tik 文件")
            val titleKek = keys.titleKeks[cryptoType]
                ?: throw IllegalStateException("prod.keys 缺少 titlekek_${cryptoType.toString(16).padStart(2, '0')}")
            NintendoAes.decryptEcb(titleKek, encryptedTitleKey)
        } else {
            val kek = keys.keyAreaKeysApplication[cryptoType]
                ?: throw IllegalStateException("prod.keys 缺少 key_area_key_application_${cryptoType.toString(16).padStart(2, '0')}")
            // Key area slot 2 is the AES-CTR key
            val encryptedCtrKey = header.copyOfRange(0x320, 0x330)
            NintendoAes.decryptEcb(kek, encryptedCtrKey)
        }

        // Locate RomFS section among the 4 section table entries
        var foundSectionOffset = -1L
        var foundFsHeaderOffset = -1

        val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            val entryOff = 0x240 + i * 0x10
            val startBlock = hdrBuf.getInt(entryOff).toLong() and 0xFFFFFFFFL
            val endBlock = hdrBuf.getInt(entryOff + 4).toLong() and 0xFFFFFFFFL
            val enabled = header[entryOff + 8].toInt() and 0xFF
            if (enabled == 0 || endBlock <= startBlock) continue

            val fsHeaderOff = 0x400 + i * 0x200
            val fsType = header[fsHeaderOff + 0x02].toInt() and 0xFF
            if (fsType == 0x00) { // 0x00 = RomFS
                foundSectionOffset = startBlock * 0x200L
                foundFsHeaderOffset = fsHeaderOff
                break
            }
        }

        require(foundSectionOffset >= 0 && foundFsHeaderOffset >= 0) {
            "NCA (contentType=$contentType) 中未找到 RomFS 分区"
        }

        encryptionType = header[foundFsHeaderOffset + 0x04].toInt() and 0xFF
        ctrKey = if (encryptionType == 0x01) null else decryptedCtrKey

        // Reverse 8 bytes at FsHeader + 0x140..0x147 for Big-Endian upper 8 bytes of AES-CTR IV
        sectionCtrUpper8 = ByteArray(8) { idx ->
            header[foundFsHeaderOffset + 0x147 - idx]
        }

        // Verify IVFC magic at FsHeader + 0x08; InfoLevelHash starts at +0x14 (maxLayers at +0x14, levels[0] at +0x18)
        val ivfcMagic = String(header, foundFsHeaderOffset + 0x08, 4, Charsets.US_ASCII)
        require(ivfcMagic == "IVFC") { "RomFS 分区缺少 IVFC 校验头（$ivfcMagic）" }

        val maxLayers = hdrBuf.getInt(foundFsHeaderOffset + 0x14)
        val dataLevelIndex = (maxLayers - 2).coerceIn(0, 5)
        val levelOff = foundFsHeaderOffset + 0x18 + dataLevelIndex * 0x18
        val logicalOffset = hdrBuf.getLong(levelOff)
        val hashDataSize = hdrBuf.getLong(levelOff + 8)

        romFsOffsetInNca = foundSectionOffset + logicalOffset
        romFsSize = hashDataSize
    }

    fun readRomFs(offsetInRomFs: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val ncaOffset = romFsOffsetInNca + offsetInRomFs
        val raw = readRaw(ncaOffset, length)
        val key = ctrKey ?: return raw
        if (encryptionType == 0x03) {
            return NintendoAes.decryptCtrAtOffset(key, sectionCtrUpper8, ncaOffset, raw)
        }
        throw UnsupportedOperationException("不支持的 RomFS 加密类型：$encryptionType")
    }

    private fun readRaw(ncaOffset: Long, length: Int): ByteArray {
        require(ncaOffset >= 0 && ncaOffset + length <= ncaSize) {
            "读取越界：ncaOffset=$ncaOffset, length=$length, ncaSize=$ncaSize"
        }
        val buf = ByteBuffer.allocate(length)
        var pos = ncaOffsetInContainer + ncaOffset
        while (buf.hasRemaining()) {
            val read = channel.read(buf, pos)
            if (read <= 0) break
            pos += read
        }
        if (buf.hasRemaining()) {
            throw IllegalStateException("读取 NCA 数据不足")
        }
        return buf.array()
    }
}
