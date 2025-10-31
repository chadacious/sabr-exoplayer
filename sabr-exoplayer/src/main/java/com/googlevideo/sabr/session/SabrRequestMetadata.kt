package com.googlevideo.sabr.session

import misc.Common.FormatId
import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy
import video_streaming.PlaybackCookieOuterClass.PlaybackCookie
import video_streaming.ReloadPlayerResponse.ReloadPlaybackContext
import video_streaming.SabrContextSendingPolicyOuterClass.SabrContextSendingPolicy
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate
import video_streaming.SabrErrorOuterClass.SabrError
import video_streaming.SabrRedirectOuterClass.SabrRedirect
import video_streaming.SnackbarMessageOuterClass.SnackbarMessage
import video_streaming.StreamProtectionStatusOuterClass.StreamProtectionStatus

import kotlin.text.toLongOrNull

/**
 * Holds metadata associated with a single SABR/UMP network request. Mirrors the shape of the
 * TypeScript `SabrRequestMetadata` interface so we can port the runtime logic incrementally.
 */
data class SabrRequestMetadata(
    val byteRange: ByteRange? = null,
    val format: SabrFormat? = null,
    val isInit: Boolean = false,
    val isUmp: Boolean = false,
    val isSabr: Boolean = false,
    var streamInfo: StreamInfo? = null,
    var error: SabrErrorInfo? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    var requestedStartTimeMs: Long? = null,
    var requestedStartRange: Long? = null,
    // Set when the previous attempt received fallback media, so the follow-up mirrors the TS client
    // behaviour of omitting SABR contexts to encourage the server to honour buffered ranges.
    var suppressSabrContexts: Boolean = false,
) {

    data class ByteRange(
        val start: Long,
        val end: Long,
    )

    data class StreamInfo(
        val playbackCookie: PlaybackCookie? = null,
        val nextRequestPolicy: NextRequestPolicy? = null,
        val formatInitMetadata: List<FormatInitializationMetadata> = emptyList(),
        val streamProtectionStatus: StreamProtectionStatus? = null,
        val reloadPlaybackContext: ReloadPlaybackContext? = null,
        val sabrContextSendingPolicy: SabrContextSendingPolicy? = null,
        val sabrContextUpdate: SabrContextUpdate? = null,
        val snackbarMessage: SnackbarMessage? = null,
        val mediaHeader: MediaHeader? = null,
        val redirect: SabrRedirect? = null,
    )

    data class SabrErrorInfo(
        val sabrError: SabrError? = null,
    )
}

/**
 * Basic representation of a SABR format as described in the manifest / SABR payload.
 */
data class SabrFormat(
    val itag: Int,
    val xtags: String? = null,
    val lastModified: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val contentLength: Long? = null,
    val audioTrackId: String? = null,
    val mimeType: String? = null,
    val isDrc: Boolean = false,
    val quality: String? = null,
    val qualityLabel: String? = null,
    val averageBitrate: Int? = null,
    val bitrate: Int? = null,
    val audioQuality: String? = null,
    val approxDurationMs: Long? = null,
    val language: String? = null,
    val isDubbed: Boolean? = null,
    val isAutoDubbed: Boolean? = null,
    val isDescriptive: Boolean? = null,
    val isSecondary: Boolean? = null,
    val isOriginal: Boolean? = null,
    val audioChannels: Int? = null,
) {
    fun toFormatId(): FormatId = FormatId.newBuilder()
        .setItag(itag)
        .apply { this@SabrFormat.xtags?.let { value -> setXtags(value) } }
        .apply {
            val lastModifiedValue = this@SabrFormat.lastModified?.toLongOrNull()
            if (lastModifiedValue != null) {
                setLastModified(lastModifiedValue)
            }
        }
        .build()
}
