package com.googlevideo.sabr.ump

internal data class UmpPart(
    val type: Int,
    val size: Int,
    val data: CompositeBuffer,
)
