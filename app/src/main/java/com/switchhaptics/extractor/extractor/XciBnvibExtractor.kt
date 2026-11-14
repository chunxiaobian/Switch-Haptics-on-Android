package com.switchhaptics.extractor.extractor

import android.content.Context
import android.net.Uri
import com.switchhaptics.extractor.crypto.ProdKeys
import com.switchhaptics.extractor.haptics.BnvibParser
import com.switchhaptics.extractor.switchrom.NcaReader
import com.switchhaptics.extractor.switchrom.Pfs0Reader
import com.switchhaptics.extractor.switchrom.RomFsReader
import com.switchhaptics.extractor.switchrom.SarcExtractor
import com.switchhaptics.extractor.switchrom.XciReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class ExtractedBnvibItem(
    val id: String,
    val fileName: String,
    val sourcePath: String,
    val savedFile: File,
    val sizeBytes: Int,
    val info: BnvibParser.BnvibInfo
)

data class ExtractionProgress(
    val stage: String,
    val progress: Float
)

/**
 * Orchestrates Stage 1: XCI -> NCA -> RomFS -> SARC/SZS -> `.bnvib` extraction on Android.
 */
class XciBnvibExtractor(private val context: Context) {

    suspend fun extractFromXci(
        xciUri: Uri,
        keysUri: Uri,
        onProgress: (ExtractionProgress) -> Unit
    ): List<ExtractedBnvibItem> = withContext(Dispatchers.IO) {
        onProgress(ExtractionProgress("正在读取 prod.keys...", 0.05f))
        val keys = context.contentResolver.openInputStream(keysUri)?.use {
            ProdKeys.parse(it)
        } ?: throw IllegalStateException("无法打开 prod.keys 文件")

        val pfd = context.contentResolver.openFileDescriptor(xciUri, "r")
            ?: throw IllegalStateException("无法打开 XCI 文件描述符")

        val extractedItems = mutableListOf<ExtractedBnvibItem>()

        pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { fis ->
                val channel = fis.channel
                onProgress(ExtractionProgress("正在识别镜像格式 (XCI / NSP)...", 0.12f))

                val (ncaEntries, titleKeyProvider) = readContainerNcas(channel)
                require(ncaEntries.isNotEmpty()) { "镜像中未找到任何 NCA 文件" }

                // Sort largest first so Program NCA is checked early
                val sortedNcas = ncaEntries.sortedByDescending { it.size }
                var foundProgram = false
                var lastError: Exception? = null

                for (ncaEntry in sortedNcas) {
                    val ncaReader = try {
                        NcaReader(
                            channel = channel,
                            ncaOffsetInContainer = ncaEntry.offset,
                            ncaSize = ncaEntry.size,
                            keys = keys,
                            encryptedTitleKeyProvider = titleKeyProvider
                        )
                    } catch (e: Exception) {
                        lastError = e
                        continue
                    }

                    // 0x00 = Program NCA
                    if (ncaReader.contentType != 0x00) continue
                    foundProgram = true

                    onProgress(ExtractionProgress("正在读取 Program NCA (${ncaReader.titleId}) 的 RomFS...", 0.25f))
                    val romFs = RomFsReader(ncaReader)
                    val allFiles = romFs.listAllFiles()

                    val outputDir = File(context.filesDir, "extracted_bnvib/${ncaReader.titleId}").apply {
                        deleteRecursively()
                        mkdirs()
                    }

                    val candidateFiles = allFiles.filter { file ->
                        val lower = file.path.lowercase()
                        lower.endsWith(".bnvib") ||
                            lower.endsWith(".sarc") ||
                            lower.endsWith(".szs") ||
                            lower.endsWith(".pack") ||
                            lower.endsWith(".arc")
                    }

                    val total = candidateFiles.size.coerceAtLeast(1)
                    var lastProgressMs = 0L
                    candidateFiles.forEachIndexed { index, romFile ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs >= 60L || index == total - 1) {
                            lastProgressMs = now
                            val frac = 0.30f + 0.68f * (index.toFloat() / total)
                            onProgress(
                                ExtractionProgress(
                                    "扫描资源 (${index + 1}/$total): ${romFile.path.substringAfterLast('/')}",
                                    frac
                                )
                            )
                        }

                        val lower = romFile.path.lowercase()
                        if (lower.endsWith(".bnvib")) {
                            val bytes = romFs.readFile(romFile)
                            saveAndRecord(
                                fileName = romFile.path.substringAfterLast('/'),
                                sourcePath = romFile.path,
                                bytes = bytes,
                                outputDir = outputDir,
                                out = extractedItems
                            )
                        } else if (romFile.size in 20..(64 * 1024 * 1024)) {
                            // Probe first 20 bytes before allocating memory for the entire archive
                            val header20 = romFs.readFileSlice(romFile, 0L, 20)
                            val isYaz0 = SarcExtractor.isYaz0(header20)
                            val isSarc = !isYaz0 && SarcExtractor.isSarc(header20)
                            if (!isYaz0 && !isSarc) return@forEachIndexed

                            if (isYaz0) {
                                val uncompressedSize = ByteBuffer.wrap(header20, 4, 4)
                                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                                    .int
                                if (uncompressedSize <= 0 || uncompressedSize > 128 * 1024 * 1024) {
                                    return@forEachIndexed
                                }
                            }

                            try {
                                val archiveBytes = romFs.readFile(romFile)
                                val innerEntries = SarcExtractor.extractBnvibEntries(archiveBytes)
                                for (entry in innerEntries) {
                                    saveAndRecord(
                                        fileName = entry.name.substringAfterLast('/'),
                                        sourcePath = "${romFile.path} -> ${entry.name}",
                                        bytes = entry.data,
                                        outputDir = outputDir,
                                        out = extractedItems
                                    )
                                }
                            } catch (_: OutOfMemoryError) {
                                // Skip oversized archive without crashing the scan
                            }
                        }
                    }
                    break
                }

                if (!foundProgram) {
                    val detail = lastError?.message?.let { "（原因：$it）" } ?: ""
                    throw IllegalStateException("未在镜像中找到可解密的 Program NCA（含 RomFS）$detail")
                }
            }
        }

        onProgress(ExtractionProgress("提取完成，共找到 ${extractedItems.size} 个 .bnvib 文件", 1.0f))
        extractedItems.sortedBy { it.fileName.lowercase() }
    }

    private data class ContainerNcaEntry(
        val name: String,
        val offset: Long,
        val size: Long
    )

    private fun readContainerNcas(
        channel: FileChannel
    ): Pair<List<ContainerNcaEntry>, ((String) -> ByteArray?)?> {
        val probe = ByteBuffer.allocate(0x200)
        var pos = 0L
        while (probe.hasRemaining()) {
            val r = channel.read(probe, pos)
            if (r <= 0) break
            pos += r
        }
        val bytes = probe.array()
        val pfs0Magic = if (bytes.size >= 4) String(bytes, 0, 4, Charsets.US_ASCII) else ""
        val xciMagic = if (bytes.size >= 0x104) String(bytes, 0x100, 4, Charsets.US_ASCII) else ""

        return when {
            pfs0Magic == "PFS0" -> {
                val pfs0 = Pfs0Reader(channel)
                val entries = pfs0.listEntries()
                val tikMap = entries
                    .filter { it.name.endsWith(".tik", ignoreCase = true) }
                    .associateBy { it.name.substringBeforeLast('.').lowercase() }
                val ncas = entries
                    .filter { it.name.endsWith(".nca", ignoreCase = true) }
                    .map { ContainerNcaEntry(it.name, it.offset, it.size) }
                val provider: (String) -> ByteArray? = { rightsIdHex ->
                    val tik = tikMap[rightsIdHex.lowercase()]
                        ?: tikMap.values.firstOrNull()
                    tik?.let { pfs0.readEncryptedTitleKey(it) }
                }
                ncas to provider
            }
            xciMagic == "HEAD" -> {
                val xci = XciReader(channel)
                val ncas = xci.listSecureNcaEntries()
                    .map { ContainerNcaEntry(it.name, it.offset, it.size) }
                ncas to null
            }
            else -> throw IllegalArgumentException("无法识别的镜像格式（既非 XCI HEAD 也非 NSP PFS0）")
        }
    }

    private fun saveAndRecord(
        fileName: String,
        sourcePath: String,
        bytes: ByteArray,
        outputDir: File,
        out: MutableList<ExtractedBnvibItem>
    ) {
        val safeName = "${out.size}_$fileName".replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(outputDir, safeName)
        targetFile.writeBytes(bytes)

        val info = BnvibParser.parse(bytes, includeSamples = false)
        out.add(
            ExtractedBnvibItem(
                id = "${out.size}_$sourcePath",
                fileName = fileName,
                sourcePath = sourcePath,
                savedFile = targetFile,
                sizeBytes = bytes.size,
                info = info
            )
        )
    }
}
