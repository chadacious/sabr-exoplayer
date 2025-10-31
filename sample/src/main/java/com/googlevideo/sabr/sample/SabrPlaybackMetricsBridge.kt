package com.googlevideo.sabr.sample

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.googlevideo.sabr.session.SabrFormat
import com.googlevideo.sabr.session.SabrSessionManager

/**
 * Bridges ExoPlayer analytics callbacks into [SabrSessionManager] so SABR requests are populated
 * with live playback metrics (player time, playback rate, bandwidth estimate, active formats).
 */
class SabrPlaybackMetricsBridge(
    private val sessionManager: SabrSessionManager,
    private val bandwidthMeter: BandwidthMeter,
) : Player.Listener, AnalyticsListener {

    companion object {
        private const val TAG = "SabrMetricsBridge"
    }

    override fun onEvents(player: Player, events: Player.Events) {
        updateMetrics(player = player)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        updateMetrics()
    }

    override fun onTracksChanged(tracks: Tracks) {
        updateMetrics()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val player = attachedPlayer ?: return
        val positionMs = newPosition.positionMs.takeUnless { it == C.TIME_UNSET } ?: player.currentPosition
        android.util.Log.d(
            TAG,
            "onPositionDiscontinuity reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs} resolved=$positionMs",
        )
        updateMetrics(player = player, overridePositionMs = positionMs)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateMetrics()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        updateMetrics()
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        updateMetrics()
    }

    override fun onAudioAttributesChanged(audioAttributes: androidx.media3.common.AudioAttributes) {
        updateMetrics()
    }

    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        // No direct metrics to update but keep active format selection in sync.
    }

    override fun onLoadStarted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        updateMetrics(eventTime = eventTime)
        recordUpcomingSegmentTime(eventTime, mediaLoadData)
    }

    private fun updateMetrics(
        eventTime: EventTime? = null,
        player: Player? = attachedPlayer,
        overridePositionMs: Long? = null,
    ) {
        val actualPlayer = player ?: return
        val rate = actualPlayer.playbackParameters.speed
        val bandwidthEstimate = bandwidthMeter.bitrateEstimate.takeIf { it > 0 } ?: 0L
        val positionMs = overridePositionMs
            ?: eventTime?.currentPlaybackPositionMs?.takeUnless { it == C.TIME_UNSET }
            ?: actualPlayer.currentPosition
        val positionSeconds = positionMs.coerceAtLeast(0L) / 1000.0

        android.util.Log.d(
            TAG,
            "updateMetrics posMs=$positionMs override=${overridePositionMs != null} fromEvent=${eventTime != null} rate=$rate bandwidth=$bandwidthEstimate",
        )
        sessionManager.updatePlaybackMetrics(
            playbackRate = rate,
            bandwidthEstimateBitsPerSec = bandwidthEstimate,
            playerPositionSeconds = positionSeconds,
        )

        val activeFormats = resolveActiveFormats(actualPlayer)
        sessionManager.updateActiveFormats(
            video = activeFormats.first,
            audio = activeFormats.second,
        )

        android.util.Log.d(
            TAG,
            "activeFormats video=${activeFormats.first?.itag} audio=${activeFormats.second?.itag}",
        )
    }

    private fun resolveActiveFormats(player: Player): Pair<SabrFormat?, SabrFormat?> {
        var activeVideo: SabrFormat? = null
        var activeAudio: SabrFormat? = null

        player.currentTracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    activeVideo = selectFormat(group, activeVideo)
                }
                C.TRACK_TYPE_AUDIO -> {
                    activeAudio = selectFormat(group, activeAudio)
                }
            }
        }

        return activeVideo to activeAudio
    }

    private fun selectFormat(group: Tracks.Group, current: SabrFormat?): SabrFormat? {
        if (current != null) return current
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) {
                return matchSabrFormat(group.getTrackFormat(i))
            }
        }
        return null
    }

    private fun matchSabrFormat(format: Format): SabrFormat? {
        // Match by itag if we can recover it from the format id or label.
        val itag = parseItag(format.id) ?: parseItag(format.label)
        val candidates = sessionManager.sabrFormats
        if (itag != null) {
            candidates.firstOrNull { it.itag == itag }?.let { return it }
        }

        // Fallback: try to match on resolution.
        if (format.width != Format.NO_VALUE && format.height != Format.NO_VALUE) {
            candidates.firstOrNull { it.width == format.width && it.height == format.height }?.let { return it }
        }

        // Audio fallback: match mime type + channel count.
        if (format.sampleMimeType != null) {
            candidates.firstOrNull {
                it.mimeType?.substringBefore(";") == format.sampleMimeType &&
                    (format.channelCount == Format.NO_VALUE || it.audioChannels == format.channelCount)
            }?.let { return it }
        }

        return null
    }

    private fun recordUpcomingSegmentTime(
        eventTime: EventTime,
        mediaLoadData: MediaLoadData,
    ) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        val trackFormat = mediaLoadData.trackFormat ?: return
        val sabrFormat = matchSabrFormat(trackFormat) ?: return

        val startTimeMs = when (val candidate = mediaLoadData.mediaStartTimeMs) {
            C.TIME_UNSET -> eventTime.currentPlaybackPositionMs
            else -> candidate
        }.takeUnless { it == C.TIME_UNSET } ?: return

        sessionManager.recordUpcomingSegmentTime(
            format = sabrFormat,
            startTimeMs = startTimeMs,
        )
    }

    private fun parseItag(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val digits = value.filter { it.isDigit() }
        return digits.toIntOrNull()
    }

    private var attachedPlayer: ExoPlayer? = null

    fun attach(player: ExoPlayer) {
        player.addListener(this)
        player.addAnalyticsListener(this)
        attachedPlayer = player
        updateMetrics(player = player)
    }

    fun detach(player: ExoPlayer) {
        player.removeListener(this)
        player.removeAnalyticsListener(this)
        attachedPlayer = null
    }

    fun refreshMetrics() {
        updateMetrics()
    }

}
