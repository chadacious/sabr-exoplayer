package com.googlevideo.sabr.datasource

import androidx.media3.datasource.DataSpec
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.session.SabrFormat
import com.googlevideo.sabr.session.SabrRequestMetadata
import com.googlevideo.sabr.session.SabrSessionManager
import java.io.IOException

interface SabrSegmentFetcher {

    @Throws(IOException::class)
    fun fetch(
        request: SabrSegmentRequest,
        session: SabrSession,
        sessionManager: SabrSessionManager,
    ): SabrSegmentResult

    companion object {
        fun unsupported(): SabrSegmentFetcher = object : SabrSegmentFetcher {
            override fun fetch(
                request: SabrSegmentRequest,
                session: SabrSession,
                sessionManager: SabrSessionManager,
            ): SabrSegmentResult {
                throw UnsupportedOperationException("SABR segment fetching not yet implemented")
            }
        }

        fun default(): SabrSegmentFetcher = DefaultSabrSegmentFetcher()
    }
}

data class SabrSegmentRequest(
    val dataSpec: DataSpec,
    val key: String,
    val format: SabrFormat?,
)

data class SabrSegmentResult(
    val data: ByteArray,
    val metadata: SabrRequestMetadata? = null,
)
