package com.switchhaptics.extractor.crypto

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses Nintendo Switch `prod.keys` files and holds keys required to decrypt XCI/NCA headers and RomFS.
 */
data class ProdKeys(
    val headerKey: ByteArray,
    val keyAreaKeysApplication: Map<Int, ByteArray>,
    val titleKeks: Map<Int, ByteArray>
) {
    val totalKeyCount: Int
        get() = 1 + keyAreaKeysApplication.size + titleKeks.size

    companion object {
        fun parse(inputStream: InputStream): ProdKeys {
            var headerKey: ByteArray? = null
            val keyAreaApp = mutableMapOf<Int, ByteArray>()
            val titleKeks = mutableMapOf<Int, ByteArray>()

            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lineSequence().forEach { rawLine ->
                    val line = rawLine.substringBefore('#').trim()
                    if (line.isEmpty() || !line.contains('=')) return@forEach

                    val parts = line.split('=', limit = 2)
                    val name = parts[0].trim().lowercase()
                    val hex = parts[1].trim()

                    when {
                        name == "header_key" && hex.length == 64 -> {
                            headerKey = hexToBytes(hex)
                        }
                        name.startsWith("key_area_key_application_") && hex.length == 32 -> {
                            val idx = name.removePrefix("key_area_key_application_").toIntOrNull(16)
                            if (idx != null) keyAreaApp[idx] = hexToBytes(hex)
                        }
                        name.startsWith("titlekek_") && hex.length == 32 -> {
                            val idx = name.removePrefix("titlekek_").toIntOrNull(16)
                            if (idx != null) titleKeks[idx] = hexToBytes(hex)
                        }
                    }
                }
            }

            val resolvedHeaderKey = headerKey
                ?: throw IllegalArgumentException("prod.keys 缺少 header_key，无法解密 NCA 头部")
            if (keyAreaApp.isEmpty()) {
                throw IllegalArgumentException("prod.keys 缺少 key_area_key_application_XX，无法解密游戏分区")
            }

            return ProdKeys(
                headerKey = resolvedHeaderKey,
                keyAreaKeysApplication = keyAreaApp,
                titleKeks = titleKeks
            )
        }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length % 2 == 0) { "Invalid hex length: ${clean.length}" }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
