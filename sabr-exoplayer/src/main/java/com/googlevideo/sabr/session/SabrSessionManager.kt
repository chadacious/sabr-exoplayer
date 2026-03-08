package com.googlevideo.sabr.session

import android.util.Base64
import android.util.Log
import com.googlevideo.sabr.SabrSession
import misc.Common
import video_streaming.BufferedRangeOuterClass.BufferedRange
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy
import video_streaming.PlaybackCookieOuterClass.PlaybackCookie
import video_streaming.ReloadPlayerResponse.ReloadPlaybackContext
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate.SabrContextWritePolicy
import video_streaming.StreamerContextOuterClass
import video_streaming.TimeRangeOuterClass
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * Holds mutable playback/session state required while streaming SABR content.
 *
 * Mirrors the structure of the TypeScript `SabrStreamingAdapter` while embracing a Kotlin/Android
 * friendly API. The manager is intentionally stateful so host apps can feed player metrics in and
 * the SABR data source can reuse the same metadata while issuing requests.
 */
class SabrSessionManager(
    val requestMetadataManager: RequestMetadataManager = RequestMetadataManager(),
    val cacheManager: CacheManager? = null,
) {

    private val requestCounter = AtomicInteger(0)
    private var lastRequestMetadata: SabrRequestMetadata? = null
    private val formatsByItag = mutableMapOf<Int, MutableList<SabrFormat>>()
    private val formatsByKey = mutableMapOf<String, SabrFormat>()
    private var lastManifestHash: Int? = null
    private val initializedFormats = mutableMapOf<String, InitializedFormat>()
    private val initSegmentStore = mutableMapOf<String, ByteArray>()
    private val estimatedPlaybackPositionsMs = mutableMapOf<String, Long>()
    private var playbackRate: Float = 1f
    private var bandwidthEstimateBitsPerSec: Long = 0L
    private var activeVideoFormat: SabrFormat? = null
    private var activeAudioFormat: SabrFormat? = null
    private var lastAbrRequestJson: String? = null
    private var lastReloadPlaybackContextJson: String? = null

    var sabrFormats: List<SabrFormat> = emptyList()
        private set

    var serverAbrStreamingUrl: String? = null
    var ustreamerConfig: ByteArray? = null
    var lastPlaybackCookie: PlaybackCookie? = null
    var lastPlayerPositionSeconds: Double = 0.0
    var poToken: ByteArray? = null
    var clientInfo: StreamerContextOuterClass.StreamerContext.ClientInfo? = null

    var nextRequestPolicy: NextRequestPolicy? = null
        private set

    val sabrContexts: MutableMap<Int, SabrContextUpdate> = mutableMapOf()
    val activeSabrContextTypes: MutableSet<Int> = mutableSetOf()

    fun nextRequestNumber(): String = requestCounter.getAndIncrement().toString()

    fun updateFormats(formats: List<SabrFormat>) {
        sabrFormats = formats
        formatsByItag.clear()
        formatsByKey.clear()
        formats.forEach { format ->
            formatsByItag.getOrPut(format.itag) { mutableListOf() }.add(format)
            FormatKeyUtils.fromFormat(format)?.let { key ->
                if (key.isNotEmpty()) {
                    formatsByKey[key] = format
                    if (key.endsWith(":")) {
                        formatsByKey[key.removeSuffix(":")] = format
                    }
                }
            }
        }

        Log.i(TAG, buildString {
            append("formatsByItag=")
            append(formatsByItag.values.flatten().joinToString { format ->
                val parts = mutableListOf("itag=${format.itag}")
                format.xtags?.takeIf { it.isNotEmpty() }?.let { parts += "xtags=$it" }
                format.mimeType?.let { parts += "mime=$it" }
                format.audioTrackId?.let { parts += "audioTrack=$it" }
                format.contentLength?.let { parts += "len=$it" }
                parts.joinToString(prefix = "[", postfix = "]", separator = ",")
            })
        })
    }

    fun recordMetadata(metadata: SabrRequestMetadata) {
        lastRequestMetadata = metadata
    }

    fun recordAbrRequestJson(json: String) {
        lastAbrRequestJson = json
    }

    fun lastAbrRequestJson(): String? = lastAbrRequestJson

    fun recordReloadPlaybackContextJson(json: String?) {
        lastReloadPlaybackContextJson = json
    }

    fun lastReloadPlaybackContextJson(): String? = lastReloadPlaybackContextJson

    fun lastReloadPlaybackContext(): ReloadPlaybackContext? {
        return lastRequestMetadata?.streamInfo?.reloadPlaybackContext
    }

    fun updatePlaybackMetrics(
        playbackRate: Float? = null,
        bandwidthEstimateBitsPerSec: Long? = null,
        playerPositionSeconds: Double? = null,
    ) {
        playbackRate?.let { this.playbackRate = it }
        bandwidthEstimateBitsPerSec?.let { this.bandwidthEstimateBitsPerSec = it }
        playerPositionSeconds?.let { this.lastPlayerPositionSeconds = it }
    }

    fun playbackRate(): Float = playbackRate

    fun bandwidthEstimateBitsPerSec(): Long = bandwidthEstimateBitsPerSec

    fun updateActiveFormats(video: SabrFormat?, audio: SabrFormat?) {
        activeVideoFormat = video
        activeAudioFormat = audio
    }

    fun clearFormatInitialization(format: SabrFormat?) {
        val key = FormatKeyUtils.fromFormat(format)
        if (!key.isNullOrEmpty()) {
            initializedFormats.remove(key)
        }
    }

    fun updateEstimatedPlaybackPosition(format: SabrFormat?, positionMs: Long) {
        val key = FormatKeyUtils.fromFormat(format) ?: return
        if (positionMs < 0) return
        estimatedPlaybackPositionsMs[key] = positionMs
    }

    fun activeVideoFormat(): SabrFormat? = activeVideoFormat

    fun activeAudioFormat(): SabrFormat? = activeAudioFormat

    fun recordUpcomingSegmentTime(format: SabrFormat?, startTimeMs: Long?) {
        val key = FormatKeyUtils.fromFormat(format) ?: return
        if (startTimeMs == null || startTimeMs < 0) return

        val previousEstimate = estimatedPlaybackPositionsMs[key]
        estimatedPlaybackPositionsMs[key] = startTimeMs

        val initialized = initializedFormats[key]
        val initializedStartMs = initialized?.mediaHeaders?.firstOrNull()?.takeIf { it.hasStartMs() }?.startMs
        val seekBackDetected = when {
            initializedStartMs != null && startTimeMs + SEEK_BACK_CLEAR_THRESHOLD_MS < initializedStartMs -> true
            previousEstimate != null && startTimeMs + SEEK_BACK_CLEAR_THRESHOLD_MS < previousEstimate -> true
            else -> false
        }
        if (seekBackDetected) {
            Log.d(TAG, "Seek back detected; clearing buffered range metadata for $key (startTimeMs=$startTimeMs previous=$previousEstimate initStart=$initializedStartMs)")
            initializedFormats.remove(key)
            // TS clears the per-format state after a rewind; we additionally drop SABR contexts so
            // the follow-up request restarts without stale server-directed state.
            sabrContexts.clear()
            activeSabrContextTypes.clear()
        }
    }

    fun applyManifest(info: SabrSession.ManifestInfo) {
        val hash = info.rawMpd?.hashCode()
        if (hash != null && hash == lastManifestHash) return
        hash?.let { lastManifestHash = it }
        initSegmentStore.clear()
        estimatedPlaybackPositionsMs.clear()
        lastAbrRequestJson = null
        lastReloadPlaybackContextJson = null

        info.serverAbrStreamingUrl?.let { serverAbrStreamingUrl = it }
        info.ustreamerConfig?.let { config ->
            ustreamerConfig = decodeBase64OrNull(config)
        }
        poToken = info.poTokenBase64?.let(::decodeBase64OrNull)
        clientInfo = info.clientInfo
        updateFormats(info.sabrFormats)
    }

    fun estimatePlayerTimeMs(metadata: SabrRequestMetadata): Long {
        val format = metadata.format ?: return roundMillis(lastPlayerPositionSeconds)
        val key = FormatKeyUtils.fromFormat(format)
        if (key.isNullOrEmpty()) {
            return roundMillis(lastPlayerPositionSeconds)
        }

        // Use cached value when we already know the timeline for this format.
        estimatedPlaybackPositionsMs[key]?.let { return it }

        if (!metadata.isInit) {
            // First media request for this format: start at 0 to request the very first segment.
            estimatedPlaybackPositionsMs[key] = 0L
            return 0L
        }

        // Init requests get the current player position.
        val fallbackMs = roundMillis(lastPlayerPositionSeconds)
        estimatedPlaybackPositionsMs[key] = fallbackMs
        return fallbackMs
    }

    fun formatForKey(key: String): SabrFormat? {
        return formatsByKey[key]
    }

    fun applyStreamInfo(metadata: SabrRequestMetadata) {
        val info = metadata.streamInfo ?: return

        info.playbackCookie?.let { lastPlaybackCookie = it }

        info.formatInitMetadata.forEach { initMetadata ->
            val formatId = initMetadata.formatId
            if (formatId.hasItag()) {
                val key = FormatKeyUtils.fromFormatInitializationMetadata(initMetadata)
                if (key.isEmpty()) return@forEach
                val existing = initializedFormats[key]
                if (existing == null) {
                    initializedFormats[key] = InitializedFormat(formatId, mutableListOf())
                } else if (existing.formatId != formatId) {
                    initializedFormats[key] = existing.copy(formatId = formatId)
                }
            }
        }

        info.redirect?.url?.takeIf { !it.isNullOrBlank() }?.let { redirectUrl ->
            if (metadata.isSabr) {
                serverAbrStreamingUrl = redirectUrl
            }
        }

        info.sabrContextSendingPolicy?.startPolicyList?.forEach(activeSabrContextTypes::add)
        info.sabrContextSendingPolicy?.stopPolicyList?.forEach(activeSabrContextTypes::remove)
        info.sabrContextSendingPolicy?.discardPolicyList?.forEach { sabrContexts.remove(it) }

        info.sabrContextUpdate?.let { update ->
            if (update.hasType() && !update.value.isEmpty) {
                val type = update.type
                val shouldOverwrite = update.writePolicy == SabrContextWritePolicy.OVERWRITE
                if (shouldOverwrite || !sabrContexts.containsKey(type)) {
                    sabrContexts[type] = update
                }
                if (update.sendByDefault) {
                    activeSabrContextTypes.add(type)
                }
            }
        }

        info.nextRequestPolicy?.let { policy ->
            nextRequestPolicy = policy
            lastPlaybackCookie = policy.playbackCookie
        }

        info.mediaHeader?.let { mediaHeader ->
            val formatKey = FormatKeyUtils.fromMediaHeader(mediaHeader)
            if (formatKey.isNotEmpty()) {
                estimatedPlaybackPositionsMs[formatKey] = mediaHeader.startMs
            }
            if (!mediaHeader.isInitSeg) {
                recordMediaHeader(mediaHeader)
            }
        }
    }

    fun rememberInitSegment(
        format: SabrFormat,
        data: ByteArray,
        range: SabrRequestMetadata.ByteRange?,
    ) {
        val key = FormatKeyUtils.fromFormat(format) ?: return
        val existing = initSegmentStore[key]

        if (range == null || range.start <= 0L) {
            initSegmentStore[key] = data.copyOf()
            return
        }

        val start = range.start.coerceAtLeast(0L).toInt()
        val computedEnd = if (range.end >= range.start) range.end + 1 else start.toLong() + data.size
        val endExclusive = computedEnd.coerceAtLeast(start.toLong()).coerceAtMost(start.toLong() + data.size).toInt()
        val targetSize = maxOf(existing?.size ?: 0, maxOf(endExclusive, start + data.size))
        val merged = ByteArray(targetSize)

        existing?.copyInto(merged, 0, 0, existing.size.coerceAtMost(targetSize))
        val copyLength = data.size.coerceAtMost(targetSize - start)
        data.copyInto(merged, start, 0, copyLength)

        initSegmentStore[key] = merged
    }

    fun initSegmentSlice(format: SabrFormat, range: SabrRequestMetadata.ByteRange): ByteArray? {
        val key = FormatKeyUtils.fromFormat(format) ?: return null
        val data = initSegmentStore[key] ?: return null
        val start = range.start.coerceAtLeast(0L).coerceAtMost(data.size.toLong()).toInt()
        val endExclusive = when {
            range.end < 0 -> data.size
            else -> (range.end + 1).coerceAtMost(data.size.toLong()).toInt()
        }
        if (start >= data.size) return null
        val clampedEnd = endExclusive.coerceIn(start, data.size)
        return data.copyOfRange(start, clampedEnd)
    }


    fun clear() {
        requestMetadataManager.clear()
        cacheManager?.clear()
        sabrContexts.clear()
        activeSabrContextTypes.clear()
        serverAbrStreamingUrl = null
        ustreamerConfig = null
        lastPlaybackCookie = null
        lastRequestMetadata = null
        nextRequestPolicy = null
        sabrFormats = emptyList()
        formatsByItag.clear()
        formatsByKey.clear()
        requestCounter.set(0)
        lastManifestHash = null
        initializedFormats.clear()
        initSegmentStore.clear()
        playbackRate = 1f
        bandwidthEstimateBitsPerSec = 0L
        activeVideoFormat = null
        activeAudioFormat = null
        lastPlayerPositionSeconds = 0.0
        poToken = null
        clientInfo = null
        lastAbrRequestJson = null
        lastReloadPlaybackContextJson = null
    }

    fun recordMediaHeader(mediaHeader: MediaHeader) {
        if (!mediaHeader.hasFormatId()) return

        val headerKey = FormatKeyUtils.fromMediaHeader(mediaHeader)
        val formatIdKey = FormatKeyUtils.createKey(
            mediaHeader.formatId.itag,
            if (mediaHeader.formatId.hasXtags()) mediaHeader.formatId.xtags else null,
        )

        val resolvedKey = when {
            headerKey.isNotEmpty() && initializedFormats.containsKey(headerKey) -> headerKey
            initializedFormats.containsKey(formatIdKey) -> formatIdKey
            else -> formatIdKey.ifEmpty { headerKey }
        }

        Log.d(TAG, "recordMediaHeader headerKey=$headerKey formatIdKey=$formatIdKey resolvedKey=$resolvedKey")

        val entry = initializedFormats.getOrPut(resolvedKey) {
            InitializedFormat(
                formatId = mediaHeader.formatId,
                mediaHeaders = mutableListOf(),
            )
        }
        entry.mediaHeaders.add(mediaHeader)
        if (entry.mediaHeaders.size > MAX_BUFFERED_MEDIA_HEADERS) {
            entry.mediaHeaders.removeAt(0)
        }

        // When a segment finishes downloading we anticipate the next start time, matching the TS
        // adapter's trick so playerTimeMs is accurate even if ExoPlayer hasn't raised analytics
        // callbacks yet (critical right after a seek).
        computeNextSegmentStartMs(mediaHeader)?.let { nextStart ->
            estimatedPlaybackPositionsMs[resolvedKey] = nextStart
        }
    }

    /**
     * Builds the buffered range protobufs expected by SABR. Matches the TS adapter behaviour by
     * advertising a full-buffer sentinel for inactive tracks and a partial rolling history for the
     * active one so the server can decide which segment to deliver next.
     */
    fun buildBufferedRanges(
        currentFormat: SabrFormat?,
        activeVideo: SabrFormat?,
        activeAudio: SabrFormat?,
        isInit: Boolean,
    ): BufferedRangesResult {
        val bufferedRanges = mutableListOf<BufferedRange>()
        var formatToDiscard: SabrFormat? = null

        val activeFormats = buildList {
            activeVideo?.let(::add)
            activeAudio?.let(::add)
        }

        val currentKey = FormatKeyUtils.fromFormat(currentFormat)

        activeFormats.forEach { activeFormat ->
            val activeKey = FormatKeyUtils.fromFormat(activeFormat)
            val shouldDiscard = currentKey == null || currentKey != activeKey

            if (shouldDiscard) {
                bufferedRanges += createFullBufferRange(activeFormat)
                formatToDiscard = activeFormat
            } else if (!isInit) {
                createPartialBufferRange(initializedFormats[activeKey])?.let(bufferedRanges::add)
            }
        }

        return BufferedRangesResult(
            bufferedRanges = bufferedRanges,
            formatToDiscard = formatToDiscard,
        )
    }

    private fun createFullBufferRange(format: SabrFormat): BufferedRange {
        val maxInt = Int.MAX_VALUE
        val timeRange = TimeRangeOuterClass.TimeRange.newBuilder()
            .setDurationTicks(maxInt.toLong())
            .setStartTicks(0)
            .setTimescale(1000)
            .build()

        return BufferedRange.newBuilder()
            .setFormatId(format.toFormatId())
            .setDurationMs(maxInt.toLong())
            .setStartTimeMs(0)
            .setStartSegmentIndex(maxInt)
            .setEndSegmentIndex(maxInt)
            .setTimeRange(timeRange)
            .build()
    }

    /**
     * Collapses the short history of media headers into a single SABR buffered range entry for the
     * active format (matches TS' rolling-window approach).
     */
    private fun createPartialBufferRange(
        metadata: InitializedFormat?
    ): BufferedRange? {
        metadata ?: return null
        if (metadata.mediaHeaders.isEmpty()) return null

        val headers = metadata.mediaHeaders
        val first = headers.first()
        val last = headers.last()

        val durationMs = headers.sumOf { header ->
            if (header.hasDurationMs()) header.durationMs else 0L
        }

        val timeRangeBuilder = TimeRangeOuterClass.TimeRange.newBuilder()
            .setTimescale(1000)
            .setStartTicks(0)
            .setDurationTicks(durationMs)

        val startIndex = if (first.hasSequenceNumber()) first.sequenceNumber else 1
        val endIndex = if (last.hasSequenceNumber()) last.sequenceNumber else startIndex

        Log.d(
            TAG,
            "Buffered range built for format=${metadata.formatId.itag} startIndex=$startIndex endIndex=$endIndex durationMs=$durationMs startMs=${if (first.hasStartMs()) first.startMs else null}"
        )

        val rangeBuilder = BufferedRange.newBuilder()
            .setFormatId(metadata.formatId)
            .setDurationMs(durationMs)
            .setStartTimeMs(0)
            .setStartSegmentIndex(startIndex)
            .setEndSegmentIndex(endIndex)
            .setTimeRange(timeRangeBuilder.build())

        metadata.mediaHeaders.clear()
        return rangeBuilder.build()
    }

    data class BufferedRangesResult(
        val bufferedRanges: List<BufferedRange>,
        val formatToDiscard: SabrFormat?,
    )

    private data class InitializedFormat(
        val formatId: Common.FormatId,
        val mediaHeaders: MutableList<MediaHeader> = mutableListOf(),
    )

    private fun roundMillis(seconds: Double): Long {
        return (seconds * 1000.0).roundToLong()
    }

    companion object {
        private const val TAG = "SabrSessionManager"
        private const val SEEK_BACK_CLEAR_THRESHOLD_MS = 1000L
        private const val MAX_BUFFERED_MEDIA_HEADERS = 3

        private fun decodeBase64OrNull(value: String): ByteArray? {
            if (value.isBlank()) return null
            val paddedCandidates = mutableListOf(value)
            val remainder = value.length % 4
            if (remainder != 0) {
                paddedCandidates += value + "=".repeat(4 - remainder)
            }
            val flags = intArrayOf(
                Base64.DEFAULT,
                Base64.DEFAULT or Base64.URL_SAFE,
                Base64.URL_SAFE or Base64.NO_WRAP
            )
            for (candidate in paddedCandidates) {
                for (flag in flags) {
                    try {
                        return Base64.decode(candidate, flag)
                    } catch (_: IllegalArgumentException) {
                        // try next combo
                    }
                }
            }
            return null
        }

        /**
         * Helper mirroring TS: deduce the start time of the *next* segment so our playerTimeMs hint
         * stays accurate even before analytics callbacks fire (especially important after seeks).
         */
        private fun computeNextSegmentStartMs(mediaHeader: MediaHeader): Long? {
            if (!mediaHeader.hasStartMs()) return null
            val durationMs = when {
                mediaHeader.hasDurationMs() -> mediaHeader.durationMs
                mediaHeader.hasTimeRange() &&
                    mediaHeader.timeRange.hasDurationTicks() &&
                    mediaHeader.timeRange.hasTimescale() &&
                    mediaHeader.timeRange.timescale > 0 -> {
                    (mediaHeader.timeRange.durationTicks * 1000L) / mediaHeader.timeRange.timescale
                }
                else -> null
            }
            return durationMs?.let { mediaHeader.startMs + it }
        }

    }
}
