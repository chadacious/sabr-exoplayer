package com.googlevideo.sabr.sample

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.datasource.DefaultSabrSegmentFetcher
import com.googlevideo.sabr.datasource.SabrDataSourceFactory
import com.googlevideo.sabr.datasource.SabrSegmentDumper
import com.googlevideo.sabr.manifest.SabrManifestParser
import com.googlevideo.sabr.logging.AndroidSabrLogger
import com.googlevideo.sabr.logging.SabrLogger
import com.googlevideo.sabr.logging.d
import com.googlevideo.sabr.logging.w
import com.googlevideo.sabr.session.SabrSessionManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.text.Charsets

/**
 * Utility harness that wires SABR manifest parsing, session management, and ExoPlayer together so
 * hosts (or instrumentation tests) can exercise SABR playback end-to-end.
 *
 * The harness does not start playback automatically; callers can start the returned player and
 * attach additional listeners as needed.
 */
@UnstableApi
class SabrPlaybackHarness(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val logger: SabrLogger = AndroidSabrLogger(SabrLogger.Level.DEBUG),
) {

    private val bandwidthMeter by lazy {
        DefaultBandwidthMeter.Builder(context).build()
    }

    fun preparePlayer(
        manifestXml: String,
        autoPlay: Boolean = false,
        dumpConfig: DumpConfig? = null,
    ): HarnessResult {
        val manifestParseResult = SabrManifestParser.parse(manifestXml)
        val session = manifestParseResult.session
        val sessionManager = SabrSessionManager()
        sessionManager.applyManifest(session.manifestInfo)

        dumpConfig?.let { clearDumpDirectory(it) }

        val httpFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
        val segmentDumper = dumpConfig?.baseUrl?.let {
            SabrSegmentDumper(
                baseUrl = it,
                callFactory = okHttpClient,
                logger = logger,
            )
        }
        val sabrDataSourceFactory = SabrDataSourceFactory(
            delegateFactory = httpFactory,
            sessionManager = sessionManager,
            sessionProvider = { session },
            segmentFetcher = DefaultSabrSegmentFetcher(
                callFactory = okHttpClient,
                logger = logger,
                segmentDumper = segmentDumper,
            ),
            logger = logger,
        )

        val dashChunkSourceFactory = DefaultDashChunkSource.Factory(sabrDataSourceFactory)
        val manifestDataSourceFactory = InMemoryManifestDataSourceFactory(manifestXml.toByteArray(Charsets.UTF_8))

        val dashMediaSourceFactory = DashMediaSource.Factory(
            dashChunkSourceFactory,
            manifestDataSourceFactory,
        )

        val player = ExoPlayer.Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            .build()

        val metricsBridge = SabrPlaybackMetricsBridge(sessionManager, bandwidthMeter)
        metricsBridge.attach(player)

        val mediaItem = MediaItem.Builder()
            .setUri(HARNESS_MEDIA_URI)
            .build()

        val mediaSource = dashMediaSourceFactory.createMediaSource(mediaItem)
        player.setMediaSource(mediaSource)
        player.prepare()
        if (autoPlay) {
            player.playWhenReady = true
            player.play()
        }

        return HarnessResult(
            player = player,
            session = session,
            sessionManager = sessionManager,
            manifestResult = manifestParseResult,
            metricsBridge = metricsBridge,
        )
    }

    data class HarnessResult(
        val player: ExoPlayer,
        val session: SabrSession,
        val sessionManager: SabrSessionManager,
        val manifestResult: SabrManifestParser.Result,
        val metricsBridge: SabrPlaybackMetricsBridge,
    ) {
        fun play() {
            player.playWhenReady = true
        }
    }

    private class InMemoryManifestDataSourceFactory(
        private val manifestBytes: ByteArray,
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            return ByteArrayDataSource(manifestBytes.copyOf())
        }
    }

    private fun clearDumpDirectory(config: DumpConfig) {
        val clearUrl = config.baseUrl.newBuilder()
            .addQueryParameter("clear", DUMP_ROOT_DIRECTORY)
            .build()
        val request = Request.Builder()
            .url(clearUrl)
            .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
            .build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logger.w("SabrPlaybackHarness", e) { "Failed to clear SABR dump directory" }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                logger.d("SabrPlaybackHarness") { "Cleared SABR dump directory via $clearUrl" }
            }
        })
    }

    data class DumpConfig(val baseUrl: HttpUrl)

    companion object {
        private const val HARNESS_MEDIA_URI = "https://example.com/harness.sabr.mpd"
        private const val DUMP_ROOT_DIRECTORY = "../../sabr-android"
    }
}
