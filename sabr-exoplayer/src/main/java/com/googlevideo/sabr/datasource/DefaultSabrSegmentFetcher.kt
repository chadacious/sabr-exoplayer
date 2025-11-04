package com.googlevideo.sabr.datasource

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.google.protobuf.ByteString
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.logging.SabrLogger
import com.googlevideo.sabr.logging.d
import com.googlevideo.sabr.logging.e
import com.googlevideo.sabr.logging.w
import com.googlevideo.sabr.session.EnabledTrackTypes
import com.googlevideo.sabr.session.FormatKeyUtils
import com.googlevideo.sabr.session.SabrFormat
import com.googlevideo.sabr.session.SabrRequestMetadata
import com.googlevideo.sabr.session.SabrSessionManager
import com.googlevideo.sabr.ump.SabrUmpProcessor
import com.googlevideo.sabr.ump.UmpProcessingResult
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import misc.Common
import video_streaming.ClientAbrStateOuterClass.ClientAbrState
import video_streaming.StreamerContextOuterClass
import video_streaming.VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.ReloadPlayerResponse.ReloadPlaybackContext
import org.json.JSONObject

@UnstableApi
class DefaultSabrSegmentFetcher(
    private val callFactory: Call.Factory = DEFAULT_HTTP_CLIENT,
    private val maxFollowUpAttempts: Int = DEFAULT_MAX_FOLLOW_UP_ATTEMPTS,
    private val responseHandlingMode: UmpResponseHandling = UmpResponseHandling.STREAM_UNTIL_TARGET,
    private val logger: SabrLogger = SabrLogger.NO_OP,
    private val segmentDumper: SabrSegmentDumper? = null,
    private val onReloadRequest: ((ReloadPlaybackContext, SabrRequestMetadata) -> Unit)? = null,
) : SabrSegmentFetcher {

    override fun fetch(
        request: SabrSegmentRequest,
        session: SabrSession,
        sessionManager: SabrSessionManager,
    ): SabrSegmentResult {
        return fetchInternal(request, session, sessionManager, attempt = 0)
    }

    /**
     * Mirrors the TypeScript SABR fetch pipeline: serve cached segments when possible, otherwise
     * issue a protobuf request, stream/process the UMP response, and schedule follow-ups when the
     * backend asks for more data (redirect, sabrContext update, fallback media, etc.).
     */
    private fun fetchInternal(
        request: SabrSegmentRequest,
        session: SabrSession,
        sessionManager: SabrSessionManager,
        attempt: Int,
        metadata: SabrRequestMetadata? = null,
        fallbackData: ByteArray? = null,
        fallbackHeader: MediaHeader? = null,
    ): SabrSegmentResult {
        val format = request.format ?: sessionManager.formatForKey(request.key)
            ?: throw IOException("Unknown SABR format for key ${request.key}")

        ensureActiveFormats(sessionManager, format)

        val resolvedMetadata = metadata ?: createMetadata(request, format)

        maybeServeCachedInitSlice(sessionManager, resolvedMetadata)?.let { cachedSlice ->
            logger.d(TAG) {
                "Served cached init slice for key=${request.key} bytes=${cachedSlice.size}"
            }
            segmentDumper?.dump(request, resolvedMetadata, cachedSlice)
            return SabrSegmentResult(cachedSlice, resolvedMetadata)
        }

        sessionManager.cacheManager?.let { cache ->
            val byteRange = resolvedMetadata.byteRange
            if (byteRange != null) {
                val cacheKey = FormatKeyUtils.createSegmentCacheKeyFromMetadata(resolvedMetadata)
                val cached = if (resolvedMetadata.isInit) {
                    cache.getInitSegment(cacheKey)
                } else {
                    cache.getSegment(cacheKey)
                }
                if (cached != null) {
                    val processed = processSegmentData(sessionManager, resolvedMetadata, cached)
                    segmentDumper?.dump(request, resolvedMetadata, processed)
                    return SabrSegmentResult(processed, resolvedMetadata)
                }
            }
        }

        val streamingUrl = resolveStreamingUrl(sessionManager, session)
        val requestNumber = sessionManager.nextRequestNumber()
        val httpUrl = buildStreamingUrl(streamingUrl, requestNumber)
        sessionManager.requestMetadataManager.put(httpUrl.toString(), resolvedMetadata)

        val abrRequest = buildAbrRequest(sessionManager, format, resolvedMetadata)
        val abrRequestJson = abrRequest.toDebugJson()
        resolvedMetadata.abrRequestJson = abrRequestJson
        sessionManager.recordAbrRequestJson(abrRequestJson)
        logger.d(TAG) {
            val requestedRange = resolvedMetadata.requestedStartRange?.toString() ?: "unset"
            val requestedTime = resolvedMetadata.requestedStartTimeMs?.toString() ?: "unset"
            val byteRange = resolvedMetadata.byteRange?.let { "${it.start}-${it.end}" } ?: "full"
            "Prepared SABR request key=${request.key} attempt=$attempt isInit=${resolvedMetadata.isInit} byteRange=$byteRange requestedStartRange=$requestedRange requestedStartTimeMs=$requestedTime bufferedCount=${abrRequest.bufferedRangesCount}"
        }
        segmentDumper?.dumpRequestJson(request, resolvedMetadata, requestNumber, abrRequest)
        val requestBody = abrRequest.toByteArray()
        val httpRequest = Request.Builder()
            .url(httpUrl)
            .post(requestBody.toRequestBody(PROTOBUF_MEDIA_TYPE))
            .header("Content-Type", PROTOBUF_MEDIA_TYPE.toString())
            .header("Accept-Encoding", "identity")
            .header("Accept", UMP_MEDIA_TYPE)
            .build()

        val processor = SabrUmpProcessor(resolvedMetadata, sessionManager.cacheManager)

        val call = callFactory.newCall(httpRequest)
        logger.d(TAG) {
            "Issuing SABR request key=${request.key} mode=$responseHandlingMode attempt=$attempt url=$httpUrl"
        }
        val response = try {
            call.execute()
        } catch (error: SocketTimeoutException) {
            logger.e(TAG, error) { "SABR request timed out for key=${request.key}" }
            throw IOException("SABR request timed out", error)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                logger.e(TAG) { "SABR request failed code=${resp.code} key=${request.key}" }
                throw IOException("SABR request failed with HTTP ${resp.code}")
            }

            val umpResult = when (responseHandlingMode) {
                UmpResponseHandling.BUFFER_FULL -> consumeBufferedResponse(resp, processor)
                UmpResponseHandling.STREAM_UNTIL_TARGET -> consumeStreamingResponse(call, resp, processor)
            }

            val originalStreamInfo = resolvedMetadata.streamInfo
            val result = umpResult
            val data = result?.data
            val bestFallbackData = result?.fallbackData ?: fallbackData
            val bestFallbackHeader = result?.fallbackMediaHeader ?: fallbackHeader
            val hasFinalData = result?.let { data != null && it.done } ?: false
            val hasFallbackOnly = !hasFinalData && bestFallbackData != null
            if (hasFallbackOnly && originalStreamInfo != null) {
                resolvedMetadata.streamInfo = originalStreamInfo.copy(mediaHeader = null)
            }

            sessionManager.applyStreamInfo(resolvedMetadata)

            maybeHandleReloadRequest(sessionManager, resolvedMetadata)

            if (hasFallbackOnly) {
                resolvedMetadata.streamInfo = originalStreamInfo
            }

            resolvedMetadata.error?.let {
                logger.w(TAG) { "SABR server returned error metadata for key=${request.key}" }
                throw IOException("SABR server returned error response")
            }

            if (hasFinalData && data != null) {
                resolvedMetadata.streamInfo?.mediaHeader?.let { header ->
                    val headerRange = if (header.hasStartRange()) header.startRange else null
                    val headerTime = if (header.hasStartMs()) header.startMs else null
                    val sequence = if (header.hasSequenceNumber()) header.sequenceNumber else null
                    logger.d(TAG) {
                        "SABR response header key=${request.key} startRange=$headerRange startTimeMs=$headerTime seq=$sequence init=${header.isInitSeg}"
                    }
                }
                val processed = processSegmentData(sessionManager, resolvedMetadata, data)
                logger.d(TAG) { "SABR request completed (done=true) for key=${request.key} bytes=${processed.size}" }
                segmentDumper?.dump(request, resolvedMetadata, processed)
                lastReloadContextSignature = null
                return SabrSegmentResult(processed, resolvedMetadata)
            }

            val requiresFollowUp = shouldFollowUp(resolvedMetadata, data) || umpResult?.fallbackData != null
            if (requiresFollowUp && attempt < maxFollowUpAttempts) {
                logger.d(TAG) { "SABR response requested follow-up for key=${request.key} attempt=${attempt + 1}" }
                val hasFallback = bestFallbackHeader != null || bestFallbackData != null
                if (bestFallbackHeader != null && resolvedMetadata.format != null) {
                    if (bestFallbackData != null) {
                        sessionManager.cacheManager?.let { cache ->
                            val cacheKey = FormatKeyUtils.createSegmentCacheKey(bestFallbackHeader, resolvedMetadata.format)
                            cache.putSegment(cacheKey, bestFallbackData)
                        }
                    }
                    sessionManager.clearFormatInitialization(resolvedMetadata.format)
                    val fallbackRange = if (bestFallbackHeader.hasStartRange()) bestFallbackHeader.startRange else null
                    val fallbackStartMs = if (bestFallbackHeader.hasStartMs()) bestFallbackHeader.startMs else null
                    logger.w(TAG) {
                        "Scheduling SABR follow-up key=${request.key} attempt=${attempt + 1} fallbackRange=$fallbackRange fallbackStartMs=$fallbackStartMs requestedRange=${resolvedMetadata.requestedStartRange} requestedTimeMs=${resolvedMetadata.requestedStartTimeMs}"
                    }
                }
                if (hasFallback) {
                    resolvedMetadata.suppressSabrContexts = true
                }
                return fetchInternal(
                    request = request,
                    session = session,
                    sessionManager = sessionManager,
                    attempt = attempt + 1,
                    metadata = resolvedMetadata,
                    fallbackData = bestFallbackData,
                    fallbackHeader = bestFallbackHeader,
                )
            }

            if (data == null || data.isEmpty()) {
                if (bestFallbackData != null && bestFallbackData.isNotEmpty()) {
                    logger.w(TAG) { "SABR response contained no matching media for key=${request.key}; fallback data discarded" }
                }
                logger.w(TAG) { "SABR response contained no media data for key=${request.key}" }
                throw IOException("SABR response contained no media data")
            }

            val processed = processSegmentData(sessionManager, resolvedMetadata, data)
            logger.d(TAG) { "SABR request completed key=${request.key} bytes=${processed.size}" }
            segmentDumper?.dump(request, resolvedMetadata, processed)
            return SabrSegmentResult(processed, resolvedMetadata)
        }
    }

    private fun maybeHandleReloadRequest(
        sessionManager: SabrSessionManager,
        metadata: SabrRequestMetadata,
    ) {
        val reloadContext = metadata.streamInfo?.reloadPlaybackContext ?: return
        val bytes = reloadContext.toByteArray()
        if (bytes.isEmpty()) return
        val signature = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (signature.isEmpty() || signature == lastReloadContextSignature) return
        lastReloadContextSignature = signature
        val reloadContextJson = reloadContextToJson(reloadContext)
        metadata.reloadPlaybackContextJson = reloadContextJson
        sessionManager.recordReloadPlaybackContextJson(reloadContextJson)
        onReloadRequest?.invoke(reloadContext, metadata)
    }

    private fun maybeServeCachedInitSlice(
        sessionManager: SabrSessionManager,
        metadata: SabrRequestMetadata,
    ): ByteArray? {
        val format = metadata.format ?: return null
        val range = metadata.byteRange ?: return null
        if (range.start <= 0L) return null
        return sessionManager.initSegmentSlice(format, range)?.takeIf { it.isNotEmpty() }
    }

    private fun processSegmentData(
        sessionManager: SabrSessionManager,
        metadata: SabrRequestMetadata,
        originalData: ByteArray,
    ): ByteArray {
        val format = metadata.format ?: return originalData
        val range = metadata.byteRange
        val isInitResponse = metadata.isInit || metadata.streamInfo?.mediaHeader?.isInitSeg == true

        if (isInitResponse) {
            if (range == null || range.start <= 0L) {
                sessionManager.rememberInitSegment(format, originalData, null)
                return originalData
            }
            sessionManager.rememberInitSegment(format, originalData, range)
            sessionManager.initSegmentSlice(format, range)?.let { slice ->
                if (slice.isNotEmpty()) return slice
            }
            return originalData
        }

        if (range != null && range.start >= 0L && metadata.streamInfo?.mediaHeader?.isInitSeg == true) {
            sessionManager.initSegmentSlice(format, range)?.let { slice ->
                if (slice.isNotEmpty()) return slice
            }
            sessionManager.rememberInitSegment(format, originalData, range)
            return originalData
        }

        return originalData
    }

    private fun isIndexRangeRequest(request: SabrSegmentRequest): Boolean {
        val length = request.dataSpec.length
        if (length == C.LENGTH_UNSET.toLong()) return false
        if (request.dataSpec.position <= 0L) return false
        return length in 1..INDEX_RANGE_MAX_LENGTH
    }

    private fun consumeBufferedResponse(
        response: Response,
        processor: SabrUmpProcessor,
    ): UmpProcessingResult? {
        val bodyBytes = response.body?.bytes() ?: ByteArray(0)
        if (bodyBytes.isEmpty()) return null
        return processor.processChunk(bodyBytes)
    }

    private fun consumeStreamingResponse(
        call: Call,
        response: Response,
        processor: SabrUmpProcessor,
    ): UmpProcessingResult? {
        val body = response.body ?: return null
        val source = body.source()
        val buffer = Buffer()
        var lastResult: UmpProcessingResult? = null

        while (true) {
            val readBytes = try {
                source.read(buffer, STREAM_READ_CHUNK_SIZE)
            } catch (error: IOException) {
                if (call.isCanceled()) {
                    break
                } else {
                    throw error
                }
            }

            if (readBytes == -1L) {
                break
            }
            if (buffer.size == 0L) {
                continue
            }

            val chunk = buffer.readByteArray(buffer.size)
            if (chunk.isEmpty()) {
                continue
            }

            val result = processor.processChunk(chunk)
            if (result != null) {
                lastResult = result
                if (result.done) {
                    logger.d(TAG) { "SABR streaming mode satisfied target; cancelling HTTP call." }
                    call.cancel()
                    break
                }
            }
        }

        return lastResult
    }

    private fun createMetadata(
        request: SabrSegmentRequest,
        format: SabrFormat,
    ): SabrRequestMetadata {
        val byteRange = buildByteRange(request)
        val isInit = isInitSegment(request) || isIndexRangeRequest(request)
        return SabrRequestMetadata(
            byteRange = byteRange,
            format = format,
            isInit = isInit,
            isUmp = true,
            isSabr = true,
        )
            .also {
                logger.d(TAG) {
                    "metadata key=${format.itag} dataKey=${request.dataSpec.key} position=${request.dataSpec.position} length=${request.dataSpec.length} isInit=$isInit range=$byteRange"
                }
            }
    }

    private fun buildByteRange(request: SabrSegmentRequest): SabrRequestMetadata.ByteRange? {
        val position = request.dataSpec.position
        val length = request.dataSpec.length
        return if (length == C.LENGTH_UNSET.toLong()) {
            null
        } else {
            val end = position + length - 1
            SabrRequestMetadata.ByteRange(position, end)
        }
    }

    private fun isInitSegment(request: SabrSegmentRequest): Boolean {
        val length = request.dataSpec.length
        val position = request.dataSpec.position
        if (length == C.LENGTH_UNSET.toLong()) return false
        if (position != 0L) return false
        return length <= INIT_SEGMENT_MAX_LENGTH
    }

    private fun resolveStreamingUrl(
        sessionManager: SabrSessionManager,
        session: SabrSession,
    ): String {
        return sessionManager.serverAbrStreamingUrl
            ?: session.manifestInfo.serverAbrStreamingUrl
            ?: throw IOException("SABR streaming URL not available")
    }

    private fun buildStreamingUrl(base: String, requestNumber: String): HttpUrl {
        val httpUrl = base.toHttpUrlOrNull()
            ?: throw IOException("Invalid SABR streaming URL: $base")
        return httpUrl.newBuilder()
            .setQueryParameter("rn", requestNumber)
            .build()
    }

    /**
     * Recreates the TypeScript VideoPlaybackAbrRequest payload: populate client state, preferred
     * formats, buffered ranges, and streamer context so the SABR backend sees identical data to
     * the TS client.
     */
    private fun buildAbrRequest(
        sessionManager: SabrSessionManager,
        format: SabrFormat,
        metadata: SabrRequestMetadata,
    ): VideoPlaybackAbrRequest {
        val ustreamerConfig = sessionManager.ustreamerConfig
            ?: throw IOException("SABR ustreamer config missing from manifest")

        val preferredVideo = sessionManager.activeVideoFormat()
            ?: if (format.width != null) format else null
        val preferredAudio = sessionManager.activeAudioFormat()
            ?: if (format.width == null) format else null

        val enabledTrackTypes = if (format.width != null) {
            EnabledTrackTypes.VIDEO_ONLY
        } else {
            EnabledTrackTypes.AUDIO_ONLY
        }

        val clientAbrStateBuilder = ClientAbrState.newBuilder()
            .setPlaybackRate(sessionManager.playbackRate())
            .setClientViewportIsFlexible(false)
            .setEnabledTrackTypesBitfield(enabledTrackTypes)
            .setBandwidthEstimate(sessionManager.bandwidthEstimateBitsPerSec())

        val playerTimeMs = sessionManager.estimatePlayerTimeMs(metadata)
        metadata.requestedStartTimeMs = playerTimeMs
        if (metadata.requestedStartRange == null) {
            metadata.requestedStartRange = metadata.byteRange?.start
        }
        clientAbrStateBuilder.playerTimeMs = playerTimeMs
        format.audioTrackId?.let { clientAbrStateBuilder.audioTrackId = it }
        clientAbrStateBuilder.drcEnabled = format.isDrc
        format.height?.let {
            clientAbrStateBuilder.stickyResolution = it
            clientAbrStateBuilder.lastManualSelectedResolution = it
        }

        val clientAbrState = clientAbrStateBuilder.build()

        val streamerContextBuilder = StreamerContextOuterClass.StreamerContext.newBuilder()
        sessionManager.lastPlaybackCookie?.let {
            streamerContextBuilder.playbackCookie = ByteString.copyFrom(it.toByteArray())
        }
        sessionManager.poToken?.let {
            streamerContextBuilder.poToken = ByteString.copyFrom(it)
        }
        sessionManager.clientInfo?.let {
            streamerContextBuilder.clientInfo = it
        }
        if (!metadata.suppressSabrContexts) {
            // Parity with TS: stream SABR contexts unless a fallback retry tells us to drop them.
            sessionManager.sabrContexts.forEach { (type, update) ->
                val isActive = sessionManager.activeSabrContextTypes.contains(type)
                if (isActive && update.hasValue() && !update.value.isEmpty) {
                    streamerContextBuilder.addSabrContexts(
                        StreamerContextOuterClass.StreamerContext.SabrContext.newBuilder()
                            .setType(type)
                            .setValue(update.value)
                            .build()
                    )
                } else if (!isActive) {
                    streamerContextBuilder.addUnsentSabrContexts(type)
                }
            }
        }

        val abrRequestBuilder = VideoPlaybackAbrRequest.newBuilder()
            .setClientAbrState(clientAbrState)
            .setVideoPlaybackUstreamerConfig(ByteString.copyFrom(ustreamerConfig))
            .setStreamerContext(streamerContextBuilder.build())

        preferredVideo?.let { abrRequestBuilder.addPreferredVideoFormatIds(it.toFormatId()) }
        preferredAudio?.let { abrRequestBuilder.addPreferredAudioFormatIds(it.toFormatId()) }

        val buffering = sessionManager.buildBufferedRanges(
            currentFormat = format,
            activeVideo = preferredVideo,
            activeAudio = preferredAudio,
            isInit = metadata.isInit,
        )
        buffering.bufferedRanges.forEach { range -> abrRequestBuilder.addBufferedRanges(range) }
        buffering.formatToDiscard?.let { abrRequestBuilder.addSelectedFormatIds(it.toFormatId()) }

        if (!metadata.isInit) {
            abrRequestBuilder.addSelectedFormatIds(format.toFormatId())
        }

        val abrRequest = abrRequestBuilder.build()
        logger.d(TAG) { "ABR request JSON: ${abrRequest.toDebugJson()}" }
        logger.d(TAG) {
            val state = abrRequest.clientAbrState
            val selected = abrRequest.selectedFormatIdsList.joinToString(prefix = "[", postfix = "]") { formatIdToString(it) }
            val buffered = abrRequest.bufferedRangesList.joinToString(prefix = "[", postfix = "]") { range ->
                val id = range.formatId?.let { formatIdToString(it) } ?: "?"
                "id=$id start=${range.startSegmentIndex} end=${range.endSegmentIndex} durationMs=${range.durationMs}"
            }
            "ABR request: selected=$selected buffered=$buffered timeMs=${state?.playerTimeMs} enabledTracks=${state?.enabledTrackTypesBitfield}"
        }

        return abrRequest
    }

    private fun ensureActiveFormats(
        sessionManager: SabrSessionManager,
        format: SabrFormat,
    ) {
        val updatedVideo = if (format.width != null) format else sessionManager.activeVideoFormat()
        val updatedAudio = if (format.width == null) format else sessionManager.activeAudioFormat()
        sessionManager.updateActiveFormats(updatedVideo, updatedAudio)
    }

    private fun shouldFollowUp(
        metadata: SabrRequestMetadata,
        data: ByteArray?,
    ): Boolean {
        val streamInfo = metadata.streamInfo ?: return false
        if (!streamInfo.redirect?.url.isNullOrBlank()) {
            return true
        }
        if (streamInfo.sabrContextUpdate != null && (data == null || data.isEmpty())) {
            return true
        }
        if (streamInfo.nextRequestPolicy != null && (data == null || data.isEmpty())) {
            return true
        }
        return false
    }

    companion object {
        private val PROTOBUF_MEDIA_TYPE = "application/x-protobuf".toMediaType()
        private const val UMP_MEDIA_TYPE = "application/vnd.yt-ump"
        private const val INIT_SEGMENT_MAX_LENGTH = 512 * 1024L // 512 KB heuristic
        private const val INDEX_RANGE_MAX_LENGTH = 16 * 1024L // treat requests up to 16 KB as index slices
        private const val DEFAULT_MAX_FOLLOW_UP_ATTEMPTS = 5
        private const val STREAM_READ_CHUNK_SIZE = 64 * 1024L
        private val DEFAULT_HTTP_CLIENT: OkHttpClient = OkHttpClient()
        private const val TAG = "DefaultSabrFetcher"
    }

    private var lastReloadContextSignature: String? = null

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

    private fun formatIdToString(formatId: Common.FormatId): String = formatIdToJson(formatId)
}

