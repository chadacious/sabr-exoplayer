package com.googlevideo.sabr.ump

import com.googlevideo.sabr.session.CacheManager
import com.googlevideo.sabr.session.FormatKeyUtils
import com.googlevideo.sabr.session.SabrRequestMetadata
import com.googlevideo.sabr.util.concatenateChunks
import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy
import video_streaming.ReloadPlayerResponse.ReloadPlaybackContext
import video_streaming.SabrContextSendingPolicyOuterClass.SabrContextSendingPolicy
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate
import video_streaming.SabrErrorOuterClass.SabrError
import video_streaming.SabrRedirectOuterClass.SabrRedirect
import video_streaming.SnackbarMessageOuterClass.SnackbarMessage
import video_streaming.StreamProtectionStatusOuterClass.StreamProtectionStatus
import video_streaming.UmpPartId.UMPPartId
import org.json.JSONObject
import kotlin.math.abs

internal class SabrUmpProcessor(
    private val requestMetadata: SabrRequestMetadata,
    private val cacheManager: CacheManager?,
) {

    private var partialBuffer: CompositeBuffer? = null
    private val formatInitMetadata = mutableListOf<FormatInitializationMetadata>()
    private var desiredHeaderId: Int? = null
    private val partialSegments = mutableMapOf<Int, Segment>()

    private val partHandlers: Map<Int, (UmpPart) -> UmpProcessingResult?> = mapOf(
        UMPPartId.FORMAT_INITIALIZATION_METADATA.number to ::handleFormatInitMetadata,
        UMPPartId.NEXT_REQUEST_POLICY.number to ::handleNextRequestPolicy,
        UMPPartId.SABR_ERROR.number to ::handleSabrError,
        UMPPartId.SABR_REDIRECT.number to ::handleSabrRedirect,
        UMPPartId.SABR_CONTEXT_UPDATE.number to ::handleSabrContextUpdate,
        UMPPartId.SABR_CONTEXT_SENDING_POLICY.number to ::handleSabrContextSendingPolicy,
        UMPPartId.SNACKBAR_MESSAGE.number to ::handleSnackbarMessage,
        UMPPartId.STREAM_PROTECTION_STATUS.number to ::handleStreamProtectionStatus,
        UMPPartId.RELOAD_PLAYER_RESPONSE.number to ::handleReloadPlayerResponse,
        UMPPartId.MEDIA_HEADER.number to ::handleMediaHeader,
        UMPPartId.MEDIA.number to ::handleMedia,
        UMPPartId.MEDIA_END.number to ::handleMediaEnd,
    )

    fun processChunk(bytes: ByteArray): UmpProcessingResult? {
        val buffer = partialBuffer ?: CompositeBuffer()
        buffer.append(bytes)
        val reader = UmpReader(buffer)

        var result: UmpProcessingResult? = null
        val partSummary = mutableListOf<String>()
        partialBuffer = reader.read { part ->
            val handlerName = UMPPartId.forNumber(part.type)?.name ?: part.type.toString()
            val before = getSegmentInfo()?.bufferedChunks?.sumOf { it.size } ?: 0
            val handler = partHandlers[part.type]
            val handlerResult = handler?.invoke(part)
            val after = getSegmentInfo()?.bufferedChunks?.sumOf { it.size } ?: 0
            val delta = after - before
            partSummary += "$handlerName bytes=${part.data.length()} delta=$delta"
            if (handlerResult != null) {
                result = handlerResult
                desiredHeaderId = null
                partialSegments.clear()
                true
            } else {
                false
            }
        }

        if (result != null) {
            // Reset partial state after terminal result
            partialBuffer = null
            android.util.Log.d(TAG, "UMP parts: ${partSummary.joinToString()}")
        } else if (partSummary.isNotEmpty()) {
            android.util.Log.d(TAG, "UMP parts (partial): ${partSummary.joinToString()}")
        }

        return result
    }

    fun getSegmentInfo(): Segment? = desiredHeaderId?.let { partialSegments[it] }

    private fun handleFormatInitMetadata(part: UmpPart): UmpProcessingResult? {
        decode(part) { FormatInitializationMetadata.parseFrom(it) }?.let(formatInitMetadata::add)
        return null
    }

    private fun handleNextRequestPolicy(part: UmpPart): UmpProcessingResult? {
        val policy = decode(part) { NextRequestPolicy.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(nextRequestPolicy = policy) }
        return null
    }

    private fun handleMediaHeader(part: UmpPart): UmpProcessingResult? {
        val mediaHeader = decode(part) { MediaHeader.parseFrom(it) } ?: return null

        val targetFormatKey = FormatKeyUtils.fromFormat(requestMetadata.format)
        val segmentFormatKey = FormatKeyUtils.fromMediaHeader(mediaHeader)

        android.util.Log.d(
            TAG,
            "MEDIA_HEADER headerId=${mediaHeader.headerId} segmentKey=$segmentFormatKey targetKey=$targetFormatKey isInit=${mediaHeader.isInitSeg} startRange=${if (mediaHeader.hasStartRange()) mediaHeader.startRange else null} startTimeMs=${if (mediaHeader.hasStartMs()) mediaHeader.startMs else null} seq=${if (mediaHeader.hasSequenceNumber()) mediaHeader.sequenceNumber else null}"
        )

        val requestedRange = requestMetadata.requestedStartRange
        val requestedTime = requestMetadata.requestedStartTimeMs
        val formatMatches = targetFormatKey.isNullOrEmpty() || segmentFormatKey == targetFormatKey
        val rangeMatches = requestedRange == null || !mediaHeader.hasStartRange() || mediaHeader.startRange == requestedRange
        val timeMatches = requestedTime == null || !mediaHeader.hasStartMs() ||
            abs(mediaHeader.startMs - requestedTime) <= START_TIME_TOLERANCE_MS
        val isTarget = formatMatches && rangeMatches && timeMatches

        if (requestMetadata.isSabr && formatMatches) {
            if (!rangeMatches && mediaHeader.hasStartRange()) {
                android.util.Log.w(
                    TAG,
                    "MEDIA_HEADER range mismatch headerId=${mediaHeader.headerId} startRange=${mediaHeader.startRange} requestedStartRange=$requestedRange seq=${if (mediaHeader.hasSequenceNumber()) mediaHeader.sequenceNumber else null}"
                )
            }
            if (!timeMatches && mediaHeader.hasStartMs()) {
                android.util.Log.w(
                    TAG,
                    "MEDIA_HEADER time mismatch headerId=${mediaHeader.headerId} startTimeMs=${mediaHeader.startMs} requestedStartTimeMs=$requestedTime seq=${if (mediaHeader.hasSequenceNumber()) mediaHeader.sequenceNumber else null}"
                )
            }
        } else if (!formatMatches) {
            android.util.Log.w(
                TAG,
                "MEDIA_HEADER format mismatch headerId=${mediaHeader.headerId} segmentKey=$segmentFormatKey targetKey=$targetFormatKey (ignoring)"
            )
        }

        if (mediaHeader.hasHeaderId()) {
            val headerId = mediaHeader.headerId
            val segment = Segment(
                headerId = headerId,
                mediaHeader = mediaHeader,
                isFallback = !isTarget
            )
            if (isTarget) {
                desiredHeaderId = headerId
                android.util.Log.d(TAG, "Desired header set to $headerId")
            } else {
                android.util.Log.d(
                    TAG,
                    "Detected fallback segment headerId=$headerId range=${if (mediaHeader.hasStartRange()) mediaHeader.startRange else null} timeMs=${if (mediaHeader.hasStartMs()) mediaHeader.startMs else null}"
                )
            }
            partialSegments[headerId] = segment
        }
        return null
    }

    private fun handleMedia(part: UmpPart): UmpProcessingResult? {
        val headerId = readHeaderId(part)
        val splitBytes = headerId.second
        val actualId = headerId.first
        val buffer = part.data.split(splitBytes).remaining
        val segment = partialSegments[actualId]
        if (segment != null) {
            segment.lastChunkSize = buffer.length()
            buffer.chunkList.forEach(segment.bufferedChunks::add)
            android.util.Log.d(
                TAG,
                "MEDIA chunk headerId=$actualId chunkBytes=${segment.lastChunkSize} total=${segment.bufferedChunks.sumOf { it.size }}"
            )
        } else {
            android.util.Log.d(TAG, "MEDIA chunk dropped headerId=$actualId (no segment)")
        }
        return null
    }

    private fun handleMediaEnd(part: UmpPart): UmpProcessingResult? {
        val headerInfo = readHeaderId(part)
        val headerId = headerInfo.first
        val desired = desiredHeaderId
        val segment = partialSegments[headerId]
        if (segment == null) {
            return null
        }

        if (!segment.isFallback && desired != null && headerId == desired) {
            val segmentBytes = concatenateChunks(segment.bufferedChunks)
            android.util.Log.d(
                TAG,
                "MEDIA_END headerId=$headerId bufferedBytes=${segmentBytes.size} init=${segment.mediaHeader.isInitSeg} startRange=${if (segment.mediaHeader.hasStartRange()) segment.mediaHeader.startRange else null} startTimeMs=${if (segment.mediaHeader.hasStartMs()) segment.mediaHeader.startMs else null} seq=${if (segment.mediaHeader.hasSequenceNumber()) segment.mediaHeader.sequenceNumber else null}"
            )
            updateStreamInfo {
                it.copy(
                    formatInitMetadata = formatInitMetadata.toList(),
                    mediaHeader = segment.mediaHeader,
                )
            }

            if (requestMetadata.isInit && requestMetadata.byteRange != null && requestMetadata.format != null) {
                cacheManager?.putInitSegment(
                    FormatKeyUtils.createSegmentCacheKey(segment.mediaHeader, requestMetadata.format),
                    segmentBytes,
                )
                val range = requestMetadata.byteRange
                val startLong = range.start.coerceAtLeast(0L)
                val endLong = if (range.end >= range.start && range.end >= 0L) {
                    (range.end + 1L).coerceAtMost(segmentBytes.size.toLong())
                } else {
                    segmentBytes.size.toLong()
                }
                if (startLong < segmentBytes.size.toLong() && endLong > startLong) {
                    val start = startLong.toInt()
                    val endExclusive = endLong.toInt()
                    val sliced = segmentBytes.copyOfRange(start, endExclusive)
                    return UmpProcessingResult(sliced, done = true)
                }
                return UmpProcessingResult(segmentBytes, done = true)
            }

            return UmpProcessingResult(segmentBytes, done = true)
        } else if (segment.isFallback) {
            val fallbackBytes = concatenateChunks(segment.bufferedChunks)
            android.util.Log.w(
                TAG,
                "FALLBACK_MEDIA headerId=$headerId bufferedBytes=${fallbackBytes.size} range=${if (segment.mediaHeader.hasStartRange()) segment.mediaHeader.startRange else null} timeMs=${if (segment.mediaHeader.hasStartMs()) segment.mediaHeader.startMs else null}"
            )
            partialSegments.remove(headerId)
            return UmpProcessingResult(
                fallbackData = fallbackBytes,
                fallbackMediaHeader = segment.mediaHeader,
                done = false
            )
        }
        return null
    }

    private fun handleSnackbarMessage(part: UmpPart): UmpProcessingResult? {
        val message = decode(part) { SnackbarMessage.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(snackbarMessage = message) }
        return null
    }

    private fun handleSabrError(part: UmpPart): UmpProcessingResult {
        val error = decode(part) { SabrError.parseFrom(it) }
        requestMetadata.error = SabrRequestMetadata.SabrErrorInfo(error)
        val errorType = error?.type ?: "unknown"
        val errorCode = error?.code ?: -1
        val playbackContextJson = requestMetadata.reloadPlaybackContextJson
            ?: requestMetadata.streamInfo?.reloadPlaybackContext?.let(::reloadContextToJson)
        val abrRequestJson = requestMetadata.abrRequestJson
        android.util.Log.e(
            TAG,
            "SABR_ERROR type=$errorType code=$errorCode playbackContext=${playbackContextJson ?: "null"} abrRequest=${abrRequestJson ?: "null"}"
        )
        return UmpProcessingResult(done = true)
    }

    private fun handleStreamProtectionStatus(part: UmpPart): UmpProcessingResult? {
        val status = decode(part) { StreamProtectionStatus.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(streamProtectionStatus = status) }
        return if (status.status == 3) UmpProcessingResult(done = true) else null
    }

    private fun handleReloadPlayerResponse(part: UmpPart): UmpProcessingResult? {
        val context = decode(part) { ReloadPlaybackContext.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(reloadPlaybackContext = context) }
        return UmpProcessingResult(done = true)
    }

    private fun handleSabrRedirect(part: UmpPart): UmpProcessingResult? {
        val redirect = decode(part) { SabrRedirect.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(redirect = redirect) }
        return if (requestMetadata.isUmp && !requestMetadata.isSabr) {
            UmpProcessingResult(done = true)
        } else null
    }

    private fun handleSabrContextUpdate(part: UmpPart): UmpProcessingResult? {
        val update = decode(part) { SabrContextUpdate.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(sabrContextUpdate = update) }
        return null
    }

    private fun handleSabrContextSendingPolicy(part: UmpPart): UmpProcessingResult? {
        val policy = decode(part) { SabrContextSendingPolicy.parseFrom(it) } ?: return null
        updateStreamInfo { it.copy(sabrContextSendingPolicy = policy) }
        return null
    }

    private fun <T> decode(part: UmpPart, parser: (ByteArray) -> T): T? {
        if (part.data.isEmpty()) return null
        return try {
            parser(part.data.toByteArray())
        } catch (_: Exception) {
            null
        }
    }


    private fun readHeaderId(part: UmpPart): Pair<Int, Int> {
        val (value, consumed) = part.data.readVarint32(0)
        val bytesUsed = if (consumed == 0) 1 else consumed
        return value to bytesUsed
    }



    private fun updateStreamInfo(transform: (SabrRequestMetadata.StreamInfo) -> SabrRequestMetadata.StreamInfo) {
        val current = requestMetadata.streamInfo ?: SabrRequestMetadata.StreamInfo()
        requestMetadata.streamInfo = transform(current)
    }

    private fun reloadContextToJson(context: ReloadPlaybackContext): String? {
        if (!context.hasReloadPlaybackParams()) return null
        val params = context.reloadPlaybackParams
        if (!params.hasToken()) return null
        val paramsJson = JSONObject().apply {
            put("token", params.token)
        }
        return JSONObject().apply {
            put("reloadPlaybackParams", paramsJson)
        }.toString()
    }

    internal data class Segment(
        val headerId: Int,
        val mediaHeader: MediaHeader,
        val bufferedChunks: MutableList<ByteArray> = mutableListOf(),
        var lastChunkSize: Int = 0,
        val isFallback: Boolean = false,
    )

    companion object {
        private const val TAG = "SabrUmpProcessor"
        private const val START_TIME_TOLERANCE_MS = 2_000L
    }

}
