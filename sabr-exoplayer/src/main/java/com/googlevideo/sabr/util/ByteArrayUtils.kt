package com.googlevideo.sabr.util

internal fun concatenateChunks(chunks: List<ByteArray>): ByteArray {
    if (chunks.isEmpty()) return ByteArray(0)
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}
