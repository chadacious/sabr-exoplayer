package com.googlevideo.sabr.datasource

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.logging.SabrLogger
import com.googlevideo.sabr.session.SabrSessionManager

@UnstableApi
class SabrDataSourceFactory(
    private val delegateFactory: DataSource.Factory,
    private val sessionManager: SabrSessionManager,
    private val sessionProvider: () -> SabrSession = { SabrSession.Empty },
    private val segmentFetcher: SabrSegmentFetcher = SabrSegmentFetcher.default(),
    private val logger: SabrLogger = SabrLogger.NO_OP,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return SabrDataSource(
            delegateFactory = delegateFactory,
            sessionManager = sessionManager,
            sessionProvider = sessionProvider,
            segmentFetcher = segmentFetcher,
            logger = logger,
        )
    }
}
