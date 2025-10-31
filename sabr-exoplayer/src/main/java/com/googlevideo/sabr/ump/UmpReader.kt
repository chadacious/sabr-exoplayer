package com.googlevideo.sabr.ump

internal class UmpReader(private var buffer: CompositeBuffer) {

    fun read(handlePart: (UmpPart) -> Boolean): CompositeBuffer? {
        while (true) {
            var offset = 0
            val (partType, offsetAfterType) = readVarInt(offset)
            if (partType < 0) return buffer
            offset = offsetAfterType

            val (partSize, offsetAfterSize) = readVarInt(offset)
            if (partSize < 0) return buffer
            offset = offsetAfterSize

            if (!buffer.canReadBytes(offset, partSize)) {
                if (!buffer.canReadBytes(offset, 1)) {
                    return buffer
                }
                return buffer
            }

            val headerSplit = buffer.split(offset)
            val partSplit = headerSplit.remaining.split(partSize)

            val part = UmpPart(partType, partSize, partSplit.extracted)
            buffer = partSplit.remaining

            val stop = handlePart(part)
            if (stop) {
                return buffer
            }
        }
    }

    private fun readVarInt(offset: Int): Pair<Int, Int> {
        var localOffset = offset

        if (!buffer.canReadBytes(localOffset, 1)) {
            return -1 to offset
        }

        val firstByte = buffer.getUint8(localOffset)
        val byteLength = when {
            firstByte < 0x80 -> 1
            firstByte < 0xC0 -> 2
            firstByte < 0xE0 -> 3
            firstByte < 0xF0 -> 4
            else -> 5
        }

        if (!buffer.canReadBytes(localOffset, byteLength)) {
            return -1 to offset
        }

        var value: Int
        when (byteLength) {
            1 -> {
                value = buffer.getUint8(localOffset)
                localOffset += 1
            }
            2 -> {
                val byte1 = buffer.getUint8(localOffset); localOffset += 1
                val byte2 = buffer.getUint8(localOffset); localOffset += 1
                value = (byte1 and 0x3F) + 64 * byte2
            }
            3 -> {
                val byte1 = buffer.getUint8(localOffset); localOffset += 1
                val byte2 = buffer.getUint8(localOffset); localOffset += 1
                val byte3 = buffer.getUint8(localOffset); localOffset += 1
                value = (byte1 and 0x1F) + 32 * (byte2 + 256 * byte3)
            }
            4 -> {
                val byte1 = buffer.getUint8(localOffset); localOffset += 1
                val byte2 = buffer.getUint8(localOffset); localOffset += 1
                val byte3 = buffer.getUint8(localOffset); localOffset += 1
                val byte4 = buffer.getUint8(localOffset); localOffset += 1
                value = (byte1 and 0x0F) + 16 * (byte2 + 256 * (byte3 + 256 * byte4))
            }
            else -> {
                val first = buffer.getUint8(localOffset); localOffset += 1
                val b0 = buffer.getUint8(localOffset); localOffset += 1
                val b1 = buffer.getUint8(localOffset); localOffset += 1
                val b2 = buffer.getUint8(localOffset); localOffset += 1
                val b3 = buffer.getUint8(localOffset); localOffset += 1
                value = (first and 0x0F) + 16 * (b0 + 256 * (b1 + 256 * (b2 + 256 * b3)))
            }
        }

        return value to localOffset
    }
}
