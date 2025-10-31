package com.googlevideo.sabr.session

import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaHeaderOuterClass.MediaHeader

internal object FormatKeyUtils {

    fun createKey(itag: Int?, xtags: String?): String {
        val left = itag?.toString() ?: ""
        val right = xtags ?: ""
        return "$left:$right"
    }

    fun fromFormat(format: SabrFormat?): String? {
        return format?.let { createKey(it.itag, it.xtags) }
    }

    fun fromMediaHeader(mediaHeader: MediaHeader): String {
        return createKey(mediaHeader.itag, if (mediaHeader.hasXtags()) mediaHeader.xtags else null)
    }

    fun fromFormatInitializationMetadata(metadata: FormatInitializationMetadata): String {
        val formatId = metadata.formatId
        return if (formatId.hasItag()) {
            createKey(formatId.itag, if (formatId.hasXtags()) formatId.xtags else null)
        } else {
            ""
        }
    }

    fun createSegmentCacheKey(
        mediaHeader: MediaHeader,
        format: SabrFormat? = null,
    ): String {
        return if (mediaHeader.isInitSeg && format != null) {
            listOf(
                mediaHeader.itag.toString(),
                if (mediaHeader.hasXtags()) mediaHeader.xtags else "",
                format.contentLength?.toString().orEmpty(),
                format.mimeType.orEmpty(),
            ).joinToString(":")
        } else {
            val startRange = if (mediaHeader.hasStartRange()) mediaHeader.startRange.toString() else "0"
            val xtags = if (mediaHeader.hasXtags()) mediaHeader.xtags else ""
            "$startRange-${mediaHeader.itag}-$xtags"
        }
    }

    fun createSegmentCacheKeyFromMetadata(metadata: SabrRequestMetadata): String {
        val byteRange = metadata.byteRange
            ?: error("Invalid metadata: byteRange is missing")
        val format = metadata.format
            ?: error("Invalid metadata: format is missing")

        val pseudoHeader = MediaHeader.newBuilder()
            .setItag(format.itag)
            .apply { format.xtags?.let { setXtags(it) } }
            .setStartRange(byteRange.start)
            .setIsInitSeg(metadata.isInit)
            .build()

        return createSegmentCacheKey(
            pseudoHeader,
            if (metadata.isInit) format else null
        )
    }

    fun getUniqueFormatId(format: SabrFormat): String {
        return if (format.width != null) {
            format.itag.toString()
        } else {
            buildList {
                add(format.itag.toString())
                format.audioTrackId?.let { add(it) }
                if (format.isDrc == true) add("drc")
            }.joinToString("-")
        }
    }

    private fun String?.orEmpty(): String = this ?: ""
}
