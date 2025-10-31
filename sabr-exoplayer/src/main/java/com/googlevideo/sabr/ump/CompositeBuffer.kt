package com.googlevideo.sabr.ump

import java.io.ByteArrayOutputStream

internal class CompositeBuffer(initialChunks: List<ByteArray> = emptyList()) {

    private val chunks: MutableList<ByteArray> = initialChunks.map { it.copyOf() }.toMutableList()
    private var totalLength: Int = initialChunks.sumOf { it.size }

    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        chunks += chunk.copyOf()
        totalLength += chunk.size
    }

    fun append(buffer: CompositeBuffer) {
        buffer.chunks.forEach { append(it) }
    }

    fun length(): Int = totalLength

    fun isEmpty(): Boolean = totalLength == 0

    fun canReadBytes(offset: Int, length: Int): Boolean {
        return offset >= 0 && length >= 0 && offset + length <= totalLength
    }

    fun getUint8(position: Int): Int {
        require(position in 0 until totalLength) { "Position $position out of range" }
        var index = position
        for (chunk in chunks) {
            if (index < chunk.size) {
                return chunk[index].toInt() and 0xFF
            }
            index -= chunk.size
        }
        error("Position $position out of range")
    }


    fun readVarint32(position: Int): Pair<Int, Int> {
        var shift = 0
        var result = 0
        var bytesRead = 0
        var currentPos = position
        while (shift < 32 && currentPos < totalLength) {
            val byte = getUint8(currentPos)
            currentPos++
            bytesRead++
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) {
                return result to bytesRead
            }
            shift += 7
        }
        return result to bytesRead
    }

    fun getUint32Le(position: Int): Int {
        require(position in 0 until totalLength) { "Position $position out of range" }
        require(totalLength - position >= 4) { "Not enough bytes to read UInt32" }
        var index = position
        var remaining = 4
        var result = 0
        var shift = 0
        for (chunk in chunks) {
            if (index >= chunk.size) {
                index -= chunk.size
                continue
            }
            val copyLen = minOf(chunk.size - index, remaining)
            for (i in 0 until copyLen) {
                result = result or ((chunk[index + i].toInt() and 0xFF) shl shift)
                shift += 8
            }
            remaining -= copyLen
            if (remaining == 0) break
            index = 0
        }
        return result
    }


    fun split(position: Int): SplitResult {
        require(position >= 0) { "Split position must be non-negative" }
        if (position >= totalLength) {
            return SplitResult(CompositeBuffer(chunks), CompositeBuffer())
        }

        val extracted = mutableListOf<ByteArray>()
        val remaining = mutableListOf<ByteArray>()
        var consumed = 0

        for (chunk in chunks) {
            val chunkSize = chunk.size
            when {
                consumed + chunkSize <= position -> {
                    extracted += chunk.copyOf()
                }
                consumed >= position -> {
                    remaining += chunk.copyOf()
                }
                else -> {
                    val splitPoint = position - consumed
                    if (splitPoint > 0) {
                        extracted += chunk.copyOfRange(0, splitPoint)
                    }
                    if (splitPoint < chunkSize) {
                        remaining += chunk.copyOfRange(splitPoint, chunkSize)
                    }
                }
            }
            consumed += chunkSize
        }

        return SplitResult(CompositeBuffer(extracted), CompositeBuffer(remaining))
    }

    fun toByteArray(): ByteArray {
        val output = ByteArrayOutputStream(totalLength)
        chunks.forEach(output::write)
        return output.toByteArray()
    }

    val chunkList: List<ByteArray>
        get() = chunks.map { it.copyOf() }

    data class SplitResult(val extracted: CompositeBuffer, val remaining: CompositeBuffer)
}
