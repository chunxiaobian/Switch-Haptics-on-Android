package com.switchhaptics.extractor.haptics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Parses Nintendo Switch `.bnvib` (Binary NX Vibration) files for metadata inspection and waveform preview.
 */
object BnvibParser {

    enum class VibrationType(val code: Int, val label: String) {
        NORMAL(0x04, "Normal（单次）"),
        LOOP(0x0C, "Loop（循环）"),
        LOOP_WAIT(0x10, "Loop & Wait（间隔循环）"),
        UNKNOWN(-1, "未知类型")
    }

    data class BnvibSample(
        val ampLow: Float,
        val freqLowHz: Float,
        val ampHigh: Float,
        val freqHighHz: Float
    )

    data class BnvibInfo(
        val type: VibrationType,
        val magic: Int,
        val sampleRateMs: Int,
        val sampleCount: Int,
        val durationMs: Int,
        val loopStart: Int? = null,
        val loopEnd: Int? = null,
        val loopWait: Int? = null,
        val samples: List<BnvibSample> = emptyList(),
        val isValid: Boolean = true
    )

    fun parse(data: ByteArray, includeSamples: Boolean = true): BnvibInfo {
        if (data.size < 12) {
            return BnvibInfo(
                type = VibrationType.UNKNOWN,
                magic = 0,
                sampleRateMs = 0,
                sampleCount = 0,
                durationMs = 0,
                isValid = false
            )
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val typeCode = data[0].toInt() and 0xFF
        val magic = data[4].toInt() and 0xFF

        val type = VibrationType.entries.firstOrNull { it.code == typeCode } ?: VibrationType.UNKNOWN
        if (magic != 0x03 || type == VibrationType.UNKNOWN) {
            return BnvibInfo(
                type = type,
                magic = magic,
                sampleRateMs = 0,
                sampleCount = 0,
                durationMs = 0,
                isValid = false
            )
        }

        val sampleRateMs = buf.getShort(0x06).toInt() and 0xFFFF
        var loopStart: Int? = null
        var loopEnd: Int? = null
        var loopWait: Int? = null
        val vibSize: Int
        val samplesOffset: Int

        when (type) {
            VibrationType.NORMAL -> {
                if (data.size < 0x0C) return invalidInfo(type, magic)
                vibSize = buf.getInt(0x08)
                samplesOffset = 0x0C
            }
            VibrationType.LOOP -> {
                if (data.size < 0x14) return invalidInfo(type, magic)
                loopStart = buf.getInt(0x08)
                loopEnd = buf.getInt(0x0C)
                vibSize = buf.getInt(0x10)
                samplesOffset = 0x14
            }
            VibrationType.LOOP_WAIT -> {
                if (data.size < 0x19) return invalidInfo(type, magic)
                loopStart = buf.getInt(0x08)
                loopEnd = buf.getInt(0x0C)
                loopWait = buf.getInt(0x10)
                vibSize = buf.getInt(0x14)
                // Handle both 0x18 (tight) and 0x19 (padded) sample array offsets safely
                samplesOffset = if (0x18 + vibSize <= data.size) 0x18 else 0x19
            }
            VibrationType.UNKNOWN -> return invalidInfo(type, magic)
        }

        val availableBytes = (data.size - samplesOffset).coerceAtLeast(0)
        val actualVibBytes = minOf(vibSize.coerceAtLeast(0), availableBytes)
        val sampleCount = actualVibBytes / 4

        val samples = if (includeSamples) {
            ArrayList<BnvibSample>(sampleCount).apply {
                for (i in 0 until sampleCount) {
                    val pos = samplesOffset + i * 4
                    val ampLow = (data[pos].toInt() and 0xFF) / 255f
                    val freqLowByte = data[pos + 1].toInt() and 0xFF
                    val ampHigh = (data[pos + 2].toInt() and 0xFF) / 255f
                    val freqHighByte = data[pos + 3].toInt() and 0xFF

                    add(
                        BnvibSample(
                            ampLow = ampLow,
                            freqLowHz = decodeFreqHz(freqLowByte),
                            ampHigh = ampHigh,
                            freqHighHz = decodeFreqHz(freqHighByte)
                        )
                    )
                }
            }
        } else {
            emptyList()
        }

        return BnvibInfo(
            type = type,
            magic = magic,
            sampleRateMs = sampleRateMs,
            sampleCount = sampleCount,
            durationMs = sampleCount * sampleRateMs,
            loopStart = loopStart,
            loopEnd = loopEnd,
            loopWait = loopWait,
            samples = samples,
            isValid = true
        )
    }

    private fun decodeFreqHz(byteVal: Int): Float {
        if (byteVal == 0) return 0f
        return (10.0 * 2.0.pow(byteVal / 32.0)).toFloat()
    }

    private fun invalidInfo(type: VibrationType, magic: Int) = BnvibInfo(
        type = type,
        magic = magic,
        sampleRateMs = 0,
        sampleCount = 0,
        durationMs = 0,
        isValid = false
    )
}
