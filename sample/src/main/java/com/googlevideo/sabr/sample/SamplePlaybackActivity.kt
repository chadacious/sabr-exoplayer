package com.googlevideo.sabr.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.googlevideo.sabr.logging.AndroidSabrLogger
import com.googlevideo.sabr.logging.SabrLogger
import okhttp3.OkHttpClient
import kotlin.text.Charsets

@UnstableApi
class SamplePlaybackActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var harnessResult: SabrPlaybackHarness.HarnessResult? = null
    private val okHttpClient by lazy { OkHttpClient() }
    private val harness by lazy {
        SabrPlaybackHarness(
            context = this,
            okHttpClient = okHttpClient,
            logger = AndroidSabrLogger(SabrLogger.Level.DEBUG),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample_playback)
        playerView = findViewById(R.id.player_view)

        val manifest = assets.open(SAMPLE_MANIFEST_PATH).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        harnessResult = harness.preparePlayer(
            manifestXml = manifest,
            autoPlay = true,
            dumpConfig = null,
        ).also { result ->
            playerView.player = result.player
        }
    }

    override fun onStart() {
        super.onStart()
        harnessResult?.player?.playWhenReady = true
        harnessResult?.player?.play()
    }

    override fun onStop() {
        super.onStop()
        harnessResult?.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        harnessResult?.player?.release()
        harnessResult = null
    }

    companion object {
        private const val SAMPLE_MANIFEST_PATH = "sabr/sample_sabr_manifest.mpd"
    }
}