internal fun VideoPlaybackAbrRequest.toDebugJson(): String {
    val builder = StringBuilder().append('{')

    clientAbrState?.let { state ->
        builder.append("\"clientAbrState\":{")
            .append("\"playerTimeMs\":").append(state.playerTimeMs)
            .append(",\"bandwidthEstimate\":").append(state.bandwidthEstimate)
            .append(",\"enabledTrackTypes\":").append(state.enabledTrackTypesBitfield)
            .append('}')
            .append(',')
    }

    builder.append("\"selectedFormatIds\":")
        .append(selectedFormatIdsList.joinToString(prefix = "[", postfix = "]") { formatIdToJson(it) })
        .append(',')

    builder.append("\"bufferedRanges\":")
        .append(bufferedRangesList.joinToString(prefix = "[", postfix = "]") { range ->
            val idJson = range.formatId?.let { formatIdToJson(it) } ?: "null"
            val timeRangeJson = if (range.hasTimeRange()) {
                val tr = range.timeRange
                val durationTicks = if (tr.hasDurationTicks()) tr.durationTicks else 0L
                "{\"timescale\":${tr.timescale},\"startTicks\":${tr.startTicks},\"durationTicks\":$durationTicks}"
            } else {
                "null"
            }
            "{\"formatId\":$idJson,\"startSegmentIndex\":${range.startSegmentIndex},\"endSegmentIndex\":${range.endSegmentIndex},\"durationMs\":${range.durationMs},\"startTimeMs\":${range.startTimeMs},\"timeRange\":$timeRangeJson}"
        })

    if (preferredVideoFormatIdsCount > 0) {
        builder.append(',')
            .append("\"preferredVideoFormatIds\":")
            .append(preferredVideoFormatIdsList.joinToString(prefix = "[", postfix = "]") { formatIdToJson(it) })
    }

    if (preferredAudioFormatIdsCount > 0) {
        builder.append(',')
            .append("\"preferredAudioFormatIds\":")
            .append(preferredAudioFormatIdsList.joinToString(prefix = "[", postfix = "]") { formatIdToJson(it) })
    }

    if (videoPlaybackUstreamerConfig.size() > 0) {
        val base64Config = Base64.encodeToString(videoPlaybackUstreamerConfig.toByteArray(), Base64.NO_WRAP)
        builder.append(',')
            .append("\"videoPlaybackUstreamerConfig\":\"").append(base64Config).append('"')
    }

    streamerContext?.let { context ->
        builder.append(',').append("\"streamerContext\":{")
        val parts = mutableListOf<String>()
        if (context.hasPoToken()) {
            val token = Base64.encodeToString(context.poToken.toByteArray(), Base64.NO_WRAP)
            parts += "\"poToken\":\"$token\""
        }
        if (context.hasPlaybackCookie()) {
            val cookie = Base64.encodeToString(context.playbackCookie.toByteArray(), Base64.NO_WRAP)
            parts += "\"playbackCookie\":\"$cookie\""
        }
        if (context.sabrContextsCount > 0) {
            val sabrJson = context.sabrContextsList.joinToString(prefix = "[", postfix = "]") { sabr ->
                val value = Base64.encodeToString(sabr.value.toByteArray(), Base64.NO_WRAP)
                "{\"type\":${sabr.type},\"value\":\"$value\"}"
            }
            parts += "\"sabrContexts\":$sabrJson"
        }
        if (context.unsentSabrContextsCount > 0) {
            parts += "\"unsentSabrContexts\":${context.unsentSabrContextsList}"
        }
        parts += "\"clientInfoPresent\":${context.hasClientInfo()}"
        builder.append(parts.joinToString(","))
        builder.append('}')
    }

    builder.append('}')
    return builder.toString()
}

internal fun formatIdToJson(formatId: Common.FormatId): String {
    val parts = buildList {
        if (formatId.hasItag()) add("\"itag\":${formatId.itag}")
        if (formatId.hasXtags()) add("\"xtags\":\"${formatId.xtags}\"")
        if (formatId.hasLastModified()) add("\"lmt\":${formatId.lastModified}")
    }
    return parts.joinToString(prefix = "{", postfix = "}")
}

enum class UmpResponseHandling {
    BUFFER_FULL,
    STREAM_UNTIL_TARGET,
}
