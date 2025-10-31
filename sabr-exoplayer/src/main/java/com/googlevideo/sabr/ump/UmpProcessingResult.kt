package com.googlevideo.sabr.ump

import video_streaming.MediaHeaderOuterClass.MediaHeader

data class UmpProcessingResult(
    val data: ByteArray? = null,
    val done: Boolean,
    val fallbackData: ByteArray? = null,
    val fallbackMediaHeader: MediaHeader? = null,
)
