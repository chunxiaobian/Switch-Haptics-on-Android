package com.switchhaptics.extractor.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives matching Nintendo Switch NCA encryption:
 * - Custom AES-128-XTS (Big-Endian sector tweak)
 * - AES-128-ECB (key area unwrapping)
 * - AES-128-CTR (RomFS section decryption with random-access offset support)
 */
object NintendoAes {

    fun decryptXts(
        key32: ByteArray,
        data: ByteArray,
        sectorStart: Long = 0L,
        sectorSize: Int = 0x200
    ): ByteArray {
        require(key32.size == 32) { "AES-128-XTS requires a 32-byte key" }
        require(data.size % sectorSize == 0) { "Data size must be a multiple of sector size" }

        val dataKey = SecretKeySpec(key32.copyOfRange(0, 16), "AES")
        val tweakKey = SecretKeySpec(key32.copyOfRange(16, 32), "AES")

        val dataCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, dataKey)
        }
        val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, tweakKey)
        }

        val out = ByteArray(data.size)
        val sectorCount = data.size / sectorSize
        val block = ByteArray(16)

        for (s in 0 until sectorCount) {
            val sectorIndex = sectorStart + s
            // Nintendo NCA uses a Big-Endian 64-bit sector index in bytes 8..15
            val rawTweak = ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(0L)
                .putLong(sectorIndex)
                .array()
            val tweak = tweakCipher.doFinal(rawTweak)

            val sectorOffset = s * sectorSize
            for (b in 0 until sectorSize step 16) {
                val pos = sectorOffset + b
                for (i in 0 until 16) {
                    block[i] = (data[pos + i].toInt() xor tweak[i].toInt()).toByte()
                }
                val decrypted = dataCipher.doFinal(block)
                for (i in 0 until 16) {
                    out[pos + i] = (decrypted[i].toInt() xor tweak[i].toInt()).toByte()
                }
                multiplyByX(tweak)
            }
        }
        return out
    }

    fun decryptEcb(key16: ByteArray, data: ByteArray): ByteArray {
        require(key16.size == 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * Decrypts a slice at [ncaOffset] (must be 16-byte aligned for direct CTR block alignment,
     * or handles arbitrary alignment cleanly).
     */
    fun decryptCtrAtOffset(
        key16: ByteArray,
        sectionCtrUpper8BigEndian: ByteArray,
        ncaOffset: Long,
        data: ByteArray
    ): ByteArray {
        require(key16.size == 16)
        require(sectionCtrUpper8BigEndian.size == 8)

        val blockIndex = ncaOffset ushr 4
        val intraBlockOffset = (ncaOffset and 0xFL).toInt()

        val iv = ByteArray(16)
        System.arraycopy(sectionCtrUpper8BigEndian, 0, iv, 0, 8)
        ByteBuffer.wrap(iv, 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(blockIndex)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), IvParameterSpec(iv))

        if (intraBlockOffset == 0) {
            return cipher.doFinal(data)
        }

        // Pad front to align with 16-byte AES block, then strip padding
        val padded = ByteArray(intraBlockOffset + data.size)
        System.arraycopy(data, 0, padded, intraBlockOffset, data.size)
        val decrypted = cipher.doFinal(padded)
        return decrypted.copyOfRange(intraBlockOffset, decrypted.size)
    }

    private fun multiplyByX(tweak: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val b = tweak[i].toInt() and 0xFF
            val nextCarry = (b ushr 7) and 1
            tweak[i] = (((b shl 1) or carry) and 0xFF).toByte()
            carry = nextCarry
        }
        if (carry != 0) {
            tweak[0] = (tweak[0].toInt() xor 0x87).toByte()
        }
    }
}
