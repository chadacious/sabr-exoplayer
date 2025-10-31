package com.googlevideo.sabr.datasource

import com.googlevideo.sabr.logging.SabrLogger
import com.googlevideo.sabr.logging.d
import com.googlevideo.sabr.logging.w
import com.googlevideo.sabr.session.SabrFormat
import com.googlevideo.sabr.session.SabrRequestMetadata
import java.io.IOException
import kotlin.text.Charsets
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import video_streaming.VideoPlaybackAbrRequestOuterClass

/**
 * Optional helper that mirrors media segments out to a host-side dump server for debugging.
 *
 * The companion Python script described in the docs listens for POST requests and writes the raw
 * segment bytes to disk where they can be inspected with external tooling.
 */
class SabrSegmentDumper(
    private val baseUrl: HttpUrl,
    private val callFactory: Call.Factory,
    private val logger: SabrLogger,
) {

    fun dump(request: SabrSegmentRequest, metadata: SabrRequestMetadata, data: ByteArray) {
        val path = buildDumpPath(request, metadata, data.size)
        sendDump(path, data, OCTET_STREAM_MEDIA_TYPE) { success ->
            if (success) {
                logger.d(TAG) { "Dumped SABR segment path=$path bytes=${data.size}" }
            } else {
                logger.w(TAG) { "Segment dump failed path=$path" }
            }
        }
    }

    fun dumpRequestJson(
        request: SabrSegmentRequest,
        metadata: SabrRequestMetadata,
        requestNumber: String,
        abrRequest: VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest,
    ) {
        val key = request.key.trimEnd(':')
        val builder = StringBuilder()
            .append(ROOT_DIRECTORY)
            .append('/')
            .append("requests")
            .append('/')
            .append("itag_").append(key)
            .append('/')
            .append("rn_").append(requestNumber)

        metadata.byteRange?.let { range ->
            builder.append("_pos_").append(range.start)
        }

        val path = builder.append(".json").toString()
        val jsonBytes = abrRequest.toDebugJson().toByteArray(Charsets.UTF_8)
        sendDump(path, jsonBytes, JSON_MEDIA_TYPE) { success ->
            if (success) {
                logger.d(TAG) { "Dumped SABR request path=$path" }
            } else {
                logger.w(TAG) { "Request dump failed path=$path" }
            }
        }
    }

    private fun buildDumpPath(
        request: SabrSegmentRequest,
        metadata: SabrRequestMetadata,
        byteCount: Int,
    ): String {
        val format = metadata.format
        val trackType = format?.let(::trackTypeForFormat) ?: "unknown"
        val key = request.key.trimEnd(':')
        val sequence = metadata.streamInfo?.mediaHeader?.sequenceNumber
        val byteRange = metadata.byteRange

        val builder = StringBuilder()
        builder.append(ROOT_DIRECTORY).append('/')
        builder.append(trackType).append('/')
        builder.append("itag_").append(key)

        sequence?.let {
            builder.append("/seq_").append(it)
        }

        builder.append("/pos_").append(request.dataSpec.position)
        if (byteRange != null) {
            builder.append("_range_").append(byteRange.start).append('-').append(byteRange.end)
        }
        builder.append("_len_").append(byteCount)

        builder.append(".bin")
        return builder.toString()
    }

    private fun trackTypeForFormat(format: SabrFormat): String {
        return when {
            format.width != null -> "video"
            format.mimeType?.startsWith("audio") == true -> "audio"
            else -> "data"
        }
    }

    companion object {
        private const val PATH_QUERY = "path"
        private const val TAG = "SabrSegmentDumper"
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ROOT_DIRECTORY = "../../sabr-android"
    }
    private fun sendDump(
        path: String,
        data: ByteArray,
        mediaType: okhttp3.MediaType,
        onComplete: (Boolean) -> Unit,
    ) {
        val url = baseUrl.newBuilder()
            .setQueryParameter(PATH_QUERY, path)
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .post(data.toRequestBody(mediaType))
            .build()

        try {
            callFactory.newCall(httpRequest).execute().use { response ->
                onComplete(response.isSuccessful)
            }
        } catch (error: IOException) {
            onComplete(false)
        }
    }
}
